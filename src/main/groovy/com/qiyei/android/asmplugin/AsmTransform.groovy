package com.qiyei.android.asmplugin

import com.android.build.api.transform.DirectoryInput
import com.android.build.api.transform.Format
import com.android.build.api.transform.JarInput
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformException
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.TransformInvocation
import com.android.build.api.transform.TransformOutputProvider
import com.android.build.api.transform.Status
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.ide.common.internal.WaitableExecutor
import com.android.utils.FileUtils
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.compress.utils.IOUtils
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

import java.util.concurrent.Callable
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry


public class AsmTransform extends Transform {
    private WaitableExecutor mWaitableExecutor = WaitableExecutor.useGlobalSharedThreadPool()

    /**
     * 设置我们自定义的Transform对应的Task名称
     * 编译的时候可以在控制台看到 比如：Task :app:transformClassesWithAsmTransformForDebug
     * @return
     */
    @Override
    String getName() {
        return "AsmTransform"
    }

    /**
     * 指定输入的类型，通过这里的设定，可以指定我们要处理的文件类型
     * 这样确保其他类型的文件不会传入
     * @return
     */
    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    /**
     * 指定Transform的作用范围
     * @return
     */
    @Override
    Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    @Override
    boolean isIncremental() {
        return true
    }

    @Override
    void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
        long startTime = System.currentTimeMillis()
        println '----------- startTime <' + startTime + '> -----------'
        //拿到所有的class文件
        Collection<TransformInput> inputs = transformInvocation.inputs
        TransformOutputProvider outputProvider = transformInvocation.outputProvider

