package com.remotecontroller.client.data.remote

import com.remotecontroller.shared.network.CertFingerprint
import kotlin.concurrent.Volatile

object ServerTrust {
    @Volatile
    var fingerprint: String = ""

    fun enforced(): Boolean = CertFingerprint.normalize(fingerprint).length == CertFingerprint.HEX_LENGTH

    fun accepts(sha256: ByteArray): Boolean {
        if (!enforced()) return true
        return CertFingerprint.matches(fingerprint, sha256)
    }
}
