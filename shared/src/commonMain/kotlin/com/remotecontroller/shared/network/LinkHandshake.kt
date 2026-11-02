package com.remotecontroller.shared.network

import kotlinx.coroutines.CancellationException

/** What each PC handed this device, so it is not asked again. */
interface LinkGrants {
    suspend fun grantFor(pc: String): LinkGrant?

    suspend fun remember(pc: String, grant: LinkGrant)

    suspend fun forget(pc: String)
}

/** One socket to the PC as the handshake sees it. Each client wraps its own kind. */
interface LinkSocket {
    /** The PC's hello. Throws when the socket closes first. */
    suspend fun readHello(): String

    suspend fun sendText(text: String)

    suspend fun sendBytes(bytes: ByteArray)

    /** The sealed `ready`, an empty array for "unknown grant", or null when the PC closed the socket. */
    suspend fun readReply(): ByteArray?
}

/**
 * hello → join → confirm → ready, the client's side, for every client. A device the PC remembers
 * gets in with its grant. Otherwise the code answers at once, or the socket waits for Allow on the
 * PC, and [onApproval] gets the check code to show until the wait ends with null. Either way the PC
 * hands back a grant, kept under the PC's id so a new address changes nothing.
 *
 * [fallbackPc] names a PC too old to send its own id. Throws a [LinkRejected] when the PC refuses,
 * and anything else when the socket fails, which says nothing about this device.
 */
suspend fun LinkSocket.openLink(
    fallbackPc: String,
    code: String,
    grants: LinkGrants,
    deviceName: String,
    onApproval: (checkCode: String?) -> Unit,
): LinkCipher {
    val offer = LinkCrypto.offer(readHello())
    val pc = offer.hello.pc.ifBlank { fallbackPc }
    val grant = grants.grantFor(pc)
    val (join, cipher) = LinkCrypto.join(offer, code, grant, deviceName)
    sendText(join)
    cipher.seal(LinkCrypto.CONFIRM.encodeToByteArray()) { box -> sendBytes(box) }
    val waiting = !offer.needsCode && grant == null
    if (waiting) onApproval(cipher.checkCode)
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
        if (id != null && key != null) {
            val kept = LinkGrant(id, key)
            if (kept != grant) grants.remember(pc, kept)
        }
        return cipher
    } finally {
        if (waiting) onApproval(null)
    }
}

private fun rejection(offer: LinkOffer, grant: LinkGrant?, waiting: Boolean): Throwable = when {
    grant != null -> GrantExpired()
    offer.needsCode -> AccessCodeRejected()
    waiting -> ApprovalDeclined()
    else -> GrantExpired()
}
