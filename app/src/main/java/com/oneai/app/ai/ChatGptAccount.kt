package com.oneai.app.ai

import com.oneai.app.security.CredentialStatus
import com.oneai.app.security.SecretRead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** One sign-in attempt. [state] and [verifier] are secrets for the length of the attempt, so [toString] hides them. */
class SignInAttempt(val state: String, val verifier: String, val redirectUri: String, val authorizeUrl: String) {
    override fun toString() = "SignInAttempt(redacted)"
}

/**
 * ChatGPT-account sign-in, recovered from the earlier Orbit-style implementation in `p1prince/one-ai-android` and kept
 * separate from API-key authentication: it has its own storage, its own endpoints, and its tokens are only ever sent to
 * [ProviderCatalog.CHATGPT_RESPONSES_ENDPOINT] by [ChatGptAccountProvider].
 *
 * Kept from the original: OAuth authorization code with PKCE (S256), a random `state` checked on return, the ID token
 * checked against the issuer's signing keys, refresh with a rotated token persisted, the account id read from the
 * tokens, and one refresh-and-retry after a 401 (see [accessToken]). Refreshes are serialized so two requests never spend
 * the same single-use refresh token.
 *
 * This uses the sign-in registration of OpenAI's Codex client, not a documented public API; see `docs/AI_PROVIDERS.md`.
 */
