@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.remotecontroller.client.data.network

import com.remotecontroller.client.data.log.logInfo
import com.remotecontroller.client.domain.model.NetworkMonitor
import com.remotecontroller.client.domain.model.NetworkStatus
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Network.nw_interface_get_name
import platform.Network.nw_interface_get_type
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_other
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_interface_type_wired
import platform.Network.nw_path_enumerate_interfaces
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_create_with_type
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_monitor_t
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_t
import platform.Network.nw_path_uses_interface_type
import platform.darwin.dispatch_get_main_queue

internal class IosNetworkMonitor : NetworkMonitor {
    override val status: StateFlow<NetworkStatus>
        field = MutableStateFlow(NetworkStatus())

    private var online = false
    private var wifi = false
    private var wired = false
    private var cellular = false
    private var wifiLinkUp = false

    private val generalMonitor = nw_path_monitor_create()
    private val wifiMonitor = nw_path_monitor_create_with_type(nw_interface_type_wifi)
    private val onGeneralPath: (nw_path_t) -> Unit = { path ->
        if (path != null) {
            val link = readLink(path)
            online = nw_path_get_status(path) == nw_path_status_satisfied
            wifi = link.wifi
            wired = link.wired
            cellular = link.cellular
            publish()
        }
    }
    private val onWifiPath: (nw_path_t) -> Unit = { path ->
        wifiLinkUp = path != null && nw_path_get_status(path) == nw_path_status_satisfied
        publish()
    }

    init {
        start(generalMonitor, onGeneralPath)
        start(wifiMonitor, onWifiPath)
    }

    private fun start(monitor: nw_path_monitor_t, handler: (nw_path_t) -> Unit) {
        if (monitor == null) return
        nw_path_monitor_set_update_handler(monitor, handler)
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
    }

    private fun publish() {
        val onWifi = wifi || wifiLinkUp
        val cellularOnly = cellular && !onWifi && !wired
        val next = NetworkStatus(
            isOnline = online,
            isWifi = onWifi,
            hasLanTransport = onWifi || wired || (online && !cellularOnly),
        )
        if (status.value == next) return
        status.value = next
        logInfo(TAG, "online=${next.isOnline} wifi=${next.isWifi} lan=${next.hasLanTransport}")
    }

    private fun readLink(path: nw_path_t): Link {
        var linkWifi = nw_path_uses_interface_type(path, nw_interface_type_wifi)
        val linkWired = nw_path_uses_interface_type(path, nw_interface_type_wired)
        val linkCellular = nw_path_uses_interface_type(path, nw_interface_type_cellular)
        nw_path_enumerate_interfaces(path) { iface ->
            if (iface != null &&
                nw_interface_get_type(iface) == nw_interface_type_other &&
                nw_interface_get_name(iface)?.toKString().orEmpty().startsWith("en")
            ) {
                linkWifi = true
            }
            true
        }
        return Link(linkWifi, linkWired, linkCellular)
    }

    private data class Link(
        val wifi: Boolean,
        val wired: Boolean,
        val cellular: Boolean,
    )

    private companion object {
        const val TAG = "LanNetwork"
    }
}
