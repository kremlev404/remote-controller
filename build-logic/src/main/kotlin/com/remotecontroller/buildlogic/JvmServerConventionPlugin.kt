package com.remotecontroller.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.named
import org.gradle.language.jvm.tasks.ProcessResources

/** A Ktor server over HTTP/1.1 that also serves the browser page: the desktop app and the relay. */
class JvmServerConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply(JvmLibraryConventionPlugin::class.java)
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

        // Netty pulls QUIC/HTTP3 native libraries for five platforms (~11 MB). The servers speak HTTP/1.1 only.
        configurations.matching { it.name == "runtimeClasspath" }.configureEach {
            exclude(mapOf("group" to "io.netty", "module" to "netty-codec-native-quic"))
        }

        dependencies {
            add("implementation", project(":shared"))
            listOf(
                "kotlinx-coroutines-core",
                "kotlinx-serialization-json",
                "ktor-server-core",
                "ktor-server-netty",
                "ktor-server-websockets",
            ).forEach { name -> add("implementation", libs.findLibrary(name).get()) }
            add("runtimeOnly", libs.findLibrary("slf4j-simple").get())
        }

        // Both ship the current build of the page, so a server never serves an old one.
        tasks.named<ProcessResources>("processResources") {
            val webDistribution = project(":client:web").layout.buildDirectory.dir("dist/wasmJs/productionExecutable")
            dependsOn(":client:web:wasmJsBrowserDistribution")
            from(webDistribution) {
                into("web")
            }
        }
        Unit
    }
}
