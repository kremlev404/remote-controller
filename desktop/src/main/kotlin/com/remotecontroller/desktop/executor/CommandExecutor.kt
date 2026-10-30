package com.remotecontroller.desktop.executor

import com.remotecontroller.shared.model.RemoteCommand
import com.remotecontroller.shared.model.ServerStatus
import com.remotecontroller.shared.model.describe

class CommandExecutor(
    private val volumeController: VolumeController,
    private val keyboardSimulator: KeyboardSimulator,
    private val mouseSimulator: MouseSimulator,
    private val screenCapturer: ScreenCapturer,
) {

    suspend fun execute(command: RemoteCommand): ServerStatus {
        when (command) {
            is RemoteCommand.SetVolume -> volumeController.setVolumeLevel(command.value)
            is RemoteCommand.VolumeUp -> volumeController.volumeUp(command.step)
            is RemoteCommand.VolumeDown -> volumeController.volumeDown(command.step)
            is RemoteCommand.MuteToggle -> volumeController.toggleMute()
            is RemoteCommand.KeyPress -> keyboardSimulator.pressKey(command.key)
            is RemoteCommand.KeyCombo -> keyboardSimulator.pressCombo(command.keys)
            is RemoteCommand.MouseMove -> mouseSimulator.moveRelative(command.dx, command.dy)
            is RemoteCommand.MouseClick -> mouseSimulator.click(command.button)
            is RemoteCommand.MouseScroll -> mouseSimulator.scroll(command.deltaY)
            is RemoteCommand.Hello -> Unit
            is RemoteCommand.Pointer,
            is RemoteCommand.PointerScroll,
            -> applyPointer(command)
        }

        return currentStatus(lastCommand = command.describe())
    }

    fun applyPointer(command: RemoteCommand): Boolean = when (command) {
        is RemoteCommand.Pointer -> {
            screenCapturer.toScreen(command.x, command.y)?.let { point ->
                mouseSimulator.pointer(point.x, point.y, command.phase, command.button)
            }
            true
        }
        is RemoteCommand.PointerScroll -> {
            screenCapturer.toScreen(command.x, command.y)?.let { point ->
                mouseSimulator.scrollAt(point.x, point.y, command.deltaY)
            }
            true
        }
        else -> false
    }

    fun releasePointer() {
        mouseSimulator.releaseHeld()
    }

    suspend fun currentStatus(lastCommand: String? = null): ServerStatus {
        val volume = volumeController.readState()
        return ServerStatus(
            connectedClients = 0,
            volumeLevel = volume?.level ?: 0,
            isMuted = volume?.muted ?: false,
            lastCommand = lastCommand,
        )
    }

    suspend fun currentStatusIfReadable(lastCommand: String? = null): ServerStatus? {
        val volume = volumeController.readState() ?: return null
        return ServerStatus(
            connectedClients = 0,
            volumeLevel = volume.level,
            isMuted = volume.muted,
            lastCommand = lastCommand,
        )
    }
}
