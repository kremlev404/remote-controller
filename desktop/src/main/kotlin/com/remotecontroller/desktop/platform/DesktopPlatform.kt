package com.remotecontroller.desktop.platform

import com.remotecontroller.desktop.executor.KeyboardSimulator
import com.remotecontroller.desktop.executor.MacMediaKeys
import com.remotecontroller.desktop.executor.MacVolumeController
import com.remotecontroller.desktop.executor.MediaKeys
import com.remotecontroller.desktop.executor.VolumeController
import com.remotecontroller.desktop.executor.WindowsMediaKeys
import com.remotecontroller.desktop.executor.WindowsVolumeController
import com.remotecontroller.desktop.server.IncomingFirewall
import com.remotecontroller.desktop.server.MacFirewall
import com.remotecontroller.desktop.server.ServerKeepAlive
import com.remotecontroller.desktop.server.WindowsFirewall
import com.remotecontroller.desktop.startup.MacStartupManager
import com.remotecontroller.desktop.startup.StartupManager
import com.remotecontroller.desktop.startup.WindowsStartupManager
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * One place that picks Windows or macOS implementations.
 * The server, UI, and DI stay free of Win32 and CoreAudio calls.
 */
object DesktopPlatform {
    val os: DesktopOs = DesktopOs.current

    fun volumeController(): VolumeController = when (os) {
        DesktopOs.Windows -> WindowsVolumeController()
        DesktopOs.Mac, DesktopOs.Other -> MacVolumeController()
    }

    fun startupManager(): StartupManager = when (os) {
        DesktopOs.Windows -> WindowsStartupManager()
        DesktopOs.Mac, DesktopOs.Other -> MacStartupManager()
    }

    fun serverKeepAlive(): ServerKeepAlive = when (os) {
        DesktopOs.Windows -> WindowsServerKeepAlive()
        DesktopOs.Mac, DesktopOs.Other -> IdleServerKeepAlive()
    }

    fun firewall(): IncomingFirewall = when (os) {
        DesktopOs.Windows -> WindowsFirewall
        DesktopOs.Mac, DesktopOs.Other -> MacFirewall
    }

    fun mediaKeys(): MediaKeys = when (os) {
        DesktopOs.Windows -> WindowsMediaKeys
        DesktopOs.Mac, DesktopOs.Other -> MacMediaKeys
    }

    fun keyboardSimulator(): KeyboardSimulator = KeyboardSimulator(mediaKeys())

    fun machineName(): String = when (os) {
        DesktopOs.Windows -> getenv("COMPUTERNAME") ?: "Windows PC"
        DesktopOs.Mac -> macComputerName()
        DesktopOs.Other -> localHostName() ?: "Computer"
    }

    private fun macComputerName(): String =
        readProcess("scutil", "--get", "ComputerName")
            ?: localHostName()
            ?: "Mac"

    private fun localHostName(): String? =
        runCatching { InetAddress.getLocalHost().hostName.substringBefore('.') }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && !it.equals("localhost", ignoreCase = true) }

    private fun getenv(name: String): String? =
        System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

    private fun readProcess(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(command.toList())
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        process.inputStream.bufferedReader().readText().trim().ifBlank { null }
    }.getOrNull()
}
