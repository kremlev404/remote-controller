package com.remotecontroller.client.domain.model

import com.remotecontroller.shared.model.PcHost
import com.remotecontroller.shared.model.ServerPort

data class PcConnection(
    val host: PcHost,
    val port: ServerPort,
    val hint: String = "",
    val pcName: String? = null,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Reconnecting : ConnectionState
    data object Connected : ConnectionState
    data object Failed : ConnectionState
}
