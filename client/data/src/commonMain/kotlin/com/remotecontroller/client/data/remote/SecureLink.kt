package com.remotecontroller.client.data.remote

import com.remotecontroller.shared.network.AccessCodeRejected
import com.remotecontroller.shared.network.ApprovalDeclined
import com.remotecontroller.shared.network.ChunkBuffer
import com.remotecontroller.shared.network.GrantExpired
import com.remotecontroller.shared.network.LinkCipher
import com.remotecontroller.shared.network.LinkCrypto
import com.remotecontroller.shared.network.LinkFrame
import com.remotecontroller.shared.network.LinkGrant
import com.remotecontroller.shared.network.LinkOffer
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Grants from PCs that approved this device (in memory, per endpoint), and the check code
 * of a link that is waiting for the owner to press Allow. Process-wide, like [ServerTrust].
 */
object LinkSessions {
    private val grants = MutableStateFlow<Map<String, LinkGrant>>(emptyMap())

    val awaitingApproval: StateFlow<String?>
        field = MutableStateFlow(null)

    internal fun grantFor(endpoint: String): LinkGrant? = grants.value[endpoint]

    internal fun remember(endpoint: String, grant: LinkGrant) {
        grants.update { it + (endpoint to grant) }
    }

    internal fun forget(endpoint: String) {
        grants.update { it - endpoint }
    }

    internal fun awaiting(checkCode: String?) {
        awaitingApproval.value = checkCode
    }
}

/**
 * hello → join → confirm → ready. With a code the PC answers at once; without one the
 * first socket waits for Allow on the PC and comes back with a grant for the others.
 */
internal suspend fun WebSocketSession.establishLink(
    endpoint: String,
    code: String,
    deviceName: String = "",
): LinkCipher {
    val offer = LinkCrypto.offer(readPlainText())
    val grant = if (offer.needsCode) null else LinkSessions.grantFor(endpoint)
    val (join, cipher) = LinkCrypto.join(offer, code, grant, deviceName)
    send(Frame.Text(join))
    cipher.seal(LinkCrypto.CONFIRM.encodeToByteArray()) { box -> send(Frame.Binary(true, box)) }
    val waiting = !offer.needsCode && grant == null
    if (waiting) LinkSessions.awaiting(cipher.checkCode)
    try {
        val box = readReply() ?: throw rejection(offer, endpoint, waiting)
        if (box.isEmpty()) {
            LinkSessions.forget(endpoint)
            throw GrantExpired()
        }
        val ready = try {
            LinkCrypto.openReady(cipher, box)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw rejection(offer, endpoint, waiting)
        }
        val id = ready.grantId
        val key = ready.grantKey
        if (id != null && key != null) LinkSessions.remember(endpoint, LinkGrant(id, key))
        return cipher
    } finally {
        if (waiting) LinkSessions.awaiting(null)
    }
}

private fun rejection(offer: LinkOffer, endpoint: String, waiting: Boolean): Throwable = when {
    offer.needsCode -> AccessCodeRejected()
    waiting -> ApprovalDeclined()
    else -> {
        LinkSessions.forget(endpoint)
        GrantExpired()
    }
}

internal suspend fun LinkCipher.sendText(socket: WebSocketSession, text: String) {
    seal(LinkFrame.pack(LinkFrame.TEXT, text.encodeToByteArray())) { box ->
        socket.send(Frame.Binary(true, box))
    }
}

internal suspend fun LinkCipher.openPayload(box: ByteArray): Pair<Byte, ByteArray> =
    LinkFrame.unpack(open(box))

private suspend fun WebSocketSession.readPlainText(): String {
    val buffer = ChunkBuffer(LinkCrypto.MAX_HANDSHAKE_TEXT)
    for (frame in incoming) {
        when (frame) {
            is Frame.Text -> {
                val text = buffer.push(frame.data, frame.fin) ?: continue
                return text.decodeToString()
            }
            is Frame.Close -> throw IllegalStateException("Connection closed")
            else -> Unit
        }
    }
    throw IllegalStateException("Connection closed")
}

/** The sealed `ready`, an empty array for "unknown grant", or null when the PC closed the socket. */
private suspend fun WebSocketSession.readReply(): ByteArray? {
    val buffer = ChunkBuffer(LinkCrypto.MAX_HANDSHAKE_TEXT)
    for (frame in incoming) {
        when (frame) {
            is Frame.Binary -> return buffer.push(frame.data, frame.fin) ?: continue
            is Frame.Text -> if (frame.data.decodeToString() == LinkCrypto.UNKNOWN_GRANT) return ByteArray(0)
            is Frame.Close -> return null
            else -> Unit
        }
    }
    return null
}
