package ai.dui.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets at rest: AES-256-GCM with a key that never leaves the Android Keystore.
 *
 * Stored form: `enc:v1:<base64(iv || ciphertext || tag)>`. Anything without the prefix is legacy plaintext and is
 * returned unchanged, so old installs keep working and are migrated on the next write (see AuthPreferences).
 */
object SecretCipher {
    private const val TAG = "SecretCipher"
    private const val PREFIX = "enc:v1:"
    private const val KEY_ALIAS = "dui_secret_v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

    /**
     * Encrypts [plain]. Throws when the Keystore is unavailable instead of returning the plaintext: a caller that
     * cannot encrypt must not persist the value at all (secure failure, never a plaintext fallback).
     */
    fun encrypt(plain: String): String {
        if (plain.startsWith(PREFIX)) return plain
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "encrypt failed: ${e.javaClass.simpleName}")
            throw IllegalStateException("Secure storage is unavailable; the value was not saved.", e)
        }
    }

    /** [encrypt] for optional values: null on failure, so the caller can skip the write instead of storing plaintext. */
    fun encryptOrNull(plain: String): String? = runCatching { encrypt(plain) }.getOrNull()

    /** Empty string on failure: callers treat it as "not set" instead of using unreadable ciphertext. */
    fun decrypt(stored: String?): String {
        if (stored.isNullOrEmpty() || !stored.startsWith(PREFIX)) return stored.orEmpty()
        return runCatching {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        }.onFailure { Log.e(TAG, "decrypt failed", it) }.getOrDefault("")
    }

    /** Values that can be blank (a cleared SSH key, an unset proxy header) stay blank instead of being encrypted. */
    fun encryptNonEmpty(plain: String): String = if (plain.isEmpty()) plain else encrypt(plain)

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
