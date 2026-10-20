package com.remotecontroller.client.domain.usecase

import com.remotecontroller.client.domain.model.PcConnection
import com.remotecontroller.client.domain.repository.ScreenRepository
import com.remotecontroller.shared.model.RemoteCommand
import com.remotecontroller.shared.model.RemoteKeys
import com.remotecontroller.shared.network.ScreenFrame
import kotlinx.coroutines.flow.Flow

class ObserveScreenUseCase(
    private val repository: ScreenRepository,
) {
    operator fun invoke(connection: PcConnection): Flow<ScreenFrame.Picture> =
        repository.watch(connection)
}

class SendPointerCommandUseCase(
    private val sendRemoteCommand: SendRemoteCommandUseCase,
) {
    suspend fun down(x: Float, y: Float, button: String): Result<Unit> =
        send(x, y, RemoteKeys.POINTER_DOWN, button)

    suspend fun move(x: Float, y: Float, button: String): Result<Unit> =
        send(x, y, RemoteKeys.POINTER_MOVE, button)

    suspend fun up(x: Float, y: Float, button: String): Result<Unit> =
        send(x, y, RemoteKeys.POINTER_UP, button)

    suspend fun scroll(x: Float, y: Float, deltaY: Int): Result<Unit> =
        sendRemoteCommand(
            RemoteCommand.PointerScroll(
                x = x,
                y = y,
                deltaY = deltaY.coerceIn(-20, 20),
            ),
        )

    private suspend fun send(x: Float, y: Float, phase: String, button: String): Result<Unit> =
        sendRemoteCommand(RemoteCommand.Pointer(x = x, y = y, phase = phase, button = button))
}
