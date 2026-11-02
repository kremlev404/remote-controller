package com.remotecontroller.client.storage.local

import com.remotecontroller.client.domain.model.SavedConnection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SavedConnectionEntry(
    val host: String,
    val port: Int,
    val hint: String = "",
)

internal object SavedConnectionCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String?): List<SavedConnection> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<SavedConnectionEntry>>(raw)
                .mapNotNull { SavedConnection.from(it.host, it.port, it.hint) }
        }.getOrDefault(emptyList())
    }
}