        //当前是否是增量编译,由isIncremental方法决定的
        // 当上面的isIncremental()写的返回true,这里得到的值不一定是true,还得看当时环境.比如clean之后第一次运行肯定就不是增量编译嘛.
        boolean isIncremental = transformInvocation.isIncremental()
        println("transform isIncremental = $isIncremental")
        if (!isIncremental && outputProvider != null) {
            //不是增量编译则删除之前的所有文件
            outputProvider.deleteAll()
        }
        //遍历inputs Transform的inputs有两种类型，一种是目录，一种是jar包，要分开遍历
        inputs.each { TransformInput input ->
            //遍历directoryInputs(文件夹中的class文件) directoryInputs代表着以源码方式参与项目编译的所有目录结构及其目录下的源码文件

            // 比如我们手写的类以及R.class、BuildConfig.class以及R$XXX.class等
            input.directoryInputs.each { DirectoryInput directoryInput ->
                //多线程
                mWaitableExecutor.execute(new Callable<Object>() {
                    @Override
                    Object call() throws Exception {
                        //文件夹中的class文件
                        processSourceInput(directoryInput, outputProvider, isIncremental)
                        return null
                    }
                })
            }

            //遍历jar包中的class文件 jarInputs代表以jar包方式参与项目编译的所有本地jar包或远程jar包
            input.jarInputs.each { JarInput jarInput ->
                //多线程处理jar
                mWaitableExecutor.execute(new Callable<Object>() {
                    @Override
                    Object call() throws Exception {
                        //处理jar包中的class文件
                        //processJarInput(jarInput, outputProvider,isIncremental)
                        return null
                    }
                })

            }
            //等待所有任务结束
            mWaitableExecutor.waitForTasksWithQuickFail(true)
        }
    }

    /**
     * 遍历directoryInputs  得到对应的class  交给ASM处理
     * @param input
     * @param outputProvider
     */
    private void processSourceInput(DirectoryInput input, TransformOutputProvider outputProvider, boolean isIncremental) {
        File dest = outputProvider.getContentLocation(input.name, input.contentTypes, input.scopes, Format.DIRECTORY)
        FileUtils.forceMkdir(dest)

        if (isIncremental) {
            String srcDirPath = input.getFile().getAbsolutePath()
            String destDirPath = dest.getAbsolutePath()
            Map<File, Status> fileStatusMap = input.getChangedFiles()
            for (Map.Entry<File, Status> changedFile : fileStatusMap.entrySet()) {
                Status status = changedFile.getValue()
                File inputFile = changedFile.getKey()
                String destFilePath = inputFile.getAbsolutePath().replace(srcDirPath, destDirPath)
                File destFile = new File(destFilePath)
                switch (status) {
                    case Status.NOTCHANGED:
                        break
                    case Status.ADDED:
                    case Status.CHANGED:
                        FileUtils.touch(destFile)
                        interceptDirClass(inputFile)
                        transformSingleFile(inputFile, destFile)
                        break
                    case Status.REMOVED:
                        if (destFile.exists()) {
                            FileUtils.forceDelete(destFile)
                        }
                        break
                }
            }
        } else {
            interceptDirClass(input.file)
            transformDirectory(input.file, dest)
        }
    }


    private void transformSingleFile(File inputFile, File destFile) {
        FileUtils.copyFile(inputFile, destFile)
    }

    private void transformDirectory(File directoryInputFile, File dest) {
        FileUtils.copyDirectory(directoryInputFile, dest)
    }

    private void interceptDirClass(JarInput input){
        //是否是文件夹
        if (input.file.isDirectory()) {
            //列出目录所有文件（包含子文件夹，子文件夹内文件）
            input.file.eachFileRecurse { File file ->
                visitorClass(file)
            }
        } else {
            visitorClass(input.file)
        }
    }

    private void visitorClass(File file){
        String name = file.name
        //需要插桩class 根据自己的需求来------------- 这里判断是否是我们自己写的Application
        println("visitorClass=" + name)
        ClassReader classReader = new ClassReader(file.bytes)
        //创建ClassNode,读取的信息会封装到这个类里面
        ClassNode cn = new ClassNode()
        //开始读取
        classReader.accept(cn, 0)
        //获取声明的所有注解
        List<AnnotationNode> annotations = cn.visibleAnnotations
        if(annotations!=null) {//便利注解
            for(AnnotationNode an: annotations) {
                //获取注解的描述信息
                String anno = an.desc.replaceAll("/", ".");
                String annoName = anno.substring(1, anno.length()-1);
                if("com.mh.base.quartz.annotation.BaseQuartz".equals(annoName)) {
                    String className = cn.name.replaceAll("/", ".");
                    //获取注解的属性名对应的值，（values是一个集合，它将注解的属性和属性值都放在了values中，通常奇数为值偶数为属性名）
                    String valu = an.values.get(1).toString();
                    System.out.println(className);
                    System.out.println(valu);
                    //根据匹配的注解，将其封装给具体的业务使用
                    //taskClazz.put(valu, Class.forName(className));
                }
            }
        }

        //传入COMPUTE_MAXS  ASM会自动计算本地变量表和操作数栈
        ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS)
        //创建类访问器   并交给它去处理
        ClassVisitor classVisitor = new CustomInterceptVisitor(classWriter)
        classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES)
        byte[] code = classWriter.toByteArray()
        FileOutputStream fos = new FileOutputStream(file.parentFile.absolutePath + File.separator + name)
        fos.write(code)
        fos.close()
    }

    private void tetst(){

    }


    /**
     * 遍历jarInputs 得到对应的class 交给ASM处理
     * @param jarInput
     * @param outputProvider
     */
    private void processJarInput(JarInput jarInput, TransformOutputProvider outputProvider, boolean isIncremental) {
        def status = jarInput.status
        File dest = outputProvider.getContentLocation(jarInput.file.absolutePath, jarInput.contentTypes, jarInput.scopes, Format.JAR)
        if (isIncremental) {
            switch (status) {
                case Status.NOTCHANGED:
                    break
                case Status.ADDED:
                case Status.CHANGED:
                    transformJar(interceptJar(jarInput), dest)
                    break
                case Status.REMOVED:
                    if (dest.exists()) {
                        FileUtils.forceDelete(dest)
                    }
                    break
            }
        } else {
            transformJar(interceptJar(jarInput), dest)
        }
    }

    private void transformJar(File jarInputFile, File dest) {
        FileUtils.copyFile(jarInputFile, dest)
    }

    private File interceptJar(JarInput jarInput){
        File tmpFile = null
        if (jarInput.file.getAbsolutePath().endsWith(".jar")) {
            //重名名输出文件,因为可能同名,会覆盖
            def jarName = jarInput.name
            def md5Name = DigestUtils.md5Hex(jarInput.file.getAbsolutePath())
            if (jarName.endsWith(".jar")) {
                jarName = jarName.substring(0, jarName.length() - 4)
            }
            JarFile jarFile = new JarFile(jarInput.file)
            Enumeration enumeration = jarFile.entries()
            tmpFile = new File(jarInput.file.getParent() + File.separator + "classes_temp.jar")
            //避免上次的缓存被重复插入
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
            JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(tmpFile))
            //用于保存
            while (enumeration.hasMoreElements()) {
                JarEntry jarEntry = (JarEntry) enumeration.nextElement()
                String entryName = jarEntry.getName()
                ZipEntry zipEntry = new ZipEntry(entryName)
                InputStream inputStream = jarFile.getInputStream(jarEntry)

                //需要插桩class 根据自己的需求来-------------
                if ("androidx/fragment/app/FragmentActivity.class".equals(entryName)) {
                    //class文件处理
                    println '----------- jar class  <' + entryName + '> -----------'
                    jarOutputStream.putNextEntry(zipEntry)
                    ClassReader classReader = new ClassReader(IOUtils.toByteArray(inputStream))
                    ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS)
                    //创建类访问器   并交给它去处理
                    ClassVisitor cv = new CustomInterceptVisitor(classWriter)
                    classReader.accept(cv, ClassReader.EXPAND_FRAMES)
                    byte[] code = classWriter.toByteArray()
                    jarOutputStream.write(code)
                } else {
                    jarOutputStream.putNextEntry(zipEntry)
                    jarOutputStream.write(IOUtils.toByteArray(inputStream))
                }
                jarOutputStream.closeEntry()
            }
            //结束
            jarOutputStream.close()
            jarFile.close()
        } else {
            tmpFile = jarInput.file
        }
        return tmpFile
    }
}