plugins {
    alias(libs.plugins.remotecontroller.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
        }
    }
}
