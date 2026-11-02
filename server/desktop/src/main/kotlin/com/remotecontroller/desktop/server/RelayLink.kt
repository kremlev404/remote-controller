package com.remotecontroller.desktop.server

import com.remotecontroller.shared.network.RelayMessage
import com.remotecontroller.shared.network.RelayProtocol
import com.remotecontroller.shared.network.RemoteJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.URI
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

sealed interface RelayState {
    data object Off : RelayState
    data object Connecting : RelayState
    data class Online(val id: String) : RelayState
    data class Failed(val message: String) : RelayState
}

/**
 * Keeps this PC reachable through a rendezvous relay, the way TeamViewer or RustDesk do:
 * one outgoing control socket (so no port forwarding, works behind NAT and CGNAT),
 * and one more outgoing socket per browser that asks for this PC's ID.
 */
class RelayLink(
    private val settings: DesktopSettings,
    private val server: CommandServer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = HttpClient(OkHttp) {
        install(WebSockets) {
            pingInterval = 20.seconds
            maxFrameSize = 8L * 1024 * 1024
        }
    }
    private val deviceKey by lazy { DeviceKey.load() }

    val state: StateFlow<RelayState>
        field = MutableStateFlow<RelayState>(RelayState.Off)

    fun start() {
        scope.launch {
            combine(settings.relayEnabled, settings.relayUrl) { enabled, url -> enabled to url }
                .collectLatest { (enabled, url) ->
                    if (!enabled || url.isBlank()) {
                        state.value = RelayState.Off
                        return@collectLatest
                    }
                    val base = socketBase(url)
                    if (base == null) {
                        state.value = RelayState.Failed("Use an https:// relay address.")
                        return@collectLatest
                    }
                    stayRegistered(base)
                }
        }
    }

    fun stop() {
        scope.cancel()
        client.close()
    }

    private suspend fun stayRegistered(base: String) {
        var failures = 0
        while (true) {
            try {
                state.value = RelayState.Connecting
                register(base)
                failures = 0
                state.value = RelayState.Failed("The relay closed the connection.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failures++
                state.value = RelayState.Failed(error.message ?: error.javaClass.simpleName)
                ServerLog.record("relay: ${error.message ?: error.javaClass.simpleName}", error = true)
            }
            delay((2L shl failures.coerceAtMost(5)).seconds)
        }
    }

    private suspend fun register(base: String) {
        client.webSocket(base + RelayProtocol.HOST_PATH) {
            val challenge = withTimeout(10.seconds) {
                val frame = incoming.receive() as? Frame.Text ?: error("The relay did not answer.")
                RemoteJson.decode<RelayMessage>(frame.readText()) as? RelayMessage.Challenge
            } ?: error("The relay did not send a challenge.")
            send(Frame.Text(RemoteJson.encode<RelayMessage>(deviceKey.register(challenge.nonce))))
            for (frame in incoming) {
                val text = (frame as? Frame.Text)?.readText() ?: continue
                when (val message = runCatching { RemoteJson.decode<RelayMessage>(text) }.getOrNull()) {
                    is RelayMessage.Registered -> {
                        state.value = RelayState.Online(message.id)
                        ServerLog.record("reachable through the relay as ${RelayProtocol.displayId(message.id)}")
                    }
                    is RelayMessage.Refused -> error(message.reason)
                    is RelayMessage.Open -> scope.launch { tunnel(base, message) }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun tunnel(base: String, open: RelayMessage.Open) {
        val path = RelayProtocol.channels[open.channel] ?: return
        val session = open.session.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (session.isEmpty()) return
        runCatching {
            client.webSocket("$base${RelayProtocol.ACCEPT_PATH}/$session") {
                val endpoint = "relay:$session"
                val socket = this
                SocketFrames.bindTunnel(
                    endpoint,
                    object : SocketFrames.Tunnel {
                        override suspend fun send(frame: Frame) {
                            socket.outgoing.send(frame)
                        }

                        override fun close() {
                            scope.launch { socket.close() }
                        }
                    },
                )
                try {
                    val link = LinkSocket(
                        endpoint = endpoint,
                        remote = open.peer.take(64),
                        incoming = incoming,
                        viaRelay = true,
                    )
                    server.serveTunnel(path, link, this)
                } finally {
                    SocketFrames.unbindTunnel(endpoint)
                }
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            ServerLog.record("relay session failed: ${error.message ?: error.javaClass.simpleName}", error = true)
        }
    }

    /** https://relay.example → wss://relay.example. Plain http only for a relay on this computer. */
    private fun socketBase(url: String): String? {
        val uri = runCatching { URI(url.trim().trimEnd('/')) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val local = host == "localhost" || host == "127.0.0.1"
        val scheme = when (uri.scheme?.lowercase()) {
            "https" -> "wss"
            "http" -> if (local) "ws" else return null
            else -> return null
        }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }
}

/**
 * This PC's long-term relay identity. The relay derives the ID from the public key,
 * so nobody else can register the same ID without this file.
 */
internal class DeviceKey private constructor(
    private val pair: KeyPair,
) {
    fun register(nonce: String): RelayMessage.Register {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(RelayProtocol.SIGN_LABEL.encodeToByteArray())
            update(Base64.getDecoder().decode(nonce))
            sign()
        }
        val encoder = Base64.getEncoder()
        return RelayMessage.Register(pub = encoder.encodeToString(pair.public.encoded), sig = encoder.encodeToString(signature))
    }

    companion object {
        fun load(): DeviceKey {
            val privateFile = File(AppFiles.directory, "relay-device.p8")
            val publicFile = File(AppFiles.directory, "relay-device.pub")
            val loaded = runCatching {
                val factory = KeyFactory.getInstance("EC")
                KeyPair(
                    factory.generatePublic(X509EncodedKeySpec(publicFile.readBytes())),
                    factory.generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes())),
                )
            }.getOrNull()
            if (loaded != null) return DeviceKey(loaded)
            val pair = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            }.generateKeyPair()
            privateFile.writeBytes(pair.private.encoded)
            AppFiles.restrict(privateFile)
            publicFile.writeBytes(pair.public.encoded)
            return DeviceKey(pair)
        }
    }
}
