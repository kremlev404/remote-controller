package com.remotecontroller.web.data

import com.remotecontroller.shared.network.LinkCipher
import com.remotecontroller.shared.network.LinkCrypto
import com.remotecontroller.shared.network.LinkFrame
import com.remotecontroller.shared.network.LinkGrant
import com.remotecontroller.shared.network.LinkGrants
import com.remotecontroller.shared.network.LinkSocket
import com.remotecontroller.shared.network.RemoteJson
import com.remotecontroller.shared.network.openLink
import com.remotecontroller.web.browser.BrowserSocket
import com.remotecontroller.web.browser.SocketClosed
import com.remotecontroller.web.browser.SocketMessage
import com.remotecontroller.web.browser.browserLabel
import com.remotecontroller.web.browser.readStored
import com.remotecontroller.web.browser.removeStored
import com.remotecontroller.web.browser.writeStored
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

private val BUSY_WAIT = 4.milliseconds

/** The page's way to the PC: each socket it opens runs the handshake every client runs. */
class PageLink(
    private val target: PageTarget,
) {
    private val grants = PageGrants()

    /** The check code while the PC's owner is asked to allow this page, else null. */
    val approvalCode: StateFlow<String?>
        field = MutableStateFlow(null)

    /** Opens a socket to [path] and runs [session] on it. The socket closes when [session] ends. */
    suspend fun <T> connect(path: String, accessCode: String, session: suspend (SealedSocket) -> T): T {
        val socket = BrowserSocket(target.socketUrl(path))
        try {
            val cipher = HandshakeSocket(socket).openLink(target.deviceId, accessCode, grants, browserLabel()) { checkCode ->
                approvalCode.value = checkCode
            }
            return session(SealedSocket(socket, cipher))
        } finally {
            socket.close()
        }
    }
}

/** A socket past its handshake. */
class SealedSocket internal constructor(
    private val socket: BrowserSocket,
    private val cipher: LinkCipher,
) {
    /** What the PC sends, opened. Fails on a frame that was replayed or altered, and when the socket closes. */
    val frames: Flow<Pair<Byte, ByteArray>> = flow {
        for (message in socket.incoming) {
            if (message is SocketMessage.Bytes) emit(LinkFrame.unpack(cipher.open(message.bytes)))
        }
    }

    /** Returns once the socket has taken [text]. A slow link makes this wait, not the browser's buffer grow. */
    suspend fun send(text: String) {
        while (socket.isBusy) delay(BUSY_WAIT)
        cipher.seal(LinkFrame.pack(LinkFrame.TEXT, text.encodeToByteArray())) { box -> socket.sendBytes(box) }
    }
}

private class HandshakeSocket(
    private val socket: BrowserSocket,
) : LinkSocket {
    override suspend fun readHello(): String {
        while (true) {
            val message = socket.incoming.receive()
            if (message is SocketMessage.Text) return message.text
        }
    }

    override suspend fun sendText(text: String) {
        socket.send(text)
    }

    override suspend fun sendBytes(bytes: ByteArray) {
        socket.sendBytes(bytes)
    }

    override suspend fun readReply(): ByteArray? {
        try {
            while (true) {
                when (val message = socket.incoming.receive()) {
                    is SocketMessage.Bytes -> return message.bytes
                    is SocketMessage.Text -> if (message.text == LinkCrypto.UNKNOWN_GRANT) return ByteArray(0)
                }
            }
        } catch (_: SocketClosed) {
            return null
        }
    }
}

/** Grants in the browser's site data, and in memory for a window that keeps none. */
private class PageGrants : LinkGrants {
    private var kept: LinkGrant? = null

    override suspend fun grantFor(pc: String): LinkGrant? = kept
        ?: readStored(key(pc))?.let { runCatching { RemoteJson.decode<LinkGrant>(it) }.getOrNull() }

    override suspend fun remember(pc: String, grant: LinkGrant) {
        kept = grant
        writeStored(key(pc), RemoteJson.encode(grant))
    }

    override suspend fun forget(pc: String) {
        kept = null
        removeStored(key(pc))
    }

    private fun key(pc: String): String = "remote-controller.grant.$pc"
}