class ChatGptAccount(
    private val storage: ChatGptSessionStorage,
    private val http: AiHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val log: (String) -> Unit = {}
) {
    private val refreshMutex = Mutex()

    // ---- state ----------------------------------------------------------------------------------------------------

    fun connection(): ChatGptConnection = when (val read = storage.read()) {
        SecretRead.Absent -> ChatGptConnection.NotConnected
        SecretRead.Unreadable -> ChatGptConnection.Unreadable
        is SecretRead.Present -> {
            val session = decodeCurrent(read.value)
            if (session?.canUsePlan == true) ChatGptConnection.Connected(session.email) else ChatGptConnection.NotConnected
        }
    }

    fun connectionStatus(): CredentialStatus = when (connection()) {
        is ChatGptConnection.Connected -> CredentialStatus.SAVED
        ChatGptConnection.Unreadable -> CredentialStatus.UNREADABLE
        ChatGptConnection.NotConnected -> CredentialStatus.NOT_SAVED
    }

    /** The ChatGPT account id sent with requests, or null when not signed in. */
    fun accountId(): String? {
        val session = (storage.read() as? SecretRead.Present)?.let { decodeCurrent(it.value) } ?: return null
        return ChatGptJwt.accountId(session.idToken).ifBlank { ChatGptJwt.accountId(session.accessToken) }.takeIf { it.isNotBlank() }
    }

    fun disconnect() {
        storage.clear()
        log("Disconnected ChatGPT account")
    }

    // ---- sign-in --------------------------------------------------------------------------------------------------

    /** Pure apart from the random source: PKCE verifier/challenge, state and the authorization address. */
    fun prepareSignIn(redirectUri: String): SignInAttempt {
        val state = randomToken(32)
        val verifier = randomToken(48)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val query = listOf(
            "response_type" to "code",
            "client_id" to CLIENT_ID,
            "redirect_uri" to redirectUri,
            "scope" to SCOPE,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "state" to state,
            "originator" to ORIGINATOR_PARAM
        ).joinToString("&") { (k, v) -> encode(k) + "=" + encode(v) }
        return SignInAttempt(state, verifier, redirectUri, "$AUTH_ENDPOINT?$query")
    }

    /**
     * Completes an attempt from the browser's callback [params] (already state-checked by [OAuthCallback]): exchanges the
     * code, verifies the identity token and stores the session. Nothing is stored if any check fails.
     */
    suspend fun finishSignIn(attempt: SignInAttempt, params: Map<String, String>): ChatGptSession = withContext(Dispatchers.IO) {
        val oauthError = params["error"].orEmpty()
        if (oauthError.isNotBlank()) {
            val description = params["error_description"].orEmpty()
            throw AiProviderException(
                "OAUTH_DENIED",
                "ChatGPT authorization was not completed: $oauthError" + if (description.isNotBlank()) " - $description" else ""
            )
        }
        val code = params["code"]?.takeIf { it.isNotBlank() }
            ?: throw AiProviderException("OAUTH_FAILED", "OpenAI returned to ONE AI without an authorization code.")
        val token = tokenRequest(
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to CLIENT_ID,
                "code" to code,
                "code_verifier" to attempt.verifier,
                "redirect_uri" to attempt.redirectUri
            )
        )
        val idToken = token.optString("id_token").ifBlank { throw AiProviderException("OAUTH_FAILED", "OpenAI did not return an identity token.") }
        val accessToken = token.optString("access_token").ifBlank { throw AiProviderException("OAUTH_FAILED", "OpenAI did not return an access token.") }
        val refreshToken = token.optString("refresh_token").ifBlank { throw AiProviderException("OAUTH_FAILED", "OpenAI did not return a refresh token.") }
        val email = ChatGptJwt.verifyIdToken(idToken, fetchSigningKeys(), CLIENT_ID, clock())
        val session = ChatGptSession(
            CLIENT_ID, accessToken, refreshToken, idToken, email,
            token.optString("scope").ifBlank { SCOPE }.split(" ").filter { it.isNotBlank() }.toSet(),
            clock() + token.optLong("expires_in", 3600) * 1000L
        )
        storage.write(ChatGptSessionCodec.encode(session))
        log("Connected ChatGPT account")
        session
    }

    /**
     * The whole flow: open the loopback receiver, send the user to the browser through [openBrowser], wait for the
     * matching callback, finish. Cancelling the calling coroutine closes the receiver.
     */
    suspend fun signIn(
        openBrowser: (String) -> Unit,
        receiverFactory: () -> LoopbackReceiver = { LoopbackReceiver.bind(CALLBACK_PORTS) }
    ): ChatGptSession = withContext(Dispatchers.IO) {
        val receiver = receiverFactory()
        val onCancel = currentCoroutineContext()[Job]?.invokeOnCompletion { receiver.close() }
        try {
            val attempt = prepareSignIn("http://127.0.0.1:${receiver.port}${OAuthCallback.PATH}")
            openBrowser(attempt.authorizeUrl)
            val params = receiver.awaitCallback(attempt.state)
            finishSignIn(attempt, params)
        } finally {
            onCancel?.dispose()
            receiver.close()
        }
    }

    // ---- tokens ---------------------------------------------------------------------------------------------------

    /**
     * A usable access token, refreshed when it is about to expire. After the server rejected a token (a 401), pass it as
     * [rejectedToken]: if another request has already replaced it, the new token is returned without a second refresh;
     * otherwise the refresh is forced. A revoked or expired sign-in disconnects the account and asks the user to reconnect.
     */
    suspend fun accessToken(rejectedToken: String? = null): String = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            // Read only after taking the lock: another request may have refreshed and saved while this one waited.
            val current = loadSession()
            val force = rejectedToken != null && current.accessToken == rejectedToken
            if (!force && current.expiresAtMillis > clock() + REFRESH_MARGIN_MILLIS) return@withLock current.accessToken
            val refreshed = try {
                tokenRequest(mapOf("grant_type" to "refresh_token", "client_id" to CLIENT_ID, "refresh_token" to current.refreshToken))
            } catch (error: AiProviderException) {
                if (error.code == "OAUTH_invalid_grant") {
                    disconnect()
                    throw AiProviderException(
                        "SESSION_EXPIRED",
                        "Your ChatGPT connection has expired or been revoked. Reconnect ChatGPT in Settings."
                    )
                }
                throw error
            }
            val newAccess = refreshed.optString("access_token").ifBlank {
                throw AiProviderException("OAUTH_FAILED", "OpenAI did not return a new access token.")
            }
            val updated = current.copy(
                accessToken = newAccess,
                // The refresh token may rotate; keep the old one only when the server sent none.
                refreshToken = refreshed.optString("refresh_token").ifBlank { current.refreshToken },
                idToken = refreshed.optString("id_token").ifBlank { current.idToken },
                scopes = refreshed.optString("scope").ifBlank { current.scopes.joinToString(" ") }.split(" ").filter { it.isNotBlank() }.toSet(),
                expiresAtMillis = clock() + refreshed.optLong("expires_in", 3600) * 1000L
            )
            storage.write(ChatGptSessionCodec.encode(updated))
            newAccess
        }
    }

    // ---- internals ------------------------------------------------------------------------------------------------

    private fun loadSession(): ChatGptSession {
        val read = storage.read()
        if (read === SecretRead.Unreadable) throw AiProviderException("CREDENTIALS_UNREADABLE", CredentialStatus.UNREADABLE_MESSAGE)
        val session = (read as? SecretRead.Present)?.let { decodeCurrent(it.value) }
            ?: throw AiProviderException("NOT_CONNECTED", "Connect your ChatGPT account in Settings first.")
        if (!session.canUsePlan) throw AiProviderException("NOT_CONNECTED", "Connect your ChatGPT account in Settings first.")
        return session
    }

    /** A stored session for another sign-in registration cannot be used with this backend; it is discarded. */
    private fun decodeCurrent(json: String): ChatGptSession? {
        val session = ChatGptSessionCodec.decode(json) ?: return null
        if (session.clientId != CLIENT_ID) {
            storage.clear()
            return null
        }
        return session
    }

    private fun fetchSigningKeys(): String =
        http.open(AiHttpRequest(JWKS_URL, mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT), "", method = "GET")).use { response ->
            val raw = response.readBody(MAX_TOKEN_BODY_BYTES)
                ?: throw AiProviderException("OAUTH_FAILED", "The OpenAI signing-key response was too large.")
            if (response.status !in 200..299) throw AiProviderException("OAUTH_FAILED", "Could not load OpenAI's signing keys (HTTP ${response.status}).")
            raw
        }

    /** POSTs a form to the token endpoint. Failures carry code `OAUTH_<error>` (for example OAUTH_invalid_grant). */
    private fun tokenRequest(form: Map<String, String>): JSONObject {
        val body = form.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
        return http.open(
            AiHttpRequest(
                TOKEN_ENDPOINT,
                mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json", "User-Agent" to USER_AGENT),
                body,
                readTimeoutMs = 30_000
            )
        ).use { response ->
            val raw = response.readBody(MAX_TOKEN_BODY_BYTES)
                ?: throw AiProviderException("OAUTH_FAILED", "The OpenAI sign-in response was too large.")
            if (response.status !in 200..299) throw tokenFailure(response.status, raw, form.values)
            runCatching { JSONObject(raw) }.getOrElse { throw AiProviderException("OAUTH_FAILED", "The OpenAI sign-in service returned an unreadable response.") }
        }
    }

    private fun tokenFailure(status: Int, raw: String, secrets: Collection<String>): AiProviderException {
        val json = runCatching { JSONObject(raw) }.getOrNull()
        val errorValue = json?.opt("error")
        val code = when (errorValue) {
            is String -> errorValue
            is JSONObject -> errorValue.optString("code")
            else -> ""
        }
        val message = (errorValue as? JSONObject)?.optString("message").orEmpty()
            .ifBlank { json?.optString("error_description").orEmpty() }
            .ifBlank { json?.optString("message").orEmpty() }
        val detail = listOf(code, message).filter { it.isNotBlank() }.joinToString(": ")
        val text = ProviderSupport.scrub(detail, *secrets.toTypedArray()).take(300)
        return AiProviderException(
            if (code.isNotBlank()) "OAUTH_$code" else "OAUTH_FAILED",
            text.ifBlank { "OpenAI sign-in service returned HTTP $status. Please try signing in again." }
        )
    }

    private fun randomToken(bytes: Int): String = ByteArray(bytes).also { random.nextBytes(it) }.let(::base64Url)
    private fun base64Url(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        // Interoperability reference: the Codex-backed account flow used by lpnovi/Orbit-Assistant, carried over unchanged
        // from the working implementation in p1prince/one-ai-android. Not OpenAI's documented API-key endpoint.
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val AUTH_ENDPOINT = "https://auth.openai.com/oauth/authorize"
        const val TOKEN_ENDPOINT = "https://auth.openai.com/oauth/token"
        const val JWKS_URL = "https://auth.openai.com/.well-known/jwks.json"
        const val SCOPE = "openid profile email offline_access"
        const val ORIGINATOR_PARAM = "codex_cli_rs"
        const val USER_AGENT = "ONEAI/Android"
        /** The Codex sign-in registration accepts only these loopback ports. */
        val CALLBACK_PORTS = listOf(1455, 1457)
        const val REFRESH_MARGIN_MILLIS = 60_000L
        private const val MAX_TOKEN_BODY_BYTES = 256 * 1024
    }
}
