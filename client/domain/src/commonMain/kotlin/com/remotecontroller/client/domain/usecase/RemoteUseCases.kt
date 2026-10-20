package com.remotecontroller.client.domain.usecase

import com.remotecontroller.client.domain.model.ConnectionState
import com.remotecontroller.client.domain.model.PcConnection
import com.remotecontroller.client.domain.repository.RemoteRepository
import com.remotecontroller.shared.model.PcHost
import com.remotecontroller.shared.model.RemoteCommand
import com.remotecontroller.shared.model.ServerPort
import com.remotecontroller.shared.model.VolumePercent
import kotlinx.coroutines.flow.StateFlow

class ConnectToPcUseCase(
    private val repository: RemoteRepository,
) {
    suspend operator fun invoke(host: String, port: Int, hint: String): Result<Unit> = runCatching {
        repository.connect(
            PcConnection(
                host = PcHost(host.trim()),
                port = ServerPort(port),
                hint = hint.trim(),
            ),
        ).getOrThrow()
    }
}

class DisconnectFromPcUseCase(
    private val repository: RemoteRepository,
) {
    suspend operator fun invoke() = repository.disconnect()
}

class RetryConnectionUseCase(
    private val repository: RemoteRepository,
) {
    suspend operator fun invoke(): Result<Unit> = repository.retryNow()
}

class ObserveConnectionStateUseCase(
    private val repository: RemoteRepository,
) {
    operator fun invoke(): StateFlow<ConnectionState> = repository.connectionState
}

class ObserveServerStatusUseCase(
    private val repository: RemoteRepository,
) {
    operator fun invoke() = repository.serverStatus
}

class ObserveActiveConnectionUseCase(
    private val repository: RemoteRepository,
) {
    operator fun invoke() = repository.activeConnection
}

class SendRemoteCommandUseCase(
    private val repository: RemoteRepository,
) {
    suspend operator fun invoke(command: RemoteCommand): Result<Unit> =
        repository.sendCommand(command)
}

class SendVolumeCommandUseCase(
    private val sendRemoteCommand: SendRemoteCommandUseCase,
) {
    suspend fun setVolume(level: Int): Result<Unit> =
        sendRemoteCommand(RemoteCommand.SetVolume(VolumePercent(level).coerce().value))

    suspend fun volumeUp(step: Int = 5): Result<Unit> =
        sendRemoteCommand(RemoteCommand.VolumeUp(step))

    suspend fun volumeDown(step: Int = 5): Result<Unit> =
        sendRemoteCommand(RemoteCommand.VolumeDown(step))

    suspend fun toggleMute(): Result<Unit> =
        sendRemoteCommand(RemoteCommand.MuteToggle)
}

class SendKeyCommandUseCase(
    private val sendRemoteCommand: SendRemoteCommandUseCase,
) {
    suspend fun pressKey(key: String): Result<Unit> =
        sendRemoteCommand(RemoteCommand.KeyPress(key))

    suspend fun pressCombo(keys: List<String>): Result<Unit> =
        sendRemoteCommand(RemoteCommand.KeyCombo(keys))
}

class SendMouseCommandUseCase(
    private val sendRemoteCommand: SendRemoteCommandUseCase,
) {
    suspend fun move(dx: Int, dy: Int): Result<Unit> =
        sendRemoteCommand(RemoteCommand.MouseMove(dx, dy))

    suspend fun click(button: String): Result<Unit> =
        sendRemoteCommand(RemoteCommand.MouseClick(button))

    suspend fun scroll(deltaY: Int): Result<Unit> =
        sendRemoteCommand(RemoteCommand.MouseScroll(deltaY))
}
