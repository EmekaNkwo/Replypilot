package com.replypilot.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

enum class CredentialKey { AI_API_KEY, BUSINESS_RELAY_TOKEN }

/**
 * Stores user-provided credentials encrypted with an AES-GCM key held in the Android Keystore.
 * The key never leaves the secure hardware/OS; only ciphertext is written to app-private storage.
 * Server-side secrets (e.g. a Meta system-user token) must never be stored here — they belong on the relay.
 */
@Singleton
class CredentialStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("replypilot_credentials", Context.MODE_PRIVATE)

    fun has(key: CredentialKey): Boolean = prefs.contains(key.name)

    fun read(key: CredentialKey): String? {
        val stored = prefs.getString(key.name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_LENGTH)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
        }.onFailure { e ->
            if (e is AEADBadTagException || e is KeyPermanentlyInvalidatedException) {
                Log.w("ReplyPilot", "Stored credential ${key.name} can no longer be decrypted and was removed")
                remove(key)
            } else {
                Log.w("ReplyPilot", "Stored credential ${key.name} couldn't be read right now", e)
            }
        }.getOrNull()
    }

    fun write(key: CredentialKey, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key.name, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    fun remove(key: CredentialKey) {
        prefs.edit().remove(key.name).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "replypilot_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
