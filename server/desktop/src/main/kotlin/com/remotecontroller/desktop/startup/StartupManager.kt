package com.remotecontroller.desktop.startup

interface StartupManager {
    val title: String
    val enabledDescription: String
    val unavailableDescription: String
    fun canConfigure(): Boolean
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
}
