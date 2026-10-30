package com.remotecontroller.desktop.server

import com.remotecontroller.shared.network.ChunkBuffer
import com.remotecontroller.shared.network.LinkAuth
import com.remotecontroller.shared.network.LinkCrypto
import com.remotecontroller.shared.network.LinkGrant
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** One incoming websocket, from the LAN listener or through the relay. */
internal class LinkSocket(
    val endpoint: String,
    val remote: String,
    val incoming: ReceiveChannel<Frame>,
    val origin: String? = null,
    val host: String? = null,
    val viaRelay: Boolean = false,
)

/**
 * Runs the link handshake for every socket: address and Origin checks, a time limit,
 * a cap on handshakes in flight, a back-off for peers that keep failing, then the
 * access code or the owner's approval.
 */
internal class LinkGate(
    private val pairingCode: String,
    private val settings: DesktopSettings,
    private val approvals: ConnectionApprovals,
    private val grants: LinkGrants,
    private val log: (String) -> Unit,
) {
    private val throttle = AdmissionThrottle()
    private val inFlight = Semaphore(MAX_HANDSHAKES)

    suspend fun admit(link: LinkSocket): Boolean {
        if (!link.viaRelay && !LanGuard.trusted(link.remote)) return false
        if (!LanGuard.sameOrigin(link.origin, link.host)) {
            log("refused a page from ${link.origin}")
            return false
        }
        val peer = if (link.viaRelay) "relay ${link.remote}" else link.remote
        if (!throttle.allowed(peer)) {
            log("$peer is waiting after failed attempts")
            return false
        }
        if (!inFlight.tryAcquire()) return false
        return try {
            handshake(link, peer)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        } finally {
            inFlight.release()
        }
    }

    private suspend fun handshake(link: LinkSocket, peer: String): Boolean {
        val endpoint = link.endpoint
        val auth = if (settings.requireCode.value) LinkAuth.CODE else LinkAuth.APPROVE
        val identity = LinkCrypto.generate()
        SocketFrames.sendText(endpoint, LinkCrypto.hello(identity, auth))
        val join = withTimeoutOrNull(HANDSHAKE_TIMEOUT) {
            readText(endpoint, link.incoming)?.let(LinkCrypto::readJoin)
        } ?: return false

        var grant: LinkGrant? = null
        val grantId = join.grant
        val secret = when {
            auth == LinkAuth.CODE -> LinkCrypto.codeSecret(pairingCode)
            grantId != null -> {
                grant = grants.find(grantId)
                if (grant == null) {
                    SocketFrames.sendText(endpoint, LinkCrypto.UNKNOWN_GRANT)
                    return false
                }
                LinkCrypto.grantSecret(grant.key)
            }
            else -> ByteArray(0)
        }
        val cipher = LinkCrypto.accept(identity, join, secret)
        val confirmed = withTimeoutOrNull(HANDSHAKE_TIMEOUT) {
            val box = readBinary(endpoint, link.incoming) ?: return@withTimeoutOrNull false
            runCatching { cipher.open(box).decodeToString() == LinkCrypto.CONFIRM }.getOrDefault(false)
        } ?: false
        if (!confirmed) {
            throttle.failed(peer)
            log(if (auth == LinkAuth.CODE) "wrong access code from $peer" else "broken handshake from $peer")
            return false
        }

        if (auth == LinkAuth.APPROVE && grant == null) {
            val name = join.name.replace(Regex("\\s+"), " ").trim().take(48).ifBlank { "Unknown device" }
            val allowed = askOwner(link, name, peer, cipher.checkCode)
            if (!allowed) {
                throttle.failed(peer)
                log("connection from $name ($peer) was not allowed")
                return false
            }
            grant = grants.issue()
        }

        val ready = LinkCrypto.readyText(grant).encodeToByteArray()
        cipher.seal(ready) { box -> SocketFrames.sendBinary(endpoint, box) }
        throttle.succeeded(peer)
        SecureFrames.bind(endpoint, cipher, grant?.id)
        return true
    }

    /**
     * Waits for Allow while still answering pings (phones drop a socket whose pings go unanswered),
     * and withdraws the request if the device gives up first.
     */
    private suspend fun askOwner(link: LinkSocket, name: String, peer: String, checkCode: String): Boolean =
        coroutineScope {
            val decision = async { approvals.ask(name, peer, checkCode, link.viaRelay) }
            val watcher = launch {
                for (frame in link.incoming) {
                    if (frame is Frame.Ping) runCatching { SocketFrames.sendPong(link.endpoint, frame.data) }
                    if (frame is Frame.Close) break
                }
                decision.cancel()
            }
            val allowed = try {
                decision.await()
            } catch (_: CancellationException) {
                false
            }
            watcher.cancel()
            allowed
        }

    private suspend fun readText(endpoint: String, incoming: ReceiveChannel<Frame>): String? {
        val buffer = ChunkBuffer(LinkCrypto.MAX_HANDSHAKE_TEXT)
        for (frame in incoming) {
            when (frame) {
                is Frame.Text -> return buffer.push(frame.data, frame.fin)?.decodeToString() ?: continue
                is Frame.Ping -> runCatching { SocketFrames.sendPong(endpoint, frame.data) }
                is Frame.Close -> return null
                else -> return null
            }
        }
        return null
    }

    private suspend fun readBinary(endpoint: String, incoming: ReceiveChannel<Frame>): ByteArray? {
        val buffer = ChunkBuffer(LinkCrypto.MAX_HANDSHAKE_TEXT)
        for (frame in incoming) {
            when (frame) {
                is Frame.Binary -> return buffer.push(frame.data, frame.fin) ?: continue
                is Frame.Ping -> runCatching { SocketFrames.sendPong(endpoint, frame.data) }
                is Frame.Close -> return null
                else -> return null
            }
        }
        return null
    }

    private companion object {
        const val MAX_HANDSHAKES = 8
        val HANDSHAKE_TIMEOUT = 10.seconds
    }
}

