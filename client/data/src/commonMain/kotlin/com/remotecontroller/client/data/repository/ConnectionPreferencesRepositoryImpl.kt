package com.remotecontroller.client.data.repository

import com.remotecontroller.client.data.local.ConnectionPreferencesStore
import com.remotecontroller.client.domain.model.SavedConnection
import com.remotecontroller.client.domain.repository.ConnectionPreferencesRepository
import kotlinx.coroutines.flow.Flow

class ConnectionPreferencesRepositoryImpl(
    private val store: ConnectionPreferencesStore,
) : ConnectionPreferencesRepository {

    override fun observeSavedConnection(): Flow<SavedConnection?> = store.savedConnection

    override fun observeSavedServers(): Flow<List<SavedConnection>> = store.savedServers

    override suspend fun saveConnection(connection: SavedConnection) {
        store.save(connection)
    }
}
