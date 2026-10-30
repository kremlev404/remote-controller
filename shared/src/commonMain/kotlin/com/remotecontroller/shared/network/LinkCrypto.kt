package com.remotecontroller.shared.network

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * Link v2. One ephemeral P-256 exchange per socket, then AES-256-GCM.
 *
 * - The secret (access code, grant key, or nothing when the PC approves by hand) is the HKDF salt.
 *   It never goes on the socket.
 * - HKDF info is a hash of both public keys, so the keys are bound to this handshake.
 * - Each direction has its own key and a sequence number in the associated data:
 *   a replayed, reordered, dropped or reflected frame fails to decrypt.
 * - Order: hello (PC) → join (client) → confirm (client, sealed) → ready (PC, sealed).
 * - Both sides derive the same six-digit check code. The PC shows it next to Allow,
 *   the client shows it while it waits, so a man in the middle is visible.
 */
open class LinkRejected(message: String) : IllegalStateException(message)

class AccessCodeRejected : LinkRejected("The access code does not match this PC.")

class AccessCodeRequired : LinkRejected("This PC asks for its access code.")

class ApprovalDeclined : LinkRejected("The PC did not allow this connection.")

class GrantExpired : LinkRejected("This PC no longer remembers this device.")

object LinkAuth {
    /** The client salts the keys with the access code shown on the PC. */
    const val CODE = "code"

    /** No code. The PC owner presses Allow; the client then gets a grant for its next sockets. */
    const val APPROVE = "approve"
}

@Serializable
data class LinkHello(
    val v: Int = LinkCrypto.VERSION,
    val pub: String,
    val auth: String = LinkAuth.CODE,
)

@Serializable
data class LinkJoin(
    val pub: String,
    val name: String = "",
    val grant: String? = null,
)

@Serializable
data class LinkReady(
    val ready: Boolean = true,
    val grantId: String? = null,
    val grantKey: String? = null,
)

/** A grant lets the other sockets of an approved device skip the prompt. */
data class LinkGrant(
    val id: String,
    val key: String,
)

class LinkCipher internal constructor(
    private val sendKey: AES.GCM.Key,
    private val receiveKey: AES.GCM.Key,
    /** Six digits both sides can compare. */
    val checkCode: String,
) {
    private val sendLock = Mutex()
    private val receiveLock = Mutex()
    private var sent = 0L
    private var received = 0L

    /**
     * Seals [plain] with the next sequence number and hands it to [write] under one lock,
     * so the order on the wire is the order of the numbers.
     */
    suspend fun seal(plain: ByteArray, write: suspend (ByteArray) -> Unit) {
        sendLock.withLock {
            val box = sendKey.cipher().encrypt(plaintext = plain, associatedData = sequence(sent))
            sent++
            write(box)
        }
    }

    /** Frames must be opened in the order they arrived. A failure means the link is broken. */
    suspend fun open(box: ByteArray): ByteArray = receiveLock.withLock {
        val plain = receiveKey.cipher().decrypt(ciphertext = box, associatedData = sequence(received))
        received++
        plain
    }

    private fun sequence(value: Long): ByteArray = ByteArray(8) { index ->
        (value ushr (56 - 8 * index)).toByte()
    }
}

class LinkIdentity internal constructor(
    val publicDer: ByteArray,
    internal val privateKey: ECDH.PrivateKey,
)

/** What the client learned from the PC's hello, before it has keys. */
data class LinkOffer(
    val hello: LinkHello,
    val needsCode: Boolean,
)

object LinkCrypto {
    const val VERSION = 2
    const val CODE_LENGTH = 12
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** Plain text frame the PC sends instead of `ready` when it does not know the grant. */
    const val UNKNOWN_GRANT = """{"type":"unknown-grant"}"""

    /**
     * The client's first sealed frame. The PC sends nothing sealed until this opens,
     * so a peer that only connects learns nothing it could test codes against offline.
     */
    const val CONFIRM = "confirm"

    const val MAX_HANDSHAKE_TEXT = 4 * 1024

    private const val INFO_LABEL = "remote-controller/2"
    private const val KEY_BYTES = 32
    private const val CHECK_BYTES = 4

    private val provider = CryptographyProvider.Default

    fun normalizeCode(code: String): String =
        code.uppercase().filter { it in ALPHABET }

    fun isCode(code: String): Boolean = normalizeCode(code).length == CODE_LENGTH

    suspend fun generate(): LinkIdentity {
        val pair = provider.get(ECDH).keyPairGenerator(EC.Curve.P256).generateKey()
        val der = pair.publicKey.encodeToByteArray(EC.PublicKey.Format.DER)
        return LinkIdentity(der, pair.privateKey)
    }

    fun hello(identity: LinkIdentity, auth: String): String =
        RemoteJson.encode(LinkHello(pub = Base64.encode(identity.publicDer), auth = auth))

    fun readJoin(text: String): LinkJoin {
        if (text.length > MAX_HANDSHAKE_TEXT) error("join too large")
        return RemoteJson.decode<LinkJoin>(text)
    }

    /** PC side. [secret] is the code, the grant key, or empty when the owner approves by hand. */
    suspend fun accept(identity: LinkIdentity, join: LinkJoin, secret: ByteArray): LinkCipher {
        val peer = Base64.decode(join.pub)
        return agree(
            privateKey = identity.privateKey,
            serverDer = identity.publicDer,
            clientDer = peer,
            peerDer = peer,
            secret = secret,
            isServer = true,
        )
    }

