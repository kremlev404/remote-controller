package com.remotecontroller.desktop.server

enum class KtorServerState {
    Starting,
    Running,
    Restarting,
    Recovering,
    Stopped,
    Error,
}

enum class NetworkState {
    Available,
    Unavailable,
}

data class ConnectedClient(
    val id: Long,
    val name: String,
)

data class ServerRuntimeStatus(
    val ktorState: KtorServerState = KtorServerState.Starting,
    val networkState: NetworkState = NetworkState.Unavailable,
    val connectedClients: Int = 0,
    val clients: List<ConnectedClient> = emptyList(),
    val localAddresses: List<String> = emptyList(),
    val recommendedAddress: String? = null,
    val lastError: String? = null,
    val warning: String? = null,
    val restartCount: Int = 0,
    val port: Int,
) {
    val clientNames: List<String>
        get() = clients.map { it.name }

    val statusMessage: String
        get() = when (ktorState) {
            KtorServerState.Starting -> "Starting Ktor server…"
            KtorServerState.Restarting -> "Restarting server…"
            KtorServerState.Running -> when {
                networkState == NetworkState.Unavailable -> "Server running, no network adapter"
                clientNames.isNotEmpty() -> "Running · ${clientNames.joinToString(", ")}"
                connectedClients > 0 -> "Running · $connectedClients client(s) connected"
                else -> "Running · waiting for device"
            }
            KtorServerState.Recovering -> "Recovering after connection issue…"
            KtorServerState.Stopped -> "Server stopped"
            KtorServerState.Error -> lastError ?: "Server error"
        }

    val networkMessage: String
        get() = when (networkState) {
            NetworkState.Available -> recommendedAddress?.let { "Network OK · $it" }
                ?: localAddresses.firstOrNull()?.let { "Network OK · $it" }
                ?: "Network OK"
            NetworkState.Unavailable -> "No Wi‑Fi / LAN address detected"
        }

    val isHealthy: Boolean
        get() = ktorState == KtorServerState.Running && networkState == NetworkState.Available
}
