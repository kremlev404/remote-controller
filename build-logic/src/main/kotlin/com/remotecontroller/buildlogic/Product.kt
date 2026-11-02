package com.remotecontroller.buildlogic

import org.gradle.api.Project
import java.util.Properties

/** The app's identity, from version.properties at the root of the repository. */
class Product(
    val name: String,
    val version: String,
    val versionCode: Int,
)

val Project.product: Product
    get() {
        val properties = Properties()
        rootProject.file("version.properties").inputStream().use(properties::load)
        return Product(
            name = properties.getProperty("name").trim(),
            version = properties.getProperty("version").trim(),
            versionCode = properties.getProperty("versionCode").trim().toInt(),
        )
    }
