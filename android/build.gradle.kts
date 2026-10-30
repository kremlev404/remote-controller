plugins {
    alias(libs.plugins.remotecontroller.android.application)
}

// Release signing comes from the environment (CI secrets) or ~/.gradle/gradle.properties.
// Without a keystore the release APK is built unsigned.
fun signingValue(name: String): String? =
    providers.environmentVariable(name).orElse(providers.gradleProperty(name)).orNull?.takeIf { it.isNotBlank() }

val releaseKeystore = signingValue("ANDROID_KEYSTORE_FILE")

android {
    namespace = "com.remotecontroller.android"

    if (releaseKeystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signingValue("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = signingValue("ANDROID_KEY_ALIAS")
                keyPassword = signingValue("ANDROID_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.remotecontroller.android"
        versionCode = rootProject.extra["productVersionCode"] as Int
        versionName = rootProject.extra["productVersion"] as String
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
