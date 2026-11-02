plugins {
    alias(libs.plugins.remotecontroller.kmp.library)
    alias(libs.plugins.remotecontroller.kmp.wasm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