    fun offer(helloText: String): LinkOffer {
        if (helloText.length > MAX_HANDSHAKE_TEXT) error("hello too large")
        val hello = RemoteJson.decode<LinkHello>(helloText)
        if (hello.v != VERSION) throw LinkRejected("Update the app: this PC speaks link version ${hello.v}.")
        return LinkOffer(hello, needsCode = hello.auth == LinkAuth.CODE)
    }

    /**
     * Client side. Pass the access code when the PC asks for one, otherwise a stored grant (or none).
     * Returns the join text to send and the cipher to open `ready` with.
     */
    suspend fun join(
        offer: LinkOffer,
        code: String,
        grant: LinkGrant?,
        deviceName: String,
    ): Pair<String, LinkCipher> {
        val secret = when {
            offer.needsCode -> {
                if (!isCode(code)) throw AccessCodeRequired()
                codeSecret(code)
            }
            grant != null -> grantSecret(grant.key)
            else -> ByteArray(0)
        }
        val mine = generate()
        val serverDer = Base64.decode(offer.hello.pub)
        val cipher = agree(
            privateKey = mine.privateKey,
            serverDer = serverDer,
            clientDer = mine.publicDer,
            peerDer = serverDer,
            secret = secret,
            isServer = false,
        )
        val join = LinkJoin(
            pub = Base64.encode(mine.publicDer),
            name = deviceName.take(48),
            grant = if (offer.needsCode) null else grant?.id,
        )
        return RemoteJson.encode(join) to cipher
    }

    fun codeSecret(code: String): ByteArray = normalizeCode(code).encodeToByteArray()

    fun grantSecret(key: String): ByteArray = Base64.decode(key)

    fun readyText(grant: LinkGrant?): String =
        RemoteJson.encode(LinkReady(grantId = grant?.id, grantKey = grant?.key))

    /** Client side. Throws when `ready` does not decrypt or does not parse. */
    suspend fun openReady(cipher: LinkCipher, box: ByteArray): LinkReady {
        val plain = cipher.open(box)
        val ready = RemoteJson.decode<LinkReady>(plain.decodeToString())
        if (!ready.ready) error("not ready")
        return ready
    }

    private suspend fun agree(
        privateKey: ECDH.PrivateKey,
        serverDer: ByteArray,
        clientDer: ByteArray,
        peerDer: ByteArray,
        secret: ByteArray,
        isServer: Boolean,
    ): LinkCipher {
        val ecdh = provider.get(ECDH)
        val peer = ecdh.publicKeyDecoder(EC.Curve.P256)
            .decodeFromByteArray(EC.PublicKey.Format.DER, peerDer)
        val shared = privateKey.sharedSecretGenerator().generateSharedSecretToByteArray(peer)
        val transcript = provider.get(SHA256).hasher().hash(
            INFO_LABEL.encodeToByteArray() + lengthPrefixed(serverDer) + lengthPrefixed(clientDer),
        )
        val material = provider.get(HKDF).secretDerivation(
            digest = SHA256,
            outputSize = ((KEY_BYTES * 2 + CHECK_BYTES) * 8).bits,
            salt = secret,
            info = INFO_LABEL.encodeToByteArray() + transcript,
        ).deriveSecretToByteArray(shared)
        val serverToClient = aesKey(material.copyOfRange(0, KEY_BYTES))
        val clientToServer = aesKey(material.copyOfRange(KEY_BYTES, KEY_BYTES * 2))
        val check = material.copyOfRange(KEY_BYTES * 2, KEY_BYTES * 2 + CHECK_BYTES)
        return if (isServer) {
            LinkCipher(sendKey = serverToClient, receiveKey = clientToServer, checkCode = checkDigits(check))
        } else {
            LinkCipher(sendKey = clientToServer, receiveKey = serverToClient, checkCode = checkDigits(check))
        }
    }

    private suspend fun aesKey(raw: ByteArray): AES.GCM.Key =
        provider.get(AES.GCM).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, raw)

    private fun lengthPrefixed(bytes: ByteArray): ByteArray =
        byteArrayOf((bytes.size ushr 8).toByte(), bytes.size.toByte()) + bytes

    private fun checkDigits(bytes: ByteArray): String {
        var value = 0L
        for (byte in bytes) value = (value shl 8) or (byte.toLong() and 0xff)
        val digits = (value % 1_000_000).toString().padStart(6, '0')
        return digits.substring(0, 3) + " " + digits.substring(3)
    }
}

object LinkFrame {
    const val TEXT: Byte = 1
    const val BYTES: Byte = 2

    fun pack(kind: Byte, payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + 1)
        out[0] = kind
        payload.copyInto(out, destinationOffset = 1)
        return out
    }

    fun unpack(plain: ByteArray): Pair<Byte, ByteArray> {
        if (plain.isEmpty()) error("empty frame")
        return plain[0] to plain.copyOfRange(1, plain.size)
    }
}

/** Joins continuation frames. Drops the message and fails once it grows past [limit]. */
class ChunkBuffer(
    private val limit: Int = MAX_MESSAGE_BYTES,
) {
    private var parts = ArrayList<ByteArray>()
    private var size = 0

    fun push(data: ByteArray, fin: Boolean): ByteArray? {
        size += data.size
        if (size > limit) {
            parts = ArrayList()
            size = 0
            throw IllegalStateException("message too large")
        }
        if (data.isNotEmpty()) parts.add(data.copyOf())
        if (!fin) return null
        val out = ByteArray(size)
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        parts = ArrayList()
        size = 0
        return out
    }

    companion object {
        const val MAX_MESSAGE_BYTES = 16 * 1024 * 1024
    }
}
