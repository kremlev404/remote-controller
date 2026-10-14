package com.remotecontroller.desktop.platform

enum class DesktopOs {
    Windows,
    Mac,
    Other,
    ;

    companion object {
        private val osName = System.getProperty("os.name").orEmpty().lowercase()
        private val osArch = System.getProperty("os.arch").orEmpty().lowercase()
        private val APPLE_SILICON_ARCHS = setOf("aarch64", "arm64")

        val current: DesktopOs = when {
            osName.contains("win") -> Windows
            osName.contains("mac") -> Mac
            else -> Other
        }

        val isAppleSilicon: Boolean
            get() = current == Mac && osArch in APPLE_SILICON_ARCHS
    }
}
