package com.remotecontroller.client.domain.usecase

import com.remotecontroller.client.domain.model.SavedConnection
import com.remotecontroller.client.domain.repository.ConnectionPreferencesRepository
import kotlinx.coroutines.flow.Flow

class ObserveSavedConnectionUseCase(
    private val repository: ConnectionPreferencesRepository,
) {
    operator fun invoke(): Flow<SavedConnection?> = repository.observeSavedConnection()
}

class ObserveSavedServersUseCase(
    private val repository: ConnectionPreferencesRepository,
) {
    operator fun invoke(): Flow<List<SavedConnection>> = repository.observeSavedServers()
}

class SaveConnectionUseCase(
    private val repository: ConnectionPreferencesRepository,
) {
    suspend operator fun invoke(host: String, port: Int, hint: String) {
        SavedConnection.from(host, port, hint)?.let { repository.saveConnection(it) }
    }
}
