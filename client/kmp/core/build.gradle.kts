plugins {
    alias(libs.plugins.remotecontroller.kmp.compose)
    alias(libs.plugins.remotecontroller.kmp.wasm)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
        }
    }
}
