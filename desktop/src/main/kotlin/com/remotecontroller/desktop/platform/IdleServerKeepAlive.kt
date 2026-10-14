package com.remotecontroller.desktop.platform

import com.remotecontroller.desktop.server.ServerKeepAlive

/** macOS and other hosts do not use the Windows power-request API. */
internal class IdleServerKeepAlive : ServerKeepAlive {
    override fun start() = Unit
    override fun stop() = Unit
}
