package com.remotecontroller.desktop.server

/**
 * Keeps the machine from suspending the server process while the session is locked
 * or the desktop window is hidden.
 */
interface ServerKeepAlive {
    fun start()
    fun stop()
}
