package com.remotecontroller.buildlogic

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

internal object Sdk {
    const val compile = 37
    const val min = 26
    const val target = 37
    const val jvm = 21
}

internal fun Project.remoteControllerNamespace(): String =
    "com.remotecontroller." + path.removePrefix(":").replace(':', '.')

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.configureKmpTargets() {
    extensions.configure<KotlinMultiplatformExtension> {
        jvmToolchain(Sdk.jvm)
        iosArm64()
        iosSimulatorArm64()
        (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = remoteControllerNamespace()
            compileSdk = Sdk.compile
            minSdk = Sdk.min
        }
    }
}

internal fun Project.addComposeDependencies() {
    extensions.configure<KotlinMultiplatformExtension> {
        sourceSets.named("commonMain") {
            dependencies {
                implementation(libs.findLibrary("compose-runtime").get())
                implementation(libs.findLibrary("compose-foundation").get())
                implementation(libs.findLibrary("compose-material3").get())
                implementation(libs.findLibrary("compose-ui").get())
            }
        }
    }
}
