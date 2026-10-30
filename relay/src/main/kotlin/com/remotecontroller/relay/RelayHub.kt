package com.remotecontroller.relay

import com.remotecontroller.shared.network.RelayMessage
import com.remotecontroller.shared.network.RelayProtocol
import com.remotecontroller.shared.network.RemoteJson
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * PCs register over one websocket each and prove they hold the key behind their ID.
 * A browser that asks for an ID gets a one-time session; the PC dials back for it and
 * the two sockets are piped frame by frame. Nothing is decrypted here.
 */
class RelayHub(
    private val config: RelayConfig,
) {
    private val hosts = ConcurrentHashMap<String, HostEntry>()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val registrations = RateLimiter(limit = 10)
    private val connections = RateLimiter(limit = 60)
    private val random = SecureRandom()

    suspend fun host(session: DefaultWebSocketServerSession, address: String) {
        if (!registrations.allow(address) || hosts.size >= MAX_HOSTS) {
            session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "busy"))
            return
        }
        val nonce = bytes(32)
        session.sendMessage(RelayMessage.Challenge(Base64.getEncoder().encodeToString(nonce)))
        val register = withTimeoutOrNull(HANDSHAKE_TIMEOUT) {
            val frame = session.incoming.receive() as? Frame.Text ?: return@withTimeoutOrNull null
            runCatching { RemoteJson.decode<RelayMessage>(frame.readText()) }.getOrNull() as? RelayMessage.Register
        }
        val key = register?.let { runCatching { Base64.getDecoder().decode(it.pub) }.getOrNull() }
        if (register == null || key == null || !verified(key, nonce, register.sig)) {
            session.sendMessage(RelayMessage.Refused("The relay could not verify this PC."))
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unverified"))
            return
        }
        val id = RelayProtocol.deviceId(key)
        val entry = HostEntry(id, key, session)
        val previous = hosts[id]
        if (previous != null && !previous.key.contentEquals(key)) {
            session.sendMessage(RelayMessage.Refused("Another PC holds this ID."))
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "taken"))
            return
        }
        hosts[id] = entry
        previous?.let { runCatching { it.session.close(CloseReason(CloseReason.Codes.NORMAL, "replaced")) } }
        entry.send(RelayMessage.Registered(id))
        try {
            for (frame in session.incoming) {
                // The PC only listens here. Pings are answered by the websocket itself.
            }
        } finally {
            hosts.remove(id, entry)
        }
    }

    suspend fun connect(
        peer: DefaultWebSocketServerSession,
        id: String,
        channel: String,
        origin: String?,
        hostHeader: String?,
        address: String,
    ) {
        if (!originAllowed(origin, hostHeader)) {
            peer.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin"))
            return
        }
        if (!connections.allow(address)) {
            peer.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "slow down"))
            return
        }
        if (channel !in RelayProtocol.channels) {
            peer.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "channel"))
            return
        }
        val host = hosts[RelayProtocol.normalizeId(id)]
        if (host == null) {
            peer.close(CloseReason(RelayProtocol.CLOSE_OFFLINE, "offline"))
            return
        }
        if (host.tunnels.incrementAndGet() > MAX_TUNNELS_PER_HOST) {
            host.tunnels.decrementAndGet()
            peer.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "busy"))
            return
        }
        val session = token()
        val waiting = Pending()
        pending[session] = waiting
        try {
            host.send(RelayMessage.Open(session = session, channel = channel, peer = address))
            val pc = withTimeoutOrNull(HANDSHAKE_TIMEOUT) { waiting.pc.await() }
            if (pc == null) {
                peer.close(CloseReason(RelayProtocol.CLOSE_OFFLINE, "no answer"))
                return
            }
            try {
                pipe(peer, pc)
            } finally {
                waiting.done.complete(Unit)
            }
        } finally {
            pending.remove(session)
            host.tunnels.decrementAndGet()
        }
    }

    /** The PC dials back for [session]; the socket stays open until the pipe ends. */
    suspend fun accept(session: DefaultWebSocketServerSession, token: String) {
        val waiting = pending.remove(token)
        if (waiting == null || !waiting.pc.complete(session)) {
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unknown session"))
            return
        }
        waiting.done.await()
    }

    private suspend fun pipe(a: DefaultWebSocketSession, b: DefaultWebSocketSession) {
        coroutineScope {
            val finished = CompletableDeferred<Unit>()
            val jobs = listOf(
                launch { copy(a, b); finished.complete(Unit) },
                launch { copy(b, a); finished.complete(Unit) },
            )
            finished.await()
            jobs.forEach { it.cancel() }
        }
        runCatching { a.close() }
        runCatching { b.close() }
    }

    private suspend fun copy(from: DefaultWebSocketSession, to: DefaultWebSocketSession) {
        runCatching {
            for (frame in from.incoming) {
                if (frame is Frame.Text || frame is Frame.Binary) to.outgoing.send(frame)
            }
        }
    }

    private fun originAllowed(origin: String?, hostHeader: String?): Boolean {
        if (origin == null) return true
        val expected = config.publicOrigin
        if (expected != null) return origin.trimEnd('/').equals(expected, ignoreCase = true)
        val authority = origin.substringAfter("://", "").trimEnd('/')
        return hostHeader != null && authority.equals(hostHeader, ignoreCase = true)
    }

    private fun verified(key: ByteArray, nonce: ByteArray, signature: String): Boolean {
        return runCatching {
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key)) as ECPublicKey
            if (publicKey.params.curve.field.fieldSize != 256) return false
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(RelayProtocol.SIGN_LABEL.encodeToByteArray())
                update(nonce)
                verify(Base64.getDecoder().decode(signature))
            }
        }.getOrDefault(false)
    }

    private fun bytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun token(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(18))

    private class HostEntry(
        val id: String,
        val key: ByteArray,
        val session: DefaultWebSocketServerSession,
    ) {
        val tunnels = AtomicInteger(0)
        private val sendLock = Mutex()

        suspend fun send(message: RelayMessage) {
            sendLock.withLock { session.sendMessage(message) }
        }
    }

    private class Pending {
        val pc = CompletableDeferred<DefaultWebSocketServerSession>()
        val done = CompletableDeferred<Unit>()
    }

    private companion object {
        const val MAX_HOSTS = 50_000
        const val MAX_TUNNELS_PER_HOST = 16
        val HANDSHAKE_TIMEOUT = 10.seconds
    }
}

private suspend fun DefaultWebSocketSession.sendMessage(message: RelayMessage) {
    outgoing.send(Frame.Text(RemoteJson.encode(message)))
}

/** At most [limit] events per address per minute. */
private class RateLimiter(
    private val limit: Int,
) {
    private val windows = ConcurrentHashMap<String, Window>()
    private val clock = TimeSource.Monotonic

    fun allow(address: String): Boolean {
        if (windows.size > MAX_TRACKED) windows.entries.removeIf { it.value.start.elapsedNow() > 1.minutes }
        val window = windows.compute(address) { _, current ->
            if (current == null || current.start.elapsedNow() > 1.minutes) Window(clock.markNow()) else current
        }!!
        return window.count.incrementAndGet() <= limit
    }

    private class Window(val start: TimeMark) {
        val count = AtomicInteger(0)
    }

    private companion object {
        const val MAX_TRACKED = 100_000
    }
}
