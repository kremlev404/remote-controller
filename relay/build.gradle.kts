import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.remotecontroller.relay.MainKt")
}

// Netty pulls QUIC/HTTP3 native libraries for five platforms (~11 MB). The relay speaks HTTP/1.1 only.
configurations.matching { it.name == "runtimeClasspath" }.configureEach {
    exclude(group = "io.netty", module = "netty-codec-native-quic")
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    runtimeOnly(libs.slf4j.simple)
}

// The relay serves the same browser page as the desktop, so it ships the current build of :web.
tasks.named<ProcessResources>("processResources") {
    val webDistribution = project(":web").layout.buildDirectory.dir("dist/wasmJs/productionExecutable")
    dependsOn(":web:wasmJsBrowserDistribution")
    from(webDistribution) {
        into("web")
    }
}
