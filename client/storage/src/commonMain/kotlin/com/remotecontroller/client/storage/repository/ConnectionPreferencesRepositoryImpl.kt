package com.remotecontroller.client.storage.repository

import com.remotecontroller.client.domain.model.SavedConnection
import com.remotecontroller.client.domain.repository.ConnectionPreferencesRepository
import com.remotecontroller.client.storage.local.SavedServerStore
import kotlinx.coroutines.flow.Flow

class ConnectionPreferencesRepositoryImpl(
    private val store: SavedServerStore,
) : ConnectionPreferencesRepository {

    override fun observeSavedConnection(): Flow<SavedConnection?> = store.savedConnection

    override fun observeSavedServers(): Flow<List<SavedConnection>> = store.savedServers

    override suspend fun saveConnection(connection: SavedConnection) {
        store.save(connection)
    }
}
