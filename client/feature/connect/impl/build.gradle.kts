plugins {
    alias(libs.plugins.remotecontroller.kmp.feature.impl)
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
        }
        iosMain.dependencies {
            implementation(project(":client:data"))
        }
    }
}
