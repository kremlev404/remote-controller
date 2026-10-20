package com.remotecontroller.client.feature.remote.impl.state

import androidx.compose.runtime.Immutable
import com.remotecontroller.client.domain.model.ConnectionState
import com.remotecontroller.client.domain.model.NetworkStatus

@Immutable
data class RemoteScreenUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val networkStatus: NetworkStatus = NetworkStatus(),
    val serverHint: String = "",
    val serverEndpoint: String? = null,
    val volumeLevel: Int = 50,
    val isMuted: Boolean = false,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val pointerScale: Float = 2f,
)
