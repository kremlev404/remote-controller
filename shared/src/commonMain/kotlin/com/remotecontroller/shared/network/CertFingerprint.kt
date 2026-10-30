package com.remotecontroller.shared.network

object CertFingerprint {
    const val HEX_LENGTH = 64

    fun normalize(value: String): String =
        value.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }

    fun format(sha256: ByteArray): String =
        sha256.joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }.chunked(4).joinToString("-").uppercase()

    fun display(value: String): String {
        val hex = normalize(value)
        if (hex.length != HEX_LENGTH) return value.trim()
        return hex.chunked(4).joinToString("-").uppercase()
    }

    fun matches(expected: String, sha256: ByteArray): Boolean {
        val wanted = normalize(expected)
        if (wanted.length != HEX_LENGTH) return false
        return wanted == normalize(format(sha256))
    }
}
