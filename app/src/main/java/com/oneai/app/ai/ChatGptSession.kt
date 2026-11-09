package com.oneai.app.ai

import com.oneai.app.security.SecretRead
import org.json.JSONObject

/**
 * One ChatGPT-account sign-in. Holds live tokens, so [toString] never shows them. It is stored encrypted, apart from
 * every API key, and is never part of a provider profile.
 */
data class ChatGptSession(
    val clientId: String,
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val email: String,
    val scopes: Set<String>,
    val expiresAtMillis: Long
) {
    val canUsePlan: Boolean get() = accessToken.isNotBlank() && refreshToken.isNotBlank()
    override fun toString() = "ChatGptSession(redacted)"
}

/** JSON form of a [ChatGptSession] as it is encrypted at rest. A damaged value decodes to null, never to a guess. */
object ChatGptSessionCodec {
    fun encode(session: ChatGptSession): String = JSONObject()
        .put("client_id", session.clientId)
        .put("access_token", session.accessToken)
        .put("refresh_token", session.refreshToken)
        .put("id_token", session.idToken)
        .put("email", session.email)
        .put("scopes", session.scopes.joinToString(" "))
        .put("expires_at", session.expiresAtMillis)
        .toString()

    fun decode(json: String): ChatGptSession? = runCatching {
        val o = JSONObject(json)
        ChatGptSession(
            clientId = o.getString("client_id"),
            accessToken = o.getString("access_token"),
            refreshToken = o.getString("refresh_token"),
            idToken = o.optString("id_token"),
            email = o.optString("email"),
            scopes = o.optString("scopes", ChatGptAccount.SCOPE).split(" ").filter { it.isNotBlank() }.toSet(),
            expiresAtMillis = o.getLong("expires_at")
        )
    }.getOrNull()
}

/**
 * Where the encrypted session lives. The Android implementation encrypts with its own Keystore key; tests use memory.
 * [read] tells "nothing saved" apart from "saved but unreadable", like the API-key vault does.
 */
interface ChatGptSessionStorage {
    fun read(): SecretRead
    fun write(value: String)
    fun clear()
}

/** Whether the app currently holds a usable ChatGPT sign-in. */
sealed interface ChatGptConnection {
    object NotConnected : ChatGptConnection { override fun toString() = "NotConnected" }
    class Connected(val email: String) : ChatGptConnection { override fun toString() = "Connected" }
    object Unreadable : ChatGptConnection { override fun toString() = "Unreadable" }

    fun link(): AccountLink = when (this) {
        is Connected -> AccountLink.CONNECTED
        Unreadable -> AccountLink.UNREADABLE
        NotConnected -> AccountLink.NOT_CONNECTED
    }
}
