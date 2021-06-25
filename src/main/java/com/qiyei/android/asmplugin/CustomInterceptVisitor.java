package com.qiyei.android.asmplugin;


import org.objectweb.asm.*;

public class CustomInterceptVisitor extends ClassVisitor {
    private String mClassName;

    public CustomInterceptVisitor(ClassVisitor classVisitor) {
        super(Opcodes.ASM7, classVisitor);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        System.out.println("LogVisitor : visit -----> started:" + name);
        this.mClassName = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    /**
     * 定义一个方法， 返回的MethodVisitor用于生成方法相关的信息
     * @param access
     * @param name
     * @param desc
     * @param signature
     * @param exceptions
     * @return
     */
    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
        if ("com/fawvw/vehice/vision/VisionApplication".equals(this.mClassName)) {
            if ("onCreate".equals(name)) {
                //处理onCreate
                System.out.println("LogVisitor : visitMethod method ----> " + name);
                return new OnCreateVisitor(mv);
            }
        }
        return mv;
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
        return super.visitTypeAnnotation(typeRef, typePath, descriptor, visible);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        return super.visitAnnotation(descriptor, visible);
    }

    //访问结束
    @Override
    public void visitEnd() {
        System.out.println("LogVisitor : visit -----> end");
        super.visitEnd();
    }
}