package ai.hybrid.commandcenter.testing

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * JVM stand-in for the "AndroidKeyStore" provider, which Robolectric lacks.
 * Keys live in memory for the test process only; the production code paths
 * (KeyStore.getInstance, KeyGenerator with KeyGenParameterSpec, AES/GCM)
 * run unchanged.
 */
object FakeAndroidKeyStore {
    internal val keys = ConcurrentHashMap<String, SecretKey>()

    @Synchronized
    fun install() {
        if (Security.getProvider(NAME) != null) return
        Security.addProvider(FakeProvider())
    }

    private const val NAME = "AndroidKeyStore"

    private class FakeProvider : Provider(NAME, 1.0, "In-memory AndroidKeyStore for JVM tests") {
        init {
            put("KeyStore.$NAME", FakeKeyStoreSpi::class.java.name)
            put("KeyGenerator.AES", FakeAesKeyGeneratorSpi::class.java.name)
        }
    }
}

class FakeKeyStoreSpi : KeyStoreSpi() {
    private val keys get() = FakeAndroidKeyStore.keys
    override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
    override fun engineGetCertificate(alias: String): Certificate? = null
    override fun engineGetCreationDate(alias: String): Date? = if (keys.containsKey(alias)) Date(0) else null
    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
        keys[alias] = key as SecretKey
    }
    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) {
        keys[alias] = SecretKeySpec(key, "AES")
    }
    override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit
    override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
    override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
    override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)
    override fun engineSize(): Int = keys.size
    override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)
    override fun engineIsCertificateEntry(alias: String): Boolean = false
    override fun engineGetCertificateAlias(cert: Certificate): String? = null
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
}

class FakeAesKeyGeneratorSpi : KeyGeneratorSpi() {
    private var alias: String? = null
    private var bits = 256
    private var random = SecureRandom()

    override fun engineInit(random: SecureRandom?) {
        random?.let { this.random = it }
    }

    override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
        val spec = params as? KeyGenParameterSpec
        alias = spec?.keystoreAlias
        spec?.keySize?.takeIf { it > 0 }?.let { bits = it }
        random?.let { this.random = it }
    }

    override fun engineInit(keysize: Int, random: SecureRandom?) {
        bits = keysize
        random?.let { this.random = it }
    }

    override fun engineGenerateKey(): SecretKey {
        val bytes = ByteArray(bits / 8).also { random.nextBytes(it) }
        val key = SecretKeySpec(bytes, "AES")
        alias?.let { FakeAndroidKeyStore.keys[it] = key }
        return key
    }
}
