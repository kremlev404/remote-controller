package com.remotecontroller.client.feature.connect.impl.state

import androidx.compose.runtime.Immutable
import com.remotecontroller.client.domain.model.ConnectionState
import com.remotecontroller.client.domain.model.SavedConnection
import com.remotecontroller.shared.model.ServerPort

@Immutable
data class ConnectUiState(
    val hint: String = "",
    val host: String = "",
    val port: String = ServerPort.DEFAULT.value.toString(),
    val accessCode: String = "",
    val certFingerprint: String = "",
    val checkCertificate: Boolean = false,
    val savedServers: List<SavedConnection> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val isLoading: Boolean = false,
    val pendingStartupNavigation: Boolean = false,
    val errorMessage: String? = null,
    val needsLocalNetworkPermission: Boolean = false,
    /** Shown while the PC owner decides; both screens show the same digits. */
    val approvalCode: String? = null,
) {
    val isConnected: Boolean get() = connectionState == ConnectionState.Connected
}
