plugins {
    alias(libs.plugins.remotecontroller.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.koin.core)
        }
    }
}
