package com.remotecontroller.client.data.remote

import com.remotecontroller.client.domain.repository.DeviceGrantRepository
import com.remotecontroller.shared.network.ChunkBuffer
import com.remotecontroller.shared.network.LinkCipher
import com.remotecontroller.shared.network.LinkCrypto
import com.remotecontroller.shared.network.LinkFrame
import com.remotecontroller.shared.network.LinkSocket
import com.remotecontroller.shared.network.openLink
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.send
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

internal suspend fun WebSocketSession.establishLink(
    endpoint: String,
    code: String,
    grants: DeviceGrantRepository,
    deviceName: String = "",
): LinkCipher = KtorLinkSocket(this).openLink(endpoint, code, grants, deviceName, LinkSessions::awaiting)

private class KtorLinkSocket(
    private val session: WebSocketSession,
) : LinkSocket {
    override suspend fun readHello(): String = session.readPlainText()

    override suspend fun sendText(text: String) = session.send(Frame.Text(text))

    override suspend fun sendBytes(bytes: ByteArray) = session.send(Frame.Binary(true, bytes))

    override suspend fun readReply(): ByteArray? = session.readReply()
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
