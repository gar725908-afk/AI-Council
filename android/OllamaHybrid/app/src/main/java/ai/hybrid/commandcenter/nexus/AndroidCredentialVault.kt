package ai.hybrid.commandcenter.nexus

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ProviderSettings(
    val endpoint: String = "",
    val model: String = ""
)

/**
 * Stores provider credentials encrypted with an AES-GCM key held by Android Keystore.
 * Only ciphertext and a random IV are written to app-private SharedPreferences.
 */
class AndroidCredentialVault(context: Context) {
    private val appContext = context.applicationContext
    private val secrets = appContext.getSharedPreferences(SECRET_PREFS, Context.MODE_PRIVATE)

    fun put(secretId: String, secret: String) {
        require(secretId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Invalid secret identifier." }
        require(secret.isNotBlank()) { "Secret cannot be blank." }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val payload = ByteBuffer.allocate(1 + iv.size + encrypted.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(encrypted)
            .array()

        check(secrets.edit().putString(secretId, Base64.encodeToString(payload, Base64.NO_WRAP)).commit()) {
            "Unable to persist encrypted credential."
        }
    }

    fun get(secretId: String): String? {
        val encoded = secrets.getString(secretId, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.isNotEmpty()) { "Stored credential is empty." }
            val buffer = ByteBuffer.wrap(payload)
            val ivLength = buffer.get().toInt() and 0xFF
            require(ivLength in 12..16 && buffer.remaining() > ivLength) { "Stored credential is malformed." }
            val iv = ByteArray(ivLength)
            buffer.get(iv)
            val ciphertext = ByteArray(buffer.remaining())
            buffer.get(ciphertext)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(secretId: String) {
        secrets.edit().remove(secretId).apply()
    }

    fun clearAll() {
        secrets.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val specification = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(specification)
        return generator.generateKey()
    }

    companion object {
        const val PRIMARY_PROVIDER_KEY = "provider.primary.api-key"

        private const val SECRET_PREFS = "nexus_encrypted_credentials"
        private const val KEY_ALIAS = "nexus.provider.credentials.v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** Non-secret provider metadata. API keys never enter this store. */
class ProviderSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        SETTINGS_PREFS,
        Context.MODE_PRIVATE
    )

    fun load(): ProviderSettings = ProviderSettings(
        endpoint = preferences.getString(KEY_ENDPOINT, "") ?: "",
        model = preferences.getString(KEY_MODEL, "") ?: ""
    )

    fun save(settings: ProviderSettings) {
        preferences.edit()
            .putString(KEY_ENDPOINT, settings.endpoint.trim())
            .putString(KEY_MODEL, settings.model.trim())
            .apply()
    }

    companion object {
        private const val SETTINGS_PREFS = "nexus_provider_settings"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_MODEL = "model"
    }
}
