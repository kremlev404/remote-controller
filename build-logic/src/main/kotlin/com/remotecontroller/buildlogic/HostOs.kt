package com.remotecontroller.buildlogic

/** The OS this build runs on. [folder] is also Compose's name for its folder in appResources. */
enum class HostOs(val folder: String) {
    WINDOWS("windows"),
    MACOS("macos"),
    LINUX("linux"),
}

val hostOs: HostOs = System.getProperty("os.name").lowercase().let { name ->
    when {
        name.startsWith("win") -> HostOs.WINDOWS
        name.contains("mac") -> HostOs.MACOS
        else -> HostOs.LINUX
    }
}

/** Compose's runtime for this OS and CPU, without its version. */
fun composeDesktopJvmArtifact(): String {
    val arch = when (val name = System.getProperty("os.arch")) {
        "x86_64", "amd64" -> "x64"
        "aarch64" -> "arm64"
        else -> error("Unsupported OS arch: $name")
    }
    return "org.jetbrains.compose.desktop:desktop-jvm-${hostOs.folder}-$arch"
}
