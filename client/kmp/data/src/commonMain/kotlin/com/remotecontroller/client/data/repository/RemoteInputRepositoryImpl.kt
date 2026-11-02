package com.remotecontroller.client.data.repository

import com.remotecontroller.client.domain.model.RemoteInput
import com.remotecontroller.client.domain.repository.RemoteInputRepository
import com.remotecontroller.client.domain.repository.RemoteRepository
import com.remotecontroller.shared.model.RemoteCommand
import com.remotecontroller.shared.model.RemoteKeys

/** The phones send input as commands, over the link they already hold open. */
class RemoteInputRepositoryImpl(
    private val remote: RemoteRepository,
) : RemoteInputRepository {
    override suspend fun send(input: RemoteInput) {
        remote.sendCommand(
            when (input) {
                is RemoteInput.PointerDown -> RemoteCommand.Pointer(input.x, input.y, RemoteKeys.POINTER_DOWN, input.button)
                is RemoteInput.PointerMove -> RemoteCommand.Pointer(input.x, input.y, RemoteKeys.POINTER_MOVE, input.button)
                is RemoteInput.PointerUp -> RemoteCommand.Pointer(input.x, input.y, RemoteKeys.POINTER_UP, input.button)
                is RemoteInput.Scroll -> RemoteCommand.PointerScroll(input.x, input.y, input.deltaY)
                is RemoteInput.Type -> RemoteCommand.TypeText(input.text, input.backspaces)
                RemoteInput.Enter -> RemoteCommand.KeyPress(ENTER_KEY)
                is RemoteInput.Key -> RemoteCommand.KeyState(input.code, input.down)
            },
        )
    }

    private companion object {
        const val ENTER_KEY = "ENTER"
    }
}
