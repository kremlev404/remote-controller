package com.remotecontroller.client.data.remote

import com.remotecontroller.client.data.network.AndroidNetworkMonitor
import com.remotecontroller.shared.network.RemoteJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.serialization.kotlinx.json.json
import okhttp3.Dns
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.time.Duration.Companion.seconds

private const val CONNECT_TIMEOUT_MS = 5_000L
private const val REQUEST_TIMEOUT_MS = 8_000L

fun remoteHttpClient(network: AndroidNetworkMonitor): HttpClient = HttpClient(OkHttp) {
    expectSuccess = true
    install(ContentNegotiation) { json(RemoteJson.json) }
    install(WebSockets) {
        pingInterval = 20.seconds
    }
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
        socketTimeoutMillis = REQUEST_TIMEOUT_MS
    }
    engine {
        config {
            connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            readTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            writeTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            retryOnConnectionFailure(true)
            proxy(Proxy.NO_PROXY)
            socketFactory(LanSocketFactory(network))
            dns(LanDns(network))
        }
    }
}

private class LanSocketFactory(
    private val network: AndroidNetworkMonitor,
) : SocketFactory() {
    override fun createSocket(): Socket = network.openLanSocket()

    override fun createSocket(host: String?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = createSocket().apply {
        bind(InetSocketAddress(localAddress, localPort))
        connect(InetSocketAddress(address, port))
    }
}

private class LanDns(
    private val network: AndroidNetworkMonitor,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        network.lookupOnLan(hostname) ?: Dns.SYSTEM.lookup(hostname)
}
