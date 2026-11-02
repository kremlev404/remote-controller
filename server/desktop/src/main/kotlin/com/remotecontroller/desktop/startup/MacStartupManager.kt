package com.remotecontroller.desktop.startup

/**
 * Login-item registration for the packaged Mac app is not implemented yet.
 * The toggle stays off so the Windows registry path is never touched on macOS.
 */
class MacStartupManager : StartupManager {
    override val title: String = "Start at login"
    override val enabledDescription: String = "Launch the server automatically when you sign in"
    override val unavailableDescription: String = "Launch at login is not set up for the Mac app yet"

    override fun canConfigure(): Boolean = false

    override fun isEnabled(): Boolean = false

    override fun setEnabled(enabled: Boolean) {
        error("Launch at login is not set up for the Mac app yet")
    }
}
