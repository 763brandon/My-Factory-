package com.myfactory.forge.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.myfactory.forge.core.settings.SecretStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys, encrypted with AES-256-GCM under a key held in the Android
 * keystore.
 *
 * The key material never enters the app's address space: the keystore returns
 * a handle, and on most devices from Android 7 onwards the operation happens
 * in a hardware-backed keymaster. What lands in SharedPreferences is the IV
 * and the ciphertext, which are useless without the device.
 *
 * Written directly against KeyStore rather than androidx.security.crypto,
 * which is deprecated and has a long history of failing on the exact low-end
 * hardware this app targets.
 */
class KeystoreSecretStore(context: Context) : SecretStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Set when the keystore itself is unusable, which happens on a handful of
     * damaged vendor ROMs. Callers show this instead of pretending a key was
     * saved securely.
     */
    var lastError: String? = null
        private set

    /**
     * Whether a key exists in the platform keystore at all. Whether the
     * keystore is hardware-backed on this specific device is not knowable
     * without KeyInfo, and is not worth an extra reflective call: the API 24
     * floor already guarantees the keystore itself.
     */
    val hasWrappingKey: Boolean
        get() = runCatching {
            keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry != null
        }.getOrDefault(false)

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private fun secretKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately not requiring user authentication: an agent
                // turn can outlive the screen timeout, and a key that becomes
                // unusable mid-request would fail the request, not protect it.
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    override fun put(alias: String, secret: String) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))

            // GCM needs a unique IV per encryption; the keystore generates one
            // and it is stored alongside the ciphertext, which is safe.
            val packed = cipher.iv + ciphertext
            prefs.edit()
                .putString(alias, Base64.encodeToString(packed, Base64.NO_WRAP))
                .apply()
            lastError = null
        } catch (e: Exception) {
            lastError = "Could not store the key securely: ${e.message}"
            // Nothing is written in plaintext as a fallback. A key that cannot
            // be protected is not stored at all.
            prefs.edit().remove(alias).apply()
        }
    }

    override fun get(alias: String): String? {
        val encoded = prefs.getString(alias, null) ?: return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= IV_LENGTH) return null
            val iv = packed.copyOfRange(0, IV_LENGTH)
            val ciphertext = packed.copyOfRange(IV_LENGTH, packed.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            // A key invalidated by a lock-screen change decrypts to nothing.
            // Report absence; the user re-enters the key.
            lastError = "The stored key could not be read and needs to be entered again."
            null
        }
    }

    override fun remove(alias: String) {
        prefs.edit().remove(alias).apply()
    }

    override fun contains(alias: String): Boolean = prefs.contains(alias)

    override fun aliases(): Set<String> = prefs.all.keys.toSet()

    /** Wipes every stored key. Offered in Settings. */
    fun clearAll() {
        prefs.edit().clear().apply()
        runCatching { keyStore.deleteEntry(KEY_ALIAS) }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "forge_secret_wrapping_key"
        const val PREFS_NAME = "forge_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
