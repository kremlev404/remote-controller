package com.remotecontroller.client.data.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.remotecontroller.client.domain.model.LocalNetworkAccess
import com.remotecontroller.client.domain.model.NetworkMonitor
import com.remotecontroller.client.domain.model.NetworkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket

class AndroidNetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val localNetwork: LocalNetworkAccess,
) : NetworkMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lanNetwork = MutableStateFlow<Network?>(null)
    private val request = NetworkRequest.Builder()
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    override val status: StateFlow<NetworkStatus>
        field = MutableStateFlow(NetworkStatus())

    init {
        publish()
        scope.launch { watchNetworks() }
    }

    fun openLanSocket(): Socket {
        if (!localNetwork.isGranted()) return Socket()
        val network = lanNetwork.value ?: return Socket()
        val socket = Socket()
        return try {
            network.bindSocket(socket)
            socket
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Wi-Fi bind failed (${error.message}); using the default route")
            runCatching { socket.close() }
            Socket()
        }
    }

    fun lookupOnLan(hostname: String): List<InetAddress>? {
        if (!localNetwork.isGranted()) return null
        val network = lanNetwork.value ?: return null
        return runCatching { network.getAllByName(hostname).toList() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private suspend fun watchNetworks() {
        callbackFlow {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(Unit)
                }

                override fun onLost(network: Network) {
                    trySend(Unit)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    trySend(Unit)
                }
            }
            val registered = runCatching {
                connectivity.registerNetworkCallback(request, callback)
            }.isSuccess
            trySend(Unit)
            awaitClose {
                if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) }
            }
        }.collect { publish() }
    }

    private fun publish() {
        val ranked = connectivity.allNetworks.mapNotNull(::rank)
        val best = ranked.maxByOrNull { it.score }
        val selected = best?.network
        if (lanNetwork.value != selected) {
            lanNetwork.value = selected
            Log.i(LOG_TAG, selected?.let { "PC traffic via ${describe(it)}" } ?: "no Wi-Fi/Ethernet for the PC")
        }
        status.value = NetworkStatus(
            isOnline = connectivity.allNetworks.any(::hasInternet),
            isWifi = ranked.any { it.score > 0 && it.wifi },
            hasLanTransport = selected != null,
        )
    }

    private fun rank(network: Network): LanCandidate? {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!wifi && !ethernet) return null
        val properties = connectivity.getLinkProperties(network)
        val name = properties?.interfaceName?.lowercase().orEmpty()
        val privateV4 = properties?.linkAddresses.orEmpty().any { link ->
            (link.address as? Inet4Address)?.isPrivateLan() == true
        }
        var score = 0
        if (wifi) score += 300
        if (ethernet) score += 200
        if (privateV4) score += 80
        if (name.contains("p2p") || name.contains("wifi-direct") || name.contains("wifidirect")) score -= 250
        return LanCandidate(network, wifi, score)
    }

    private fun hasInternet(network: Network): Boolean =
        connectivity.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun Inet4Address.isPrivateLan(): Boolean {
        val first = address[0].toInt() and 0xFF
        val second = address[1].toInt() and 0xFF
        return first == 10 || (first == 192 && second == 168) || (first == 172 && second in 16..31)
    }

    private fun describe(network: Network): String {
        val properties = connectivity.getLinkProperties(network)
        val name = properties?.interfaceName ?: "network"
        val addresses = properties?.linkAddresses
            ?.mapNotNull { it.address?.hostAddress }
            ?.joinToString()
            .orEmpty()
        return "$name $addresses".trim()
    }

    private data class LanCandidate(
        val network: Network,
        val wifi: Boolean,
        val score: Int,
    )
}

private const val LOG_TAG = "LanNetwork"
