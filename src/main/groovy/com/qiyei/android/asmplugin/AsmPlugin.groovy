package com.qiyei.android.asmplugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import com.android.build.gradle.AppExtension

public class AsmPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        def android = project.extensions.getByType(AppExtension)
        println 'AsmPlugin ----------- 开始注册 >>>>> -----------'
        AsmTransform transform = new AsmTransform()
        android.registerTransform(transform)
    }
}