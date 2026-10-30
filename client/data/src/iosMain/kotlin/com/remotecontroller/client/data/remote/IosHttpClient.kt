package com.remotecontroller.client.data.remote

import com.remotecontroller.shared.network.RemoteJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CC_SHA256
import platform.CoreFoundation.CFArrayAppendValue
import platform.CoreFoundation.CFArrayCreateMutable
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.Security.SecCertificateCopyData
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecTrustEvaluateWithError
import platform.Security.SecTrustGetCertificateAtIndex
import platform.Security.SecTrustGetCertificateCount
import platform.Security.SecTrustSetAnchorCertificates
import platform.Security.SecTrustSetAnchorCertificatesOnly

private const val CONNECT_TIMEOUT_MS = 5_000L
private const val REQUEST_TIMEOUT_MS = 8_000L

@OptIn(ExperimentalForeignApi::class)
fun remoteHttpClient(): HttpClient = HttpClient(Darwin) {
    expectSuccess = true
    install(ContentNegotiation) { json(RemoteJson.json) }
    install(WebSockets) {
        pingInterval = 20.seconds
    }
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
        socketTimeoutMillis = REQUEST_TIMEOUT_MS
    }
    engine {
        handleChallenge { _, _, challenge, completionHandler ->
            val trust = challenge.protectionSpace.serverTrust
            if (challenge.protectionSpace.authenticationMethod != NSURLAuthenticationMethodServerTrust || trust == null) {
                completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
            } else if (trusts(trust)) {
                completionHandler(
                    NSURLSessionAuthChallengeUseCredential,
                    NSURLCredential.credentialForTrust(trust),
                )
            } else {
                completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            }
        }
        configureRequest {
            setAllowsCellularAccess(true)
            setAllowsConstrainedNetworkAccess(true)
            setAllowsExpensiveNetworkAccess(true)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun trusts(trust: platform.Security.SecTrustRef): Boolean {
    if (!ServerTrust.enforced()) return true
    val count = SecTrustGetCertificateCount(trust).toInt()
    var matched: platform.CoreFoundation.CFDataRef? = null
    for (index in 0 until count) {
        val certificate = SecTrustGetCertificateAtIndex(trust, index.toLong()) ?: continue
        val data = SecCertificateCopyData(certificate) ?: continue
        val matches = ServerTrust.accepts(sha256(data))
        if (matches) {
            matched = data
            break
        }
        CFRelease(data)
    }
    val anchorData = matched ?: return false
    val anchor = SecCertificateCreateWithData(null, anchorData)
    CFRelease(anchorData)
    if (anchor == null) return false
    val anchors = CFArrayCreateMutable(null, 1, null) ?: return false
    CFArrayAppendValue(anchors, anchor)
    SecTrustSetAnchorCertificates(trust, anchors)
    SecTrustSetAnchorCertificatesOnly(trust, true)
    CFRelease(anchors)
    return memScoped {
        val error = alloc<CFErrorRefVar>()
        val trusted = SecTrustEvaluateWithError(trust, error.ptr)
        val failure = error.value
        if (failure != null) CFRelease(failure)
        trusted
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun sha256(data: platform.CoreFoundation.CFDataRef): ByteArray {
    val length = CFDataGetLength(data).toInt()
    val pointer = CFDataGetBytePtr(data) ?: return ByteArray(0)
    val digest = UByteArray(32)
    digest.usePinned { pinned ->
        CC_SHA256(pointer, length.toUInt(), pinned.addressOf(0).reinterpret())
    }
    return ByteArray(digest.size) { index -> digest[index].toByte() }
}
