package com.remotecontroller.client.domain.repository

import com.remotecontroller.client.domain.model.SavedConnection
import kotlinx.coroutines.flow.Flow

interface ConnectionPreferencesRepository {
    fun observeSavedConnection(): Flow<SavedConnection?>

    fun observeSavedServers(): Flow<List<SavedConnection>>

    suspend fun saveConnection(connection: SavedConnection)
}
