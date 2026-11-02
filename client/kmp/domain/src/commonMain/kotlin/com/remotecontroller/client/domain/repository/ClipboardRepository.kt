package com.remotecontroller.client.domain.repository

import com.remotecontroller.client.domain.model.PcConnection
import com.remotecontroller.shared.model.ClipEvent
import com.remotecontroller.shared.model.ClipNote
import kotlinx.coroutines.flow.Flow

data class ClipSnapshot(
    val notes: List<ClipNote> = emptyList(),
    val connected: Boolean = false,
    val banner: String? = null,
) {
    /** The same conversation after [event], as the PC reports it. */
    fun after(event: ClipEvent): ClipSnapshot = when (event) {
        is ClipEvent.History -> copy(notes = event.messages, banner = null)
        is ClipEvent.Note -> copy(notes = notes + event.message, banner = null)
        is ClipEvent.Cleared -> copy(notes = emptyList())
        is ClipEvent.Rejected -> copy(banner = event.message)
    }
}

interface ClipboardRepository {
    fun observe(connection: PcConnection): Flow<ClipSnapshot>

    suspend fun send(text: String, image: String? = null, imageType: String? = null)

    suspend fun clear()
}
