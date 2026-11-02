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
            implementation(project(":client:kmp:core"))
            implementation(project(":client:kmp:domain"))
            implementation(project(":client:kmp:storage"))
            implementation(project(":client:kmp:data"))
            implementation(project(":client:kmp:feature:umbrella"))
            implementation(libs.jetbrains.navigation.compose)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
        }
    }
}
