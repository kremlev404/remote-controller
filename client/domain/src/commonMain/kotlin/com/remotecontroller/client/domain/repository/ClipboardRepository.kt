package com.remotecontroller.client.domain.repository

import com.remotecontroller.client.domain.model.PcConnection
import com.remotecontroller.shared.model.ClipNote
import kotlinx.coroutines.flow.Flow

data class ClipSnapshot(
    val notes: List<ClipNote> = emptyList(),
    val connected: Boolean = false,
    val banner: String? = null,
)

interface ClipboardRepository {
    fun observe(connection: PcConnection): Flow<ClipSnapshot>

    suspend fun send(text: String)

    suspend fun clear()
}
