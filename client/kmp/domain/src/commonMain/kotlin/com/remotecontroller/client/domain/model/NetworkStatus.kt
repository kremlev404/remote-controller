package com.remotecontroller.client.domain.model

data class NetworkStatus(
    val isOnline: Boolean = false,
    val isWifi: Boolean = false,
    val hasLanTransport: Boolean = isWifi,
) {
    val isUsableForLocalPc: Boolean get() = hasLanTransport
}
