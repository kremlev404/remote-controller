plugins {
    alias(libs.plugins.remotecontroller.android.application)
}

android {
    namespace = "com.remotecontroller.android"

    defaultConfig {
        applicationId = "com.remotecontroller.android"
        versionCode = rootProject.extra["productVersionCode"] as Int
        versionName = rootProject.extra["productVersion"] as String
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":client:feature:common"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.android)
}
