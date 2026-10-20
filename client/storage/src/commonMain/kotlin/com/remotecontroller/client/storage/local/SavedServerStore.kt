package com.remotecontroller.client.storage.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.remotecontroller.client.domain.model.SavedConnection
import com.remotecontroller.shared.model.ServerPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock

class SavedServerStore(
    private val dao: SavedServerDao,
    private val dataStore: DataStore<Preferences>,
) {
    private val ready = CompletableDeferred<Unit>()

    val savedServers: Flow<List<SavedConnection>> = flow {
        ready.await()
        emitAll(
            dao.observeAll().map { rows ->
                rows.map { SavedConnection(host = it.host, port = it.port, hint = it.hint) }
            },
        )
    }

    val savedConnection: Flow<SavedConnection?> = savedServers.map { servers -> servers.firstOrNull() }

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                importLegacy()
            } finally {
                ready.complete(Unit)
            }
        }
    }

    suspend fun save(connection: SavedConnection) {
        ready.await()
        dao.upsert(
            SavedServerEntity(
                host = connection.host,
                port = connection.port,
                hint = connection.hint,
                savedAt = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        dao.trimTo(MAX_SAVED_SERVERS)
    }

    private suspend fun importLegacy() {
        if (dao.count() > 0) return
        val preferences = dataStore.data.first()
        val servers = readLegacy(preferences)
        if (servers.isEmpty()) return
        val newestFirst = Clock.System.now().toEpochMilliseconds()
        servers.forEachIndexed { index, server ->
            dao.upsert(
                SavedServerEntity(
                    host = server.host,
                    port = server.port,
                    hint = server.hint,
                    savedAt = newestFirst - index,
                ),
            )
        }
        dataStore.edit { prefs ->
            prefs.remove(SERVERS_KEY)
            prefs.remove(HOST_KEY)
            prefs.remove(PORT_KEY)
        }
    }

    private fun readLegacy(preferences: Preferences): List<SavedConnection> {
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
