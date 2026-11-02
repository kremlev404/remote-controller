package com.remotecontroller.shared.network

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CODE = "ABCD-EFGH-JKLM"
private const val PC = "pc-1"
private val KNOWN = LinkGrant("known-id", "c2VjcmV0LXNlY3JldC1zZWNyZXQtc2VjcmV0LTEyMzQ=")
private val ISSUED = LinkGrant("issued-id", "bmV3LXNlY3JldC1uZXctc2VjcmV0LW5ldy1zZWNyZXQ=")

/** Runs the PC's side with the real crypto, the way LinkGate does, in place of a socket. */
private class FakePc(
    private val auth: String,
    private val expectedSecret: ByteArray,
    private val pcId: String = PC,
    private val knowsGrant: Boolean = true,
    private val approves: Boolean = true,
    private val dropsAfterConfirm: Boolean = false,
    private val garblesReady: Boolean = false,
    private val issues: LinkGrant? = ISSUED,
) : LinkSocket {
    var joined: LinkJoin? = null
    var pcCipher: LinkCipher? = null
    private var confirmed = false
    private lateinit var identity: LinkIdentity

    override suspend fun readHello(): String {
        identity = LinkCrypto.generate()
        return LinkCrypto.hello(identity, auth, pcId)
    }

    override suspend fun sendText(text: String) {
        joined = LinkCrypto.readJoin(text)
    }

    override suspend fun sendBytes(bytes: ByteArray) {
        val join = assertNotNull(joined, "confirm came before join")
        val cipher = LinkCrypto.accept(identity, join, expectedSecret)
        confirmed = runCatching { cipher.open(bytes).decodeToString() == LinkCrypto.CONFIRM }.getOrDefault(false)
        pcCipher = cipher
    }

    override suspend fun readReply(): ByteArray? {
        if (joined?.grant != null && !knowsGrant) return ByteArray(0)
        if (dropsAfterConfirm || !confirmed || !approves) return null
        if (garblesReady) return ByteArray(32) { 7 }
        var box = ByteArray(0)
        assertNotNull(pcCipher).seal(LinkCrypto.readyText(issues).encodeToByteArray()) { box = it }
        return box
    }
}

private class MemoryGrants(vararg kept: Pair<String, LinkGrant>) : LinkGrants {
    val grants = kept.toMap().toMutableMap()
    var writes = 0

    override suspend fun grantFor(pc: String): LinkGrant? = grants[pc]

    override suspend fun remember(pc: String, grant: LinkGrant) {
        writes++
        grants[pc] = grant
    }

    override suspend fun forget(pc: String) {
        grants.remove(pc)
    }
}

class LinkHandshakeTest {
    private val approvals = ArrayList<String?>()

    private suspend fun FakePc.open(grants: LinkGrants, code: String = CODE, fallback: String = PC): LinkCipher =
        openLink(fallback, code, grants, "Test device", approvals::add)

    private fun codePc(secret: String = CODE, issues: LinkGrant? = ISSUED) =
        FakePc(LinkAuth.CODE, LinkCrypto.codeSecret(secret), issues = issues)

    @Test
    fun rightCodeOpensTheLinkAndKeepsTheGrant() = runBlocking {
        val pc = codePc()
        val grants = MemoryGrants()

        val cipher = pc.open(grants)

        assertEquals(ISSUED, grants.grants[PC])
        assertEquals("Test device", pc.joined?.name)
        assertTrue(approvals.isEmpty())

        var box = ByteArray(0)
        cipher.seal("to the pc".encodeToByteArray()) { box = it }
        assertContentEquals("to the pc".encodeToByteArray(), assertNotNull(pc.pcCipher).open(box))
    }

    @Test
    fun wrongCodeIsRejectedAndKeepsNothing() = runBlocking {
        val grants = MemoryGrants()

        assertFailsWith<AccessCodeRejected> { codePc(secret = "ZZZZ-ZZZZ-ZZZZ").open(grants) }

        assertTrue(grants.grants.isEmpty())
    }

