plugins {
    alias(libs.plugins.remotecontroller.kmp.compose)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ClientUI"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":client:domain"))
            implementation(project(":client:storage"))
            implementation(project(":client:data"))
            implementation(project(":client:feature:umbrella"))
            implementation(libs.jetbrains.navigation.compose)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
        }
    }
}
