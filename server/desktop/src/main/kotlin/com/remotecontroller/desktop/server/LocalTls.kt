package com.remotecontroller.desktop.server

import com.remotecontroller.shared.network.CertFingerprint
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.GeneralSubtree
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.NameConstraints
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * A certificate authority that lives only in this user's app folder.
 * The leaf certificate is minted for the addresses this process is about to bind.
 *
 * The CA carries critical name constraints: it can only vouch for private IPv4 ranges,
 * localhost and .local names. "Trust this computer" adds it to the system store, so a
 * stolen CA key must not be able to impersonate any public site.
 */
internal class LocalTls private constructor(
    private val caPrivate: PrivateKey,
    private val caCertificate: X509Certificate,
    private val password: CharArray,
    val fingerprint: String,
    val caCertificateFile: File,
) {
    fun passwordCopy(): CharArray = password.copyOf()

    fun serverKeyStore(hosts: Collection<String>): KeyStore {
        val names = hosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val pair = newKeyPair()
        val leaf = sign(
            issuerKey = caPrivate,
            issuer = X500Name(caCertificate.subjectX500Principal.name),
            subject = X500Name("CN=Remote Controller"),
            publicKey = pair.public,
            days = 90,
            ca = false,
            hosts = names,
        )
        return KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(SERVER_ALIAS, pair.private, password, arrayOf(leaf, caCertificate))
        }
    }

    companion object {
        const val SERVER_ALIAS = "server"
        private const val CA_ALIAS = "ca"

        fun open(): LocalTls {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
            val directory = AppFiles.directory
            val storeFile = File(directory, "local-ca.p12")
            val passwordFile = File(directory, "local-ca.secret")
            val certificateFile = File(directory, "local-ca.crt")
            val loaded = load(storeFile, passwordFile, certificateFile)
            if (loaded != null) return loaded
            return create(storeFile, passwordFile, certificateFile)
        }

        private fun load(storeFile: File, passwordFile: File, certificateFile: File): LocalTls? {
            if (!storeFile.isFile || !passwordFile.isFile) return null
            return runCatching {
                val password = passwordFile.readText().trim().toCharArray()
                val store = KeyStore.getInstance("PKCS12")
                storeFile.inputStream().use { store.load(it, password) }
                val key = store.getKey(CA_ALIAS, password) as PrivateKey
                val certificate = store.getCertificate(CA_ALIAS) as X509Certificate
                // A CA from an older version has no name constraints: make a new one.
                if (certificate.getExtensionValue(Extension.nameConstraints.id) == null) return null
                writeCertificate(certificateFile, certificate)
                LocalTls(key, certificate, password, fingerprintOf(certificate), certificateFile)
            }.getOrNull()
        }

        private fun create(storeFile: File, passwordFile: File, certificateFile: File): LocalTls {
            val pair = newKeyPair()
            val certificate = sign(
                issuerKey = pair.private,
                issuer = X500Name("CN=Remote Controller Local CA (LAN only)"),
                subject = X500Name("CN=Remote Controller Local CA (LAN only)"),
                publicKey = pair.public,
                days = 3650,
                ca = true,
                hosts = emptyList(),
            )
            val password = ByteArray(24).also { SecureRandom().nextBytes(it) }
                .let { Base64.getEncoder().withoutPadding().encodeToString(it) }
                .toCharArray()
            val store = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry(CA_ALIAS, pair.private, password, arrayOf(certificate))
            }
            storeFile.outputStream().use { store.store(it, password) }
            passwordFile.writeText(String(password))
            AppFiles.restrict(storeFile)
            AppFiles.restrict(passwordFile)
            writeCertificate(certificateFile, certificate)
            return LocalTls(pair.private, certificate, password, fingerprintOf(certificate), certificateFile)
        }

        private fun writeCertificate(file: File, certificate: X509Certificate) {
            val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded)
            file.writeText("-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n")
        }

        private fun fingerprintOf(certificate: X509Certificate): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            return CertFingerprint.format(digest)
        }

        private fun newKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            }.generateKeyPair()

        private fun sign(
            issuerKey: PrivateKey,
            issuer: X500Name,
            subject: X500Name,
            publicKey: java.security.PublicKey,
            days: Long,
            ca: Boolean,
            hosts: List<String>,
        ): X509Certificate {
            val now = Date()
            val until = Date(now.time + TimeUnit.DAYS.toMillis(days))
            val serial = BigInteger(64, SecureRandom()).abs().let { if (it.signum() == 0) BigInteger.ONE else it }
            val builder = JcaX509v3CertificateBuilder(issuer, serial, now, until, subject, publicKey)
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
            if (ca) {
                builder.addExtension(
                    Extension.keyUsage,
                    true,
                    KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign),
                )
                builder.addExtension(Extension.nameConstraints, true, localNamesOnly())
            } else {
                builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
                builder.addExtension(
                    Extension.extendedKeyUsage,
                    false,
                    ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
                )
                if (hosts.isNotEmpty()) {
                    val names = hosts.map { host ->
                        if (host.any { it.isLetter() }) {
                            GeneralName(GeneralName.dNSName, host)
                        } else {
                            GeneralName(GeneralName.iPAddress, host)
                        }
                    }
                    builder.addExtension(
                        Extension.subjectAlternativeName,
                        false,
                        GeneralNames(names.toTypedArray()),
                    )
                }
            }
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(issuerKey)
            val holder = builder.build(signer)
            return JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME).getCertificate(holder)
        }

        private fun localNamesOnly(): NameConstraints {
            val permitted = PERMITTED_IPS.map { GeneralSubtree(GeneralName(GeneralName.iPAddress, it)) } +
                PERMITTED_DNS.map { GeneralSubtree(GeneralName(GeneralName.dNSName, it)) }
            return NameConstraints(permitted.toTypedArray(), null)
        }

        private val PERMITTED_IPS = listOf(
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "169.254.0.0/16",
            "127.0.0.0/8",
        )
        private val PERMITTED_DNS = listOf("localhost", "local")
    }
}
