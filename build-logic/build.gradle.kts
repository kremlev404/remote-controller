plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "remotecontroller.kmp.library"
            implementationClass = "com.remotecontroller.buildlogic.KmpLibraryConventionPlugin"
        }
        register("kmpCompose") {
            id = "remotecontroller.kmp.compose"
            implementationClass = "com.remotecontroller.buildlogic.KmpComposeConventionPlugin"
        }
        register("kmpFeatureApi") {
            id = "remotecontroller.kmp.feature.api"
            implementationClass = "com.remotecontroller.buildlogic.KmpFeatureApiConventionPlugin"
        }
        register("kmpFeatureImpl") {
            id = "remotecontroller.kmp.feature.impl"
            implementationClass = "com.remotecontroller.buildlogic.KmpFeatureImplConventionPlugin"
        }
        register("kmpClient") {
            id = "remotecontroller.kmp.client"
            implementationClass = "com.remotecontroller.buildlogic.KmpClientConventionPlugin"
        }
        register("kmpWasm") {
            id = "remotecontroller.kmp.wasm"
            implementationClass = "com.remotecontroller.buildlogic.KmpWasmConventionPlugin"
        }
        register("jvmServer") {
            id = "remotecontroller.jvm.server"
            implementationClass = "com.remotecontroller.buildlogic.JvmServerConventionPlugin"
        }
        register("jvmLibrary") {
            id = "remotecontroller.jvm.library"
            implementationClass = "com.remotecontroller.buildlogic.JvmLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "remotecontroller.android.application"
            implementationClass = "com.remotecontroller.buildlogic.AndroidApplicationConventionPlugin"
        }
    }
}
