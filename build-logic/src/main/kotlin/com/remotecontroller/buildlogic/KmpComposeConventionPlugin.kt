package com.remotecontroller.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

class KmpComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(KmpLibraryConventionPlugin::class.java)
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        addComposeDependencies()
    }
}
