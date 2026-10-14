package com.remotecontroller.desktop.server

interface IncomingFirewall {
    /**
     * Allows inbound TCP on [port] when the OS needs an explicit rule.
     * Returns a warning for the desktop window, or null when nothing else is required.
     */
    fun allowIncomingTcp(port: Int): String?
}
