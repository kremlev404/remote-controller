package com.remotecontroller.buildlogic

import org.gradle.api.Project
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import java.io.File
import java.util.Properties

private const val PACKAGING_JDK_VERSION = 21

/** Major version from the JDK's `release` file (JAVA_VERSION="21.0.6" → 21), or null. */
private fun jdkMajorVersion(jdkHome: String): Int? {
    val release = File(jdkHome.trim().trim('"'), "release")
    if (!release.isFile) return null
    val line = release.readLines().firstOrNull { it.startsWith("JAVA_VERSION=") } ?: return null
    return line.substringAfter('=').trim('"').substringBefore('.').toIntOrNull()
}

fun hasJpackage(jdkHome: String): Boolean {
    val home = File(jdkHome.trim().trim('"'))
    val jpackageName = if (hostOs == HostOs.WINDOWS) "jpackage.exe" else "jpackage"
    return File(home, "bin/$jpackageName").isFile
}

private fun hasJniHeaders(jdkHome: String): Boolean {
    val home = File(jdkHome.trim().trim('"'))
    return File(home, "include/jni.h").isFile && File(home, "include/darwin/jni_md.h").isFile
}

/** [directory] or a JDK directly inside it, with macOS's Contents/Home layout allowed for. */
private fun jdkHomeIn(directory: File, matches: (String) -> Boolean): String? {
    if (!directory.isDirectory) return null
    if (matches(directory.absolutePath)) return directory.absolutePath

    val contentsHome = File(directory, "Contents/Home")
    if (matches(contentsHome.absolutePath)) return contentsHome.absolutePath

    return directory.listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory }
        ?.sortedByDescending { it.name }
        ?.mapNotNull { child ->
            when {
                matches(child.absolutePath) -> child.absolutePath
                matches(File(child, "Contents/Home").absolutePath) -> File(child, "Contents/Home").absolutePath
                else -> null
            }
        }
        ?.firstOrNull()
}

private fun Project.readLocalProperty(key: String): String? {
    val localProperties = rootProject.file("local.properties")
    if (!localProperties.isFile) return null

    return Properties().apply {
        localProperties.inputStream().use { load(it) }
    }.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
}

private fun Project.toolchainHome(version: Int): String? =
    extensions.findByType(JavaToolchainService::class.java)?.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(version))
    }?.orNull?.metadata?.installationPath?.asFile?.absolutePath

/** A JDK with the macOS JNI headers, for the Core Audio library. */
fun Project.discoverJniJdkHome(): String? {
    val candidates = listOfNotNull(
        toolchainHome(PACKAGING_JDK_VERSION),
        findProperty("desktop.jdk.home")?.toString(),
        rootProject.findProperty("desktop.jdk.home")?.toString(),
        readLocalProperty("desktop.jdk.home"),
        System.getenv("DESKTOP_JDK_HOME"),
        System.getenv("JAVA_HOME"),
        System.getProperty("java.home"),
    ).cleaned()

    candidates.firstOrNull(::hasJniHeaders)?.let { return it }

    val searchRoots = listOf(
        File("/Library/Java/JavaVirtualMachines"),
        File(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines"),
        File(System.getProperty("user.home"), "Library/Java/JetBrainsRuntime"),
        File(System.getProperty("user.home"), ".jdks"),
        File("/opt/homebrew/opt/openjdk@21"),
        File("/opt/homebrew/opt/openjdk"),
        File("/usr/local/opt/openjdk"),
    )
    return searchRoots.firstNotNullOfOrNull { jdkHomeIn(it, ::hasJniHeaders) }
}

/** A JDK with jpackage, for the release app. */
fun Project.discoverPackagingJdkHome(): String? {
    // Explicit settings first, then the JDK 21 toolchain the code compiles with, then JAVA_HOME.
    // Gradle's own JVM comes last: gradle-daemon-jvm.properties runs Gradle on JDK 25, and packaging
    // with that jlink broke the Linux release build.
    val candidates = listOfNotNull(
        findProperty("desktop.jdk.home")?.toString(),
        rootProject.findProperty("desktop.jdk.home")?.toString(),
        rootProject.findProperty("org.gradle.java.home")?.toString(),
        readLocalProperty("desktop.jdk.home"),
        System.getenv("DESKTOP_JDK_HOME"),
        toolchainHome(PACKAGING_JDK_VERSION),
        System.getenv("JAVA_HOME"),
        System.getProperty("java.home"),
    ).cleaned()

    candidates.firstOrNull { hasJpackage(it) && jdkMajorVersion(it) == PACKAGING_JDK_VERSION }?.let { return it }
    candidates.firstOrNull(::hasJpackage)?.let { return it }

    val searchRoots = buildList {
        System.getenv("ProgramFiles")?.let { add(File(it)) }
        System.getenv("ProgramFiles(x86)")?.let { add(File(it)) }
        add(File(System.getProperty("user.home"), "AppData/Local/Programs/Eclipse Adoptium"))
        add(File(System.getProperty("user.home"), ".jdks"))
        add(File(System.getProperty("user.home"), "scoop/apps/temurin21-jdk/current"))
        add(File("/Library/Java/JavaVirtualMachines"))
        add(File(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines"))
        add(File("/opt/homebrew/opt/openjdk@21"))
        add(File("/opt/homebrew/opt/openjdk"))
        add(File("/usr/local/opt/openjdk"))
        add(File("/usr/lib/jvm"))
    }

    val vendorDirs = listOf("Java", "Eclipse Adoptium", "Microsoft", "BellSoft", "Zulu")

    for (root in searchRoots) {
        if (!root.isDirectory) continue

        jdkHomeIn(root, ::hasJpackage)?.let { return it }

        for (vendor in vendorDirs) {
            val vendorDir = if (root.name == vendor) root else File(root, vendor)
            if (!vendorDir.isDirectory) continue
            jdkHomeIn(vendorDir, ::hasJpackage)?.let { return it }
        }
    }

    return null
}

private fun List<String>.cleaned(): List<String> =
    filter { it.isNotBlank() }.map { it.trim().trim('"') }.distinct()
