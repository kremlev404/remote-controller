package com.remotecontroller.client.domain.usecase

import com.remotecontroller.client.domain.model.NetworkMonitor
import com.remotecontroller.client.domain.model.NetworkStatus
import kotlinx.coroutines.flow.StateFlow

class ObserveNetworkStatusUseCase(
    private val networkMonitor: NetworkMonitor,
) {
    operator fun invoke(): StateFlow<NetworkStatus> = networkMonitor.status
}
