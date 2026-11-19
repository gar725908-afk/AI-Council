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
    val model: String = "",
    val providerKind: String = "OPENAI_COMPATIBLE"
)

/**
 * Stores provider credentials encrypted with an AES-GCM key held by Android Keystore.
 * Only ciphertext and a random IV are written to app-private SharedPreferences.
 */
class AndroidCredentialVault(context: Context) {
    private val appContext = context.applicationContext
    private val secrets = appContext.getSharedPreferences(SECRET_PREFS, Context.MODE_PRIVATE)

    fun put(secretId: String, secret: String) {
        requireValidSecretId(secretId)
        require(secret.isNotBlank()) { "Secret cannot be blank." }
        require(secret.toByteArray(Charsets.UTF_8).size <= MAX_SECRET_BYTES) {
            "Credential exceeds the 16 KiB storage limit."
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(secretId.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val payload = ByteBuffer.allocate(1 + iv.size + encrypted.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(encrypted)
            .array()

        check(secrets.edit().putString(secretId, V2_PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)).commit()) {
            "Unable to persist encrypted credential."
        }
    }

    fun get(secretId: String): String? {
        requireValidSecretId(secretId)
        val stored = secrets.getString(secretId, null) ?: return null
        val versioned = stored.startsWith(V2_PREFIX)
        val encoded = if (versioned) stored.removePrefix(V2_PREFIX) else stored
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
            if (versioned) cipher.updateAAD(secretId.toByteArray(Charsets.UTF_8))
            val secret = String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            // Migrate legacy v1 entries in place. The legacy format did not bind ciphertext
            // to its preference key; v2 authenticates the secret identifier as GCM AAD.
            if (!versioned) runCatching { put(secretId, secret) }
            secret
        }.getOrNull()
    }

    fun remove(secretId: String) {
        requireValidSecretId(secretId)
        secrets.edit().remove(secretId).apply()
    }

    fun clearAll() {
        secrets.edit().clear().apply()
    }

    private fun requireValidSecretId(secretId: String) {
        require(secretId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) {
            "Invalid secret identifier."
        }
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
        fun providerKeyId(providerKind: String): String =
            "provider.${providerKind.lowercase()}.api-key"

        private const val SECRET_PREFS = "nexus_encrypted_credentials"
        private const val V2_PREFIX = "v2:"
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

    fun load(providerKind: String? = null): ProviderSettings {
        val activeKind = providerKind
            ?: preferences.getString(KEY_ACTIVE_KIND, "OPENAI_COMPATIBLE")
            ?: "OPENAI_COMPATIBLE"
        return ProviderSettings(
            endpoint = preferences.getString("${activeKind}.endpoint", "") ?: "",
            model = preferences.getString("${activeKind}.model", "") ?: "",
            providerKind = activeKind
        )
    }

    fun save(settings: ProviderSettings) {
        val kind = ProviderKind.valueOf(settings.providerKind).name
        val endpoint = settings.endpoint.trim()
        val model = settings.model.trim()
        require(endpoint.length <= 2_048 && endpoint.none { it.code < 0x20 || it.code == 0x7f }) {
            "Provider endpoint must not exceed 2048 characters or contain control characters."
        }
        require(model.length <= 200 && model.none { it.code < 0x20 || it.code == 0x7f }) {
            "Provider model ID must not exceed 200 characters or contain control characters."
        }
        preferences.edit()
            .putString(KEY_ACTIVE_KIND, kind)
            .putString("${kind}.endpoint", endpoint)
            .putString("${kind}.model", model)
            .apply()
    }

    companion object {
        private const val SETTINGS_PREFS = "nexus_provider_settings"
        private const val KEY_ACTIVE_KIND = "active_provider_kind"
    }
}
