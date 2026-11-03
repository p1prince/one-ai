package com.oneai.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts independent provider credentials with non-exportable Android Keystore keys. */
class SecretVault(context: Context) {
    private val prefs = context.getSharedPreferences("one_ai_secrets", Context.MODE_PRIVATE)
    private val alias = "one_ai_provider_key"

    /** Backward-compatible primary AI provider credential storage. */
    fun save(value: String) = saveSecret(alias, "cipher", "iv", value)

    fun read(): String? = readSecret(alias, "cipher", "iv")

    /** Stores a separate encrypted credential without replacing the primary AI provider key. */
    fun saveNamed(name: String, value: String) {
        val safeName = validateName(name)
        saveSecret("one_ai_secret_$safeName", "cipher_$safeName", "iv_$safeName", value)
    }

    fun readNamed(name: String): String? {
        val safeName = validateName(name)
        return readSecret("one_ai_secret_$safeName", "cipher_$safeName", "iv_$safeName")
    }

    private fun saveSecret(keyAlias: String, cipherKey: String, ivKey: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(cipherKey).remove(ivKey).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey(keyAlias))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(cipherKey, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(ivKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).apply()
    }

    private fun readSecret(keyAlias: String, cipherKey: String, ivKey: String): String? = runCatching {
        val data = prefs.getString(cipherKey, null) ?: return null
        val iv = Base64.decode(prefs.getString(ivKey, null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKey(keyAlias), GCMParameterSpec(128, iv))
        String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    private fun validateName(name: String): String {
        require(name.matches(Regex("[a-z][a-z0-9_]{0,31}"))) { "Credential name is invalid." }
        return name
    }

    private fun getKey(keyAlias: String): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}
