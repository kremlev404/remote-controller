package com.remotecontroller.desktop.platform

import com.remotecontroller.desktop.executor.KeyboardSimulator
import com.remotecontroller.desktop.executor.LinuxMediaKeys
import com.remotecontroller.desktop.executor.LinuxTextTyper
import com.remotecontroller.desktop.executor.LinuxVolumeController
import com.remotecontroller.desktop.executor.MacMediaKeys
import com.remotecontroller.desktop.executor.MacTextTyper
import com.remotecontroller.desktop.executor.MacVolumeController
import com.remotecontroller.desktop.executor.MediaKeys
import com.remotecontroller.desktop.executor.TextTyper
import com.remotecontroller.desktop.executor.VolumeController
import com.remotecontroller.desktop.executor.WindowsMediaKeys
import com.remotecontroller.desktop.executor.WindowsTextTyper
import com.remotecontroller.desktop.executor.WindowsVolumeController
import com.remotecontroller.desktop.server.IncomingFirewall
import com.remotecontroller.desktop.server.LinuxFirewall
import com.remotecontroller.desktop.server.MacFirewall
import com.remotecontroller.desktop.server.ServerKeepAlive
import com.remotecontroller.desktop.server.WindowsFirewall
import com.remotecontroller.desktop.startup.LinuxStartupManager
import com.remotecontroller.desktop.startup.MacStartupManager
import com.remotecontroller.desktop.startup.StartupManager
import com.remotecontroller.desktop.startup.WindowsStartupManager
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * One place that picks Windows, macOS or Linux implementations.
 * The server, UI, and DI stay free of Win32, CoreAudio and PipeWire calls.
 */
object DesktopPlatform {
    val os: DesktopOs = DesktopOs.current

    fun volumeController(): VolumeController = when (os) {
        DesktopOs.Windows -> WindowsVolumeController()
        DesktopOs.Linux -> LinuxVolumeController()
        DesktopOs.Mac, DesktopOs.Other -> MacVolumeController()
    }

    fun startupManager(): StartupManager = when (os) {
        DesktopOs.Windows -> WindowsStartupManager()
        DesktopOs.Linux -> LinuxStartupManager()
        DesktopOs.Mac, DesktopOs.Other -> MacStartupManager()
    }

    fun serverKeepAlive(): ServerKeepAlive = when (os) {
        DesktopOs.Windows -> WindowsServerKeepAlive()
        DesktopOs.Mac, DesktopOs.Linux, DesktopOs.Other -> IdleServerKeepAlive()
    }

    fun firewall(): IncomingFirewall = when (os) {
        DesktopOs.Windows -> WindowsFirewall
        DesktopOs.Linux -> LinuxFirewall
        DesktopOs.Mac, DesktopOs.Other -> MacFirewall
    }

    fun mediaKeys(volume: VolumeController): MediaKeys = when (os) {
        DesktopOs.Windows -> WindowsMediaKeys
        DesktopOs.Linux -> LinuxMediaKeys(volume)
        DesktopOs.Mac, DesktopOs.Other -> MacMediaKeys
    }

    fun textTyper(): TextTyper = when (os) {
        DesktopOs.Windows -> WindowsTextTyper
        DesktopOs.Linux -> LinuxTextTyper
        DesktopOs.Mac, DesktopOs.Other -> MacTextTyper
    }

    fun keyboardSimulator(volume: VolumeController): KeyboardSimulator =
        KeyboardSimulator(mediaKeys(volume), textTyper())

    fun machineName(): String = when (os) {
        DesktopOs.Windows -> getenv("COMPUTERNAME") ?: "Windows PC"
        DesktopOs.Mac -> macComputerName()
        DesktopOs.Linux -> readProcess("hostnamectl", "--pretty") ?: localHostName() ?: "Linux PC"
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
