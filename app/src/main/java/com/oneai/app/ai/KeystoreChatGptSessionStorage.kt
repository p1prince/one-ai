package com.oneai.app.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.oneai.app.security.SecretRead
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The ChatGPT sign-in encrypted with its own non-exportable Android Keystore key, in its own preferences file, apart from
 * every API key. A value that cannot be decrypted (for example after the Keystore key was invalidated) reads as
 * [SecretRead.Unreadable], not as "not connected". Never logs, and the failure cause is not kept.
 */
class KeystoreChatGptSessionStorage(context: Context) : ChatGptSessionStorage {
    private val prefs = context.getSharedPreferences("one_ai_chatgpt_oauth", Context.MODE_PRIVATE)

    override fun read(): SecretRead {
        val stored = prefs.getString(SESSION, null) ?: return SecretRead.Absent
        return try {
            val data = Base64.decode(stored, Base64.NO_WRAP)
            require(data.size > IV_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, IV_BYTES)))
            SecretRead.Present(String(cipher.doFinal(data.copyOfRange(IV_BYTES, data.size)), Charsets.UTF_8))
        } catch (ignored: Exception) {
            SecretRead.Unreadable
        }
    }

    override fun write(value: String) {
        if (value.isBlank()) return clear()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(SESSION, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    override fun clear() {
        prefs.edit().remove(SESSION).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val SESSION = "session"
        const val ALIAS = "one_ai_chatgpt_oauth_v1"
        const val IV_BYTES = 12
    }
}

/** One [ChatGptAccount] per process, so every provider instance shares its refresh lock and stored session. */
object ChatGptAccounts {
    @Volatile private var instance: ChatGptAccount? = null

    fun forContext(context: Context, log: (String) -> Unit = {}): ChatGptAccount =
        instance ?: synchronized(this) {
            instance ?: ChatGptAccount(KeystoreChatGptSessionStorage(context.applicationContext), HttpUrlConnectionAiHttp(), log = log)
                .also { instance = it }
        }
}
