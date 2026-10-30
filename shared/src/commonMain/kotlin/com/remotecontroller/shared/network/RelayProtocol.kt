package com.remotecontroller.shared.network

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Rendezvous relay, the TeamViewer-style path for a PC outside the LAN.
 *
 * The PC keeps one outgoing websocket to the relay and proves it owns a long-term P-256 key;
 * its ID is derived from that key. A browser asks the relay for that ID; the relay tells the PC,
 * the PC opens a second outgoing socket for that session, and the relay pipes the two.
 * The link handshake and encryption run end to end through the pipe: the relay sees ciphertext.
 */
object RelayProtocol {
    const val HOST_PATH = "/relay/host"
    const val ACCEPT_PATH = "/relay/accept"
    const val CONNECT_PATH = "/relay/connect"
    const val SIGN_LABEL = "remote-controller-relay/1:"

    /** Close code the relay sends to a browser when the PC is not online. */
    const val CLOSE_OFFLINE: Short = 4004

    const val ID_DIGITS = 9

    /** Short names in relay URLs → the PC's own socket paths. */
    val channels: Map<String, String> = mapOf(
        "ws" to RemoteProtocol.WS_PATH,
        "stream" to RemoteProtocol.STREAM_PATH,
        "web" to RemoteProtocol.CLIP_WS_PATH,
    )

    fun channelName(path: String): String? = channels.entries.firstOrNull { it.value == path }?.key

    suspend fun deviceId(publicKeyDer: ByteArray): String {
        val hash = CryptographyProvider.Default.get(SHA256).hasher().hash(publicKeyDer)
        var value = 0L
        for (index in 0 until 8) value = (value shl 8) or (hash[index].toLong() and 0xff)
        val id = (value ushr 1) % 1_000_000_000L
        return id.toString().padStart(ID_DIGITS, '0')
    }

    fun normalizeId(text: String): String = text.filter { it.isDigit() }

    fun displayId(id: String): String = normalizeId(id).chunked(3).joinToString(" ")
}

@Serializable
sealed interface RelayMessage {
    @Serializable
    @SerialName("challenge")
    data class Challenge(val nonce: String) : RelayMessage

    /** [sig]: ECDSA P-256 / SHA-256 (DER) over [RelayProtocol.SIGN_LABEL] + nonce bytes. */
    @Serializable
    @SerialName("register")
    data class Register(val pub: String, val sig: String) : RelayMessage

    @Serializable
    @SerialName("registered")
    data class Registered(val id: String) : RelayMessage

    @Serializable
    @SerialName("refused")
    data class Refused(val reason: String) : RelayMessage

    /** Relay → PC: open `ACCEPT_PATH/session` and serve [channel] there. */
    @Serializable
    @SerialName("open")
    data class Open(val session: String, val channel: String, val peer: String) : RelayMessage
}