/** Grants live in memory: a restart of the app, or turning the code back on, forgets them. */
internal class LinkGrants {
    private val grants = ConcurrentHashMap<String, LinkGrant>()
    private val random = SecureRandom()

    fun issue(): LinkGrant {
        val grant = LinkGrant(id = token(16), key = Base64.getEncoder().encodeToString(bytes(32)))
        grants[grant.id] = grant
        return grant
    }

    fun find(id: String): LinkGrant? = grants[id]

    fun revoke(id: String) {
        grants.remove(id)
    }

    fun revokeAll() {
        grants.clear()
    }

    private fun bytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun token(size: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(size))
}

/** Five failures in a minute, then that peer waits five minutes. */
internal class AdmissionThrottle {
    private val peers = ConcurrentHashMap<String, Record>()
    private val clock = TimeSource.Monotonic

    fun allowed(peer: String): Boolean {
        val record = peers[peer] ?: return true
        val until = record.blockedUntil ?: return true
        if (until.hasPassedNow()) {
            peers.remove(peer)
            return true
        }
        return false
    }

    fun failed(peer: String) {
        peers.compute(peer) { _, current ->
            val record = current?.takeUnless { it.windowStart.elapsedNow() > WINDOW } ?: Record(clock.markNow())
            record.failures++
            if (record.failures >= MAX_FAILURES) record.blockedUntil = clock.markNow() + BLOCK
            record
        }
        if (peers.size > MAX_TRACKED) peers.entries.removeIf { it.value.windowStart.elapsedNow() > BLOCK }
    }

    fun succeeded(peer: String) {
        peers.remove(peer)
    }

    private class Record(val windowStart: TimeMark) {
        var failures = 0
        var blockedUntil: TimeMark? = null
    }

    private companion object {
        const val MAX_FAILURES = 5
        const val MAX_TRACKED = 1024
        val WINDOW = 1.minutes
        val BLOCK = 5.minutes
    }
}
