package com.remotecontroller.desktop.server

/**
 * macOS Application Firewall asks the user the first time a bundled app listens.
 * There is no netsh-style rule to add here. The Mac ticket can call `socketfilterfw`
 * for the packaged .app if that prompt is not enough.
 */
internal object MacFirewall : IncomingFirewall {
    override fun allowIncomingTcp(port: Int): String? = null
}
