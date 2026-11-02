package com.remotecontroller.client.domain.model

import kotlinx.coroutines.flow.StateFlow

interface NetworkMonitor {
    val status: StateFlow<NetworkStatus>
}
