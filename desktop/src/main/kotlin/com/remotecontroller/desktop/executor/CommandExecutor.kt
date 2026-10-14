package com.remotecontroller.desktop.executor

import com.remotecontroller.shared.model.RemoteCommand
import com.remotecontroller.shared.model.ServerStatus
import com.remotecontroller.shared.model.describe

class CommandExecutor(
    private val volumeController: VolumeController,
    private val keyboardSimulator: KeyboardSimulator,
    private val mouseSimulator: MouseSimulator,
) {

    fun execute(command: RemoteCommand): ServerStatus {
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
        }

        return currentStatus(lastCommand = command.describe())
    }

    fun currentStatus(lastCommand: String? = null): ServerStatus =
        ServerStatus(
            connectedClients = 0,
            volumeLevel = volumeController.getVolumeLevel(),
            isMuted = volumeController.isMuted(),
            lastCommand = lastCommand,
        )
}
