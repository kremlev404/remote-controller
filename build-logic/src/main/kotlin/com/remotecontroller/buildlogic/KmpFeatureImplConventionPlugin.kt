package com.remotecontroller.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class KmpFeatureImplConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(KmpComposeConventionPlugin::class.java)
        addClientDependencies()

        val apiProject = path.removeSuffix(":impl")
        check(apiProject != path) {
            "remotecontroller.kmp.feature.impl applies to a module whose path ends with :impl. Was $path."
        }

        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.named("commonMain") {
                dependencies {
                    api(project("$apiProject:api"))
                    implementation(project(":client:kmp:domain"))
                    implementation(libs.findLibrary("compose-material-icons-extended").get())
                    implementation(libs.findLibrary("jetbrains-lifecycle-viewmodel-compose").get())
                    implementation(libs.findLibrary("koin-compose").get())
                    implementation(libs.findLibrary("koin-compose-viewmodel").get())
                }
            }
        }
    }
}
