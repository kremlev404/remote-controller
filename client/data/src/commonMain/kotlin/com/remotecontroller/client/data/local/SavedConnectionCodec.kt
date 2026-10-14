package com.remotecontroller.client.data.local

import com.remotecontroller.client.domain.model.SavedConnection
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class SavedConnectionEntry(
    val host: String,
    val port: Int,
)

internal object SavedConnectionCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(servers: List<SavedConnection>): String =
        json.encodeToString(servers.map { SavedConnectionEntry(it.host, it.port) })

    fun decode(raw: String?): List<SavedConnection> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<SavedConnectionEntry>>(raw)
                .mapNotNull { SavedConnection.from(it.host, it.port) }
        }.getOrDefault(emptyList())
    }
}
