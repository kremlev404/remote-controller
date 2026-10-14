package com.remotecontroller.desktop.startup

interface StartupManager {
    fun canConfigure(): Boolean
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
}
