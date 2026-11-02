package com.remotecontroller.desktop.ui

import com.remotecontroller.desktop.server.ConnectedClient
import com.remotecontroller.desktop.server.KtorServerState
import com.remotecontroller.desktop.server.ServerRuntimeStatus
import com.remotecontroller.shared.model.RemoteKeys

sealed interface DesktopServerState {
    val port: Int
    val headline: String
    val ktorLabel: String
    val networkLabel: String
    val networkAvailable: Boolean
    val clients: List<ConnectedClient>
    val clientNames: List<String>
        get() = clients.map { it.name }
    val addresses: List<String>
    val recommendedAddress: String?
    val warning: String?
    val error: String?
    val restartInProgress: Boolean

    data class Starting(
        override val port: Int,
    ) : DesktopServerState {
        override val headline: String = "Starting Ktor server…"
        override val ktorLabel: String = "Starting"
        override val networkLabel: String = "Checking…"
        override val networkAvailable: Boolean = false
        override val clients: List<ConnectedClient> = emptyList()
        override val addresses: List<String> = emptyList()
        override val recommendedAddress: String? = null
        override val warning: String? = null
        override val error: String? = null
        override val restartInProgress: Boolean = true
    }

    data class Restarting(
        override val port: Int,
        override val addresses: List<String>,
        override val recommendedAddress: String?,
        override val warning: String?,
    ) : DesktopServerState {
        override val headline: String = "Restarting server…"
        override val ktorLabel: String = "Restarting"
        override val networkLabel: String = networkText(recommendedAddress, addresses)
        override val networkAvailable: Boolean = addresses.isNotEmpty()
        override val clients: List<ConnectedClient> = emptyList()
        override val error: String? = null
        override val restartInProgress: Boolean = true
    }

    data class Running(
        override val port: Int,
        override val addresses: List<String>,
        override val recommendedAddress: String?,
        override val clients: List<ConnectedClient>,
        override val warning: String?,
        val restartCount: Int,
    ) : DesktopServerState {
        override val headline: String = when {
            addresses.isEmpty() -> "Server running, no network adapter"
            clientNames.isNotEmpty() -> "Running · ${clientNames.joinToString(", ")}"
            else -> "Running · waiting for device"
        }
        override val ktorLabel: String = "Running"
        override val networkLabel: String = networkText(recommendedAddress, addresses)
        override val networkAvailable: Boolean = addresses.isNotEmpty()
        override val error: String? = null
        override val restartInProgress: Boolean = false
    }

    data class Recovering(
        override val port: Int,
        override val addresses: List<String>,
        override val recommendedAddress: String?,
        override val warning: String?,
    ) : DesktopServerState {
        override val headline: String = "Recovering after connection issue…"
        override val ktorLabel: String = "Recovering"
        override val networkLabel: String = networkText(recommendedAddress, addresses)
        override val networkAvailable: Boolean = addresses.isNotEmpty()
        override val clients: List<ConnectedClient> = emptyList()
        override val error: String? = null
        override val restartInProgress: Boolean = true
    }

    data class Stopped(
        override val port: Int,
    ) : DesktopServerState {
        override val headline: String = "Server stopped"
        override val ktorLabel: String = "Stopped"
        override val networkLabel: String = "No Wi‑Fi / LAN address detected"
        override val networkAvailable: Boolean = false
        override val clients: List<ConnectedClient> = emptyList()
        override val addresses: List<String> = emptyList()
        override val recommendedAddress: String? = null
        override val warning: String? = null
        override val error: String? = null
        override val restartInProgress: Boolean = false
    }

    data class Failed(
        override val port: Int,
        val message: String,
        override val warning: String?,
    ) : DesktopServerState {
        override val headline: String = message
        override val ktorLabel: String = "Error"
        override val networkLabel: String = "No Wi‑Fi / LAN address detected"
        override val networkAvailable: Boolean = false
        override val clients: List<ConnectedClient> = emptyList()
        override val addresses: List<String> = emptyList()
        override val recommendedAddress: String? = null
        override val error: String? = message
        override val restartInProgress: Boolean = false
    }
}

internal fun ServerRuntimeStatus.toDesktopServerState(): DesktopServerState = when (ktorState) {
    KtorServerState.Starting -> DesktopServerState.Starting(port)
    KtorServerState.Restarting -> DesktopServerState.Restarting(
        port = port,
        addresses = localAddresses,
        recommendedAddress = recommendedAddress,
        warning = warning,
    )
    KtorServerState.Running -> DesktopServerState.Running(
        port = port,
        addresses = localAddresses,
        recommendedAddress = recommendedAddress,
        clients = clients,
        warning = warning,
        restartCount = restartCount,
    )
    KtorServerState.Recovering -> DesktopServerState.Recovering(
        port = port,
        addresses = localAddresses,
        recommendedAddress = recommendedAddress,
        warning = warning,
    )
    KtorServerState.Stopped -> DesktopServerState.Stopped(port)
    KtorServerState.Error -> DesktopServerState.Failed(
        port = port,
        message = lastError ?: "Server error",
        warning = warning,
    )
}

internal fun initialDesktopServerState(): DesktopServerState =
    DesktopServerState.Starting(RemoteKeys.DEFAULT_PORT)

private fun networkText(recommendedAddress: String?, addresses: List<String>): String =
    recommendedAddress?.let { "Network OK · $it" }
        ?: addresses.firstOrNull()?.let { "Network OK · $it" }
        ?: "No Wi‑Fi / LAN address detected"
