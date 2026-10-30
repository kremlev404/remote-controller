package com.remotecontroller.client.data.remote

import com.remotecontroller.client.domain.repository.DeviceGrantRepository
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

/** The check code of a link that is waiting for the owner to press Allow. Process-wide, like [ServerTrust]. */
object LinkSessions {
    val awaitingApproval: StateFlow<String?>
        field = MutableStateFlow(null)

    internal fun awaiting(checkCode: String?) {
        awaitingApproval.value = checkCode
    }
}

/**
 * hello → join → confirm → ready. A device the PC remembers gets in with its grant. Otherwise
 * the code answers at once, or the first socket waits for Allow on the PC. Either way the
 * PC hands back a grant, kept under the PC's id so a new address changes nothing.
 */
internal suspend fun WebSocketSession.establishLink(
    endpoint: String,
    code: String,
    grants: DeviceGrantRepository,
    deviceName: String = "",
): LinkCipher {
    val offer = LinkCrypto.offer(readPlainText())
    val pc = offer.hello.pc.ifBlank { endpoint }
    val grant = grants.grantFor(pc)
    val (join, cipher) = LinkCrypto.join(offer, code, grant, deviceName)
    send(Frame.Text(join))
    cipher.seal(LinkCrypto.CONFIRM.encodeToByteArray()) { box -> send(Frame.Binary(true, box)) }
    val waiting = !offer.needsCode && grant == null
    if (waiting) LinkSessions.awaiting(cipher.checkCode)
    try {
        val box = readReply()
        if (box != null && box.isEmpty()) {
            grants.forget(pc)
            throw GrantExpired()
        }
        val ready = try {
            box?.let { LinkCrypto.openReady(cipher, it) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
        if (ready == null) {
            // A socket that dropped says nothing about the grant: only an answer from the PC does.
            if (grant != null && box == null) throw IllegalStateException("Connection closed")
            if (grant != null) grants.forget(pc)
            throw rejection(offer, grant, waiting)
        }
        val id = ready.grantId
        val key = ready.grantKey
        if (id != null && key != null && id != grant?.id) grants.remember(pc, LinkGrant(id, key))
        return cipher
    } finally {
        if (waiting) LinkSessions.awaiting(null)
    }
}

private fun rejection(offer: LinkOffer, grant: LinkGrant?, waiting: Boolean): Throwable = when {
    grant != null -> GrantExpired()
    offer.needsCode -> AccessCodeRejected()
    waiting -> ApprovalDeclined()
    else -> GrantExpired()
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
