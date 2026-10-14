plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.remotecontroller.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.remotecontroller.android"
        minSdk = 26
        targetSdk = 37
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":client:ui"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.android)
}
