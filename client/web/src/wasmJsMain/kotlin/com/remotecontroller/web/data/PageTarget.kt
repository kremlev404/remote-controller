package com.remotecontroller.web.data

import com.remotecontroller.client.domain.model.PcConnection
import com.remotecontroller.shared.model.PcHost
import com.remotecontroller.shared.model.ServerPort
import com.remotecontroller.shared.network.RelayProtocol
import com.remotecontroller.web.browser.isRelayPage
import com.remotecontroller.web.browser.socketOrigin
import com.remotecontroller.web.browser.takePageHash

/** Where the page connects: straight to the PC that served it, or to a PC ID through the relay. */
data class PageTarget(
    val relay: Boolean,
    val deviceId: String,
    val code: String,
) {
    fun socketUrl(path: String): String {
        if (!relay) return socketOrigin() + path
        val channel = RelayProtocol.channelName(path) ?: error("no relay channel for $path")
        return socketOrigin() + RelayProtocol.CONNECT_PATH + "/" + deviceId + "/" + channel
    }

    /** The page reaches its PC through [socketUrl]: of a connection, only the code means anything here. */
    fun connection(accessCode: String): PcConnection =
        PcConnection(host = PcHost(PAGE_HOST), port = ServerPort.DEFAULT, accessCode = accessCode)

    companion object {
        private const val PAGE_HOST = "page"

        /** `#XXXX-XXXX-XXXX` (old links) or `#id=123456789&code=XXXX-XXXX-XXXX`. */
        fun fromPage(): PageTarget {
            val hash = takePageHash()
            val params = if ('=' in hash) {
                hash.split('&').mapNotNull { part ->
                    val key = part.substringBefore('=', "")
                    if (key.isEmpty()) null else key to part.substringAfter('=')
                }.toMap()
            } else {
                mapOf("code" to hash)
            }
            return PageTarget(
                relay = isRelayPage(),
                deviceId = RelayProtocol.normalizeId(params["id"].orEmpty()),
                code = params["code"].orEmpty(),
            )
        }
    }
}
