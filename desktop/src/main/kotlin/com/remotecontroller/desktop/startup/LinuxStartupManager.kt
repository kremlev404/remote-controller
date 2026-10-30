package com.remotecontroller.desktop.startup

import java.io.File

/**
 * XDG autostart: a .desktop file in ~/.config/autostart. GNOME, KDE, Cinnamon, Xfce and MATE
 * all read it. Only the app's own launcher can be registered, not `gradlew run`.
 */
class LinuxStartupManager : StartupManager {
    override val title: String = "Start at login"
    override val enabledDescription: String = "Launch the server automatically when you sign in"
    override val unavailableDescription: String = "Available when started from the unpacked app, not from Gradle"

    private val launcher: String? = ProcessHandle.current().info().command().orElse(null)
        ?.takeUnless { it.endsWith("/java") }

    private val entry: File by lazy {
        val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            ?: "${System.getProperty("user.home")}/.config"
        File(config, "autostart/remote-controller-server.desktop")
    }

    override fun canConfigure(): Boolean = launcher != null

    override fun isEnabled(): Boolean = launcher != null && entry.isFile

    override fun setEnabled(enabled: Boolean) {
        val path = launcher ?: error("Start at login needs the app's own launcher")
        if (!enabled) {
            entry.delete()
            return
        }
        entry.parentFile.mkdirs()
        entry.writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=Remote Controller Server
            Exec="${path.replace("\"", "\\\"")}"
            X-GNOME-Autostart-enabled=true
            NoDisplay=true
            Terminal=false
            """.trimIndent() + "\n",
        )
    }
}
