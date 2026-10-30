package com.remotecontroller.desktop.startup

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

class WindowsStartupManager : StartupManager {
    override val title: String = "Start with Windows"
    override val enabledDescription: String = "Launch the server automatically when you sign in"
    override val unavailableDescription: String = "Available in the installed .exe app only"

    private val executablePath: String? = resolveExecutablePath()

    override fun canConfigure(): Boolean = executablePath != null

    override fun isEnabled(): Boolean {
        return executablePath != null && runCatching {
            Advapi32Util.registryValueExists(
                WinReg.HKEY_CURRENT_USER,
                RUN_KEY,
                VALUE_NAME,
            )
        }.getOrDefault(false)
    }

    override fun setEnabled(enabled: Boolean) {
        val path = executablePath
            ?: error("Startup can only be configured for the packaged .exe application")

        if (enabled) {
            Advapi32Util.registrySetStringValue(
                WinReg.HKEY_CURRENT_USER,
                RUN_KEY,
                VALUE_NAME,
                "\"$path\"",
            )
        } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)) {
            Advapi32Util.registryDeleteValue(
                WinReg.HKEY_CURRENT_USER,
                RUN_KEY,
                VALUE_NAME,
            )
        }
    }

    private fun resolveExecutablePath(): String? {
        val command = ProcessHandle.current().info().command().orElse(null)?.trim().orEmpty()
        if (command.endsWith(".exe", ignoreCase = true)) {
            return command
        }
        return null
    }

    private companion object {
        const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val VALUE_NAME = "RemoteControllerServer"
    }
}
