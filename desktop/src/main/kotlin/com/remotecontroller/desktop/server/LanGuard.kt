package com.remotecontroller.desktop.server

import com.remotecontroller.shared.network.LinkCipher
import com.remotecontroller.shared.network.LinkCrypto
import com.remotecontroller.shared.network.LinkFrame
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

internal object PairingCode {
    fun generate(): String {
        val alphabet = LinkCrypto.ALPHABET
        val random = SecureRandom()
        val raw = CharArray(LinkCrypto.CODE_LENGTH) { alphabet[random.nextInt(alphabet.length)] }
        return raw.concatToString(0, 4) + "-" + raw.concatToString(4, 8) + "-" + raw.concatToString(8, 12)
    }
}

/**
 * The socket is bound to a private address. This drops a peer that still arrives
 * from a public address, for example through a port forward. Relay links skip it:
 * they come in over the desktop's own outgoing connection.
 */
internal object LanGuard {
    fun trusted(address: String): Boolean {
        var host = address.trim().removePrefix("/")
        val zone = host.indexOf('%')
        if (zone >= 0) host = host.substring(0, zone)
        if (host.startsWith("::ffff:")) host = host.removePrefix("::ffff:")
        if (host == "127.0.0.1" || host == "::1" || host.equals("localhost", ignoreCase = true)) return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false
        if (parts[2].toIntOrNull() == null || parts[3].toIntOrNull() == null) return false
        if (first == 10) return true
        if (first == 192 && second == 168) return true
        if (first == 172 && second in 16..31) return true
        if (first == 169 && second == 254) return true
        return false
    }

    /**
     * Browsers send Origin on websockets; native apps do not. A page from any other site
     * (for example one open in this computer's browser, aimed at 127.0.0.1) is refused.
     */
    fun sameOrigin(origin: String?, host: String?): Boolean {
        if (origin == null) return true
        if (host.isNullOrBlank()) return false
        val authority = origin.trim().removePrefix("https://")
        if (authority == origin.trim()) return false
        return authority.trimEnd('/').equals(host.trim(), ignoreCase = true)
    }
}

internal object SecureFrames {
    private val links = ConcurrentHashMap<String, Bound>()

    fun bind(endpoint: String, cipher: LinkCipher, grantId: String?) {
        links[endpoint] = Bound(cipher, grantId)
    }

    fun clear(endpoint: String) {
        links.remove(endpoint)
    }

    fun grantOf(endpoint: String): String? = links[endpoint]?.grantId

    /** Endpoints that came in by approval or a grant, not by the access code. */
    fun approvedEndpoints(): List<String> = links.filterValues { it.grantId != null }.keys.toList()

    suspend fun sendText(endpoint: String, text: String) {
        send(endpoint, LinkFrame.TEXT, text.encodeToByteArray())
    }

    suspend fun sendBinary(endpoint: String, payload: ByteArray) {
        send(endpoint, LinkFrame.BYTES, payload)
    }

    suspend fun openText(endpoint: String, box: ByteArray): String {
        val (kind, body) = open(endpoint, box)
        if (kind != LinkFrame.TEXT) error("unexpected frame")
        return body.decodeToString()
    }

    private suspend fun send(endpoint: String, kind: Byte, payload: ByteArray) {
        val cipher = links[endpoint]?.cipher ?: error("no link")
        cipher.seal(LinkFrame.pack(kind, payload)) { box -> SocketFrames.sendBinary(endpoint, box) }
    }

    private suspend fun open(endpoint: String, box: ByteArray): Pair<Byte, ByteArray> {
        val cipher = links[endpoint]?.cipher ?: error("no link")
        return LinkFrame.unpack(cipher.open(box))
    }

    private class Bound(
        val cipher: LinkCipher,
        val grantId: String?,
    )
}
