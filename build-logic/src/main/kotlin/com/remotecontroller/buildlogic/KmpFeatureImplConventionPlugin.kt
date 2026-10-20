package com.remotecontroller.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.compose.ComposePlugin
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class KmpFeatureImplConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(KmpComposeConventionPlugin::class.java)

        val apiProject = path.removeSuffix(":impl")
        check(apiProject != path) {
            "remotecontroller.kmp.feature.impl applies to a module whose path ends with :impl. Was $path."
        }

        val compose = ComposePlugin.Dependencies(this)
        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.named("commonMain") {
                dependencies {
                    api(project("$apiProject:api"))
                    implementation(project(":client:domain"))
                    implementation(project(":shared"))
                    implementation(compose.materialIconsExtended)
                    implementation(libs.findLibrary("jetbrains-lifecycle-viewmodel-compose").get())
                    implementation(libs.findLibrary("kotlinx-coroutines-core").get())
                    implementation(libs.findLibrary("koin-core").get())
                    implementation(libs.findLibrary("koin-compose").get())
                    implementation(libs.findLibrary("koin-compose-viewmodel").get())
                }
            }
        }
    }
}
