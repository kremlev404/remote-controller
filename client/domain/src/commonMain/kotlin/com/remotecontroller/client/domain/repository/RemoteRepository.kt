package com.remotecontroller.client.domain.repository

import com.remotecontroller.client.domain.model.ConnectionState
import com.remotecontroller.client.domain.model.PcConnection
import com.remotecontroller.shared.model.RemoteCommand
import com.remotecontroller.shared.model.ServerStatus
import kotlinx.coroutines.flow.StateFlow

interface RemoteRepository {
    val connectionState: StateFlow<ConnectionState>
    val serverStatus: StateFlow<ServerStatus?>
    val activeConnection: StateFlow<PcConnection?>

    suspend fun connect(connection: PcConnection): Result<Unit>
    suspend fun retryNow(): Result<Unit>
    suspend fun disconnect()
    suspend fun sendCommand(command: RemoteCommand): Result<Unit>
}
