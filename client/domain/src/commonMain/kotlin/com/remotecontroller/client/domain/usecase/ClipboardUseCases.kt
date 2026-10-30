package com.remotecontroller.client.domain.usecase

import com.remotecontroller.client.domain.model.PcConnection
import com.remotecontroller.client.domain.repository.ClipSnapshot
import com.remotecontroller.client.domain.repository.ClipboardRepository
import kotlinx.coroutines.flow.Flow

class ObserveClipboardUseCase(
    private val repository: ClipboardRepository,
) {
    operator fun invoke(connection: PcConnection): Flow<ClipSnapshot> = repository.observe(connection)
}

class SendClipboardUseCase(
    private val repository: ClipboardRepository,
) {
    suspend fun send(text: String) = repository.send(text)

    suspend fun clear() = repository.clear()
}
