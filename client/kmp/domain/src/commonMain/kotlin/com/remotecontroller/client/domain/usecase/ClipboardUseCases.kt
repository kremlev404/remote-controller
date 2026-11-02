package com.remotecontroller.client.domain.usecase

import com.remotecontroller.client.domain.model.PcConnection
import com.remotecontroller.client.domain.repository.ClipSnapshot
import com.remotecontroller.client.domain.repository.ClipboardRepository
import com.remotecontroller.shared.model.ClipNote
import kotlinx.coroutines.flow.Flow

class ObserveClipboardUseCase(
    private val repository: ClipboardRepository,
) {
    operator fun invoke(connection: PcConnection): Flow<ClipSnapshot> = repository.observe(connection)
}

class SendClipboardUseCase(
    private val repository: ClipboardRepository,
) {
    /** False when the text is too long to send. Nothing at all is not sent, and that is fine. */
    suspend fun send(text: String, image: String? = null, imageType: String? = null): Boolean {
        val trimmed = text.trim()
        if (trimmed.length > ClipNote.MAX_TEXT) return false
        if (trimmed.isNotEmpty() || !image.isNullOrEmpty()) repository.send(trimmed, image, imageType)
        return true
    }

    suspend fun clear() = repository.clear()
}
