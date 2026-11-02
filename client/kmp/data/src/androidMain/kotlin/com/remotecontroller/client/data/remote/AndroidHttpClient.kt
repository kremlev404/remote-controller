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
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager
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
            val trust = PinningTrustManager()
            sslSocketFactory(pinningSocketFactory(network, trust), trust)
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

private fun pinningSocketFactory(
    network: AndroidNetworkMonitor,
    trust: X509ExtendedTrustManager,
): SSLSocketFactory {
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf<TrustManager>(trust), SecureRandom())
    val delegate = context.socketFactory
    val lan = LanSocketFactory(network)
    return object : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            delegate.createSocket(socket, host, port, autoClose)

        override fun createSocket(host: String?, port: Int): Socket =
            delegate.createSocket(lan.createSocket(host, port), host, port, true)

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            delegate.createSocket(lan.createSocket(host, port, localHost, localPort), host, port, true)

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            delegate.createSocket(lan.createSocket(host, port), host?.hostAddress, port, true)

        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int,
        ): Socket = delegate.createSocket(
            lan.createSocket(address, port, localAddress, localPort),
            address?.hostAddress,
            port,
            true,
        )
    }
}

private class PinningTrustManager : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        throw CertificateException("No client certificate")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        throw CertificateException("No client certificate")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("No client certificate")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        trust(chain)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        trust(chain)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        trust(chain)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun trust(chain: Array<out X509Certificate>?) {
        if (!ServerTrust.enforced()) return
        val certificates = chain?.toList().orEmpty()
        if (certificates.isEmpty()) throw CertificateException("The certificate does not match this PC.")
        val authority = certificates.firstOrNull { certificate ->
            ServerTrust.accepts(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))
        } ?: throw CertificateException("The certificate does not match this PC.")
        val leaf = certificates.first()
        leaf.checkValidity()
        if (leaf != authority) {
            runCatching { leaf.verify(authority.publicKey) }.getOrElse {
                throw CertificateException("The certificate does not match this PC.")
            }
        }
    }
}

private class LanDns(
    private val network: AndroidNetworkMonitor,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        network.lookupOnLan(hostname) ?: Dns.SYSTEM.lookup(hostname)
}
