import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
}

val productProperties = Properties().apply {
    file("version.properties").inputStream().use { load(it) }
}

extra["productName"] = productProperties.getProperty("name").trim()
extra["productVersion"] = productProperties.getProperty("version").trim()
extra["productVersionCode"] = productProperties.getProperty("versionCode").trim().toInt()

tasks.register("packageDesktop") {
    group = "distribution"
    description = "Build every desktop artifact into artifacts/desktop"
    dependsOn(":desktop:packageDesktop")
}
