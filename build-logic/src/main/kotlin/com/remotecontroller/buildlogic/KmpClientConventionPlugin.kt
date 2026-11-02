package com.remotecontroller.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/** A layer of the phone apps under the screens: domain, data, storage. */
class KmpClientConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(KmpLibraryConventionPlugin::class.java)
        addClientDependencies()
    }
}
