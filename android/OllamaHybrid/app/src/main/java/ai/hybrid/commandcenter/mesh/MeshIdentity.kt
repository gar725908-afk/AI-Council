package ai.hybrid.commandcenter.mesh

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.net.Socket
import java.security.Principal
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * Who this phone is in the private mesh: an EC P-256 key pair whose private
 * half never leaves the device, and the self-signed certificate TLS presents.
 * The Device ID and the fingerprint are derived from the public key, so a
 * rename never changes identity.
 */
interface MeshIdentity {
    val certificate: X509Certificate
    fun privateKey(): PrivateKey

    /**
     * One fixed key for both TLS roles. Stock key managers pick a key by the
     * peer's CA list; our self-signed peers send none, which would make them
     * send no certificate at all — so the choice is made explicitly here.
     */
    fun keyManagers(): Array<KeyManager> = arrayOf(SingleKeyManager(privateKey(), arrayOf(certificate)))

    val fingerprint: String get() = MeshFingerprint.of(certificate)
    val deviceId: String get() = MeshFingerprint.deviceId(fingerprint)
}

internal class SingleKeyManager(private val key: PrivateKey, private val chain: Array<X509Certificate>) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain
    override fun getPrivateKey(alias: String?): PrivateKey = key

    private companion object {
        const val ALIAS = "nexus-mesh"
    }
}

object MeshFingerprint {
    /** SHA-256 over the SubjectPublicKeyInfo, lowercase hex. Pinning the key, not the certificate. */
    fun of(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded).joinToString("") { "%02x".format(it) }

    /** Short, human-friendly id: «NX-3F9A-C21B». */
    fun deviceId(fingerprint: String): String =
        "NX-" + fingerprint.take(4).uppercase(Locale.ROOT) + "-" + fingerprint.substring(4, 8).uppercase(Locale.ROOT)

    /** Fingerprint grouped for the eye: «3f9a c21b 77e0 …». */
    fun pretty(fingerprint: String, groups: Int = 8): String = fingerprint.chunked(4).take(groups).joinToString(" ")
}

/** Production identity: the key lives in Android Keystore (hardware-backed where the phone has it). */
class AndroidKeystoreIdentity private constructor(private val alias: String) : MeshIdentity {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override val certificate: X509Certificate
        get() = keyStore.getCertificate(alias) as X509Certificate

    override fun privateKey(): PrivateKey = keyStore.getKey(alias, null) as PrivateKey

    /** True when the private key is inside secure hardware (TEE/StrongBox). */
    fun hardwareBacked(): Boolean = runCatching {
        val key = keyStore.getKey(alias, null) as PrivateKey
        val factory = java.security.KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
        val info = factory.getKeySpec(key, android.security.keystore.KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        } else {
            @Suppress("DEPRECATION")
            info.isInsideSecureHardware
        }
    }.getOrDefault(false)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "nexus-mesh-identity-v1"

        fun loadOrCreate(@Suppress("UNUSED_PARAMETER") context: Context): AndroidKeystoreIdentity {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!store.containsAlias(ALIAS)) {
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    // TLS signs handshakes; Conscrypt may ask for raw or SHA-2 digests.
                    .setDigests(
                        KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512
                    )
                    .setCertificateSubject(X500Principal("CN=NEXUS Mesh"))
                    .setCertificateSerialNumber(BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE))
                    .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
                    .setCertificateNotAfter(Date(System.currentTimeMillis() + 30L * 365 * 86_400_000L))
                    .build()
                generator.initialize(spec)
                generator.generateKeyPair()
            }
            return AndroidKeystoreIdentity(ALIAS)
        }

        /** Forget the identity (owner's «Сбросить узел»): every peer must pair again. */
        fun destroy() {
            runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
        }
    }
}

/**
 * Software identity for JVM tests and the desktop peer. Builds a minimal
 * self-signed X.509 v3 certificate itself, so no extra crypto library is needed.
 */
class SoftwareIdentity(private val keyPair: KeyPair, override val certificate: X509Certificate) : MeshIdentity {
    override fun privateKey(): PrivateKey = keyPair.private

    companion object {
        fun generate(commonName: String = "NEXUS Mesh"): SoftwareIdentity {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            val pair = generator.generateKeyPair()
            return SoftwareIdentity(pair, MiniCertificate.selfSigned(pair, commonName))
        }
    }
}

/** DER encoder for exactly one thing: a self-signed ECDSA P-256 certificate. */
internal object MiniCertificate {
    private val ECDSA_SHA256 = byteArrayOf(0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)
    private val COMMON_NAME = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)

    fun selfSigned(pair: KeyPair, commonName: String): X509Certificate {
        val algorithm = seq(ECDSA_SHA256)
        val name = seq(set(seq(COMMON_NAME + tlv(0x0C, commonName.toByteArray(Charsets.UTF_8)))))
        val serial = BigInteger(120, SecureRandom()).add(BigInteger.ONE).toByteArray()
        val validity = seq(utcTime(Date(System.currentTimeMillis() - 86_400_000L)) + tlv(0x17, "491231235959Z".toByteArray()))
        val tbs = seq(
            tlv(0xA0, tlv(0x02, byteArrayOf(2))) + tlv(0x02, serial) + algorithm + name + validity + name + pair.public.encoded
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(tbs)
            sign()
        }
        val der = seq(tbs + algorithm + tlv(0x03, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun utcTime(date: Date): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, format.format(date).toByteArray())
    }

    private fun seq(content: ByteArray) = tlv(0x30, content)
    private fun set(content: ByteArray) = tlv(0x31, content)

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            else -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }
}
