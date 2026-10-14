package com.remotecontroller.client.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.remotecontroller.client.domain.model.SavedConnection
import com.remotecontroller.shared.model.ServerPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ConnectionPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) {
    val savedServers: Flow<List<SavedConnection>> =
        dataStore.data.map { preferences -> readServers(preferences) }

    val savedConnection: Flow<SavedConnection?> =
        savedServers.map { servers -> servers.firstOrNull() }

    suspend fun save(connection: SavedConnection) {
        dataStore.edit { preferences ->
            val updated = readServers(preferences)
                .filterNot { it.host == connection.host && it.port == connection.port }
                .toMutableList()
            updated.add(0, connection)

            preferences[SERVERS_KEY] = SavedConnectionCodec.encode(updated.take(MAX_SAVED_SERVERS))
            preferences[HOST_KEY] = connection.host
            preferences[PORT_KEY] = connection.port
        }
    }

    private fun readServers(preferences: Preferences): List<SavedConnection> {
        val fromList = SavedConnectionCodec.decode(preferences[SERVERS_KEY])
        if (fromList.isNotEmpty()) return fromList

        val host = preferences[HOST_KEY]?.trim().orEmpty()
        if (host.isBlank()) return emptyList()

        return listOf(
            SavedConnection(
                host = host,
                port = preferences[PORT_KEY] ?: ServerPort.DEFAULT.value,
            ),
        )
    }

    private companion object {
        const val MAX_SAVED_SERVERS = 8

        val SERVERS_KEY = stringPreferencesKey("saved_servers")
        val HOST_KEY = stringPreferencesKey("pc_host")
        val PORT_KEY = intPreferencesKey("pc_port")
    }
}
