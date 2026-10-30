package com.remotecontroller.desktop.server

interface IncomingFirewall {
    /**
     * Allows inbound TCP on [port] when the OS needs an explicit rule.
     * Returns a warning for the desktop window, or null when nothing else is required.
     */
    fun allowIncomingTcp(port: Int): String?

    /**
     * Looks at the current rules without trying to add one.
     * Null means the phone on the private network can already connect.
     */
    fun accessWarning(port: Int): String? = null

    /**
     * Asks the OS again, including an elevation prompt when one is required.
     * Returns the warning that should stay on screen, or null once access is granted.
     */
    fun requestAccess(port: Int): String? = allowIncomingTcp(port)
}