    @Test
    fun missingCodeAsksForItBeforeSendingAnything() = runBlocking {
        val pc = codePc()

        assertFailsWith<AccessCodeRequired> { pc.open(MemoryGrants(), code = "") }

        assertNull(pc.joined)
    }

    @Test
    fun aKnownGrantGetsInWithoutCodeAndIsNotWrittenAgain() = runBlocking {
        val pc = FakePc(LinkAuth.CODE, LinkCrypto.grantSecret(KNOWN.key), issues = KNOWN)
        val grants = MemoryGrants(PC to KNOWN)

        pc.open(grants, code = "")

        assertEquals(KNOWN.id, pc.joined?.grant)
        assertEquals(0, grants.writes)
    }

    @Test
    fun aGrantWithANewKeyReplacesTheOldOne() = runBlocking {
        val changed = KNOWN.copy(key = ISSUED.key)
        val pc = FakePc(LinkAuth.CODE, LinkCrypto.grantSecret(KNOWN.key), issues = changed)
        val grants = MemoryGrants(PC to KNOWN)

        pc.open(grants, code = "")

        assertEquals(changed, grants.grants[PC])
    }

    @Test
    fun aGrantThePcForgotIsDropped() = runBlocking {
        val pc = FakePc(LinkAuth.CODE, ByteArray(0), knowsGrant = false)
        val grants = MemoryGrants(PC to KNOWN)

        assertFailsWith<GrantExpired> { pc.open(grants) }

        assertFalse(PC in grants.grants)
    }

    @Test
    fun aDroppedSocketKeepsTheGrant() = runBlocking {
        val pc = FakePc(LinkAuth.CODE, LinkCrypto.grantSecret(KNOWN.key), dropsAfterConfirm = true)
        val grants = MemoryGrants(PC to KNOWN)

        val failure = assertFailsWith<IllegalStateException> { pc.open(grants) }

        assertFalse(failure is LinkRejected)
        assertEquals(KNOWN, grants.grants[PC])
    }

    @Test
    fun aReadyThatDoesNotOpenDropsTheGrant() = runBlocking {
        val pc = FakePc(LinkAuth.CODE, LinkCrypto.grantSecret(KNOWN.key), garblesReady = true)
        val grants = MemoryGrants(PC to KNOWN)

        assertFailsWith<GrantExpired> { pc.open(grants) }

        assertFalse(PC in grants.grants)
    }

    @Test
    fun approvalShowsTheCheckCodeThenClearsIt() = runBlocking {
        val pc = FakePc(LinkAuth.APPROVE, ByteArray(0))

        val cipher = pc.open(MemoryGrants(), code = "")

        assertEquals(listOf(cipher.checkCode, null), approvals)
        assertEquals(assertNotNull(pc.pcCipher).checkCode, cipher.checkCode)
    }

    @Test
    fun aDeclinedApprovalIsRejectedAndClearsTheCheckCode() = runBlocking {
        val pc = FakePc(LinkAuth.APPROVE, ByteArray(0), approves = false)

        assertFailsWith<ApprovalDeclined> { pc.open(MemoryGrants(), code = "") }

        assertEquals(2, approvals.size)
        assertNull(approvals.last())
    }

    @Test
    fun aPcWithoutAnIdFallsBackToTheAddress() = runBlocking {
        val pc = FakePc(LinkAuth.CODE, LinkCrypto.codeSecret(CODE), pcId = "")
        val grants = MemoryGrants()

        pc.open(grants, fallback = "192.168.1.5:8765")

        assertEquals(ISSUED, grants.grants["192.168.1.5:8765"])
    }

    @Test
    fun anOlderPcVersionIsRefused() = runBlocking {
        val socket = object : LinkSocket {
            override suspend fun readHello(): String = """{"v":1,"pub":"","auth":"code","pc":"old"}"""

            override suspend fun sendText(text: String) = error("nothing may be sent")

            override suspend fun sendBytes(bytes: ByteArray) = error("nothing may be sent")

            override suspend fun readReply(): ByteArray? = null
        }

        assertFailsWith<LinkRejected> { socket.openLink(PC, CODE, MemoryGrants(), "", approvals::add) }
        Unit
    }
}
