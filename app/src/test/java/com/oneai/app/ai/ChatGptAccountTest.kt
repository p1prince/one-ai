package com.oneai.app.ai

import com.oneai.app.security.CredentialStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** ChatGPT-account OAuth: PKCE, state, token verification, refresh and 401 handling, all against mock HTTP. */
class ChatGptAccountTest {
    private val now = 1_800_000_000_000L
    private val issuer = TestIssuer()
    private val storage = MemorySession()

    private fun fail(block: suspend () -> Unit): AiProviderException =
        assertThrows(AiProviderException::class.java) { runBlocking { block() } }

    private fun tokenJson(access: String = "access-1", refresh: String = "refresh-1", id: String = issuer.token(now = now), expiresIn: Long = 3600) =
        JSONObject().put("access_token", access).put("refresh_token", refresh).put("id_token", id).put("expires_in", expiresIn).toString()

    /** Routes the token endpoint and the signing-key endpoint; counts token calls per grant type. */
    private class Auth(val issuer: TestIssuer, val onToken: (Map<String, String>) -> Canned) {
        val tokenForms = ArrayList<Map<String, String>>()
        val http = FakeHttp { request ->
            when (request.url) {
                ChatGptAccount.JWKS_URL -> Canned.ok(issuer.jwks)
                ChatGptAccount.TOKEN_ENDPOINT -> {
                    val form = request.body.split("&").associate {
                        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                    }
                    synchronized(tokenForms) { tokenForms += form }
                    onToken(form)
                }
                else -> Canned(404, "unexpected ${request.url}")
            }
        }
    }

    private fun account(auth: Auth, clock: () -> Long = { now }) = ChatGptAccount(storage, auth.http, clock)

    private fun connected(expiresInMillis: Long = 3_600_000, access: String = "access-1", refresh: String = "refresh-1", clientId: String = ChatGptAccount.CLIENT_ID) {
        storage.value = ChatGptSessionCodec.encode(
            ChatGptSession(clientId, access, refresh, issuer.token(now = now), "user@example.com", setOf("openid"), now + expiresInMillis)
        )
    }

    // ---- sign-in request ---------------------------------------------------------------------------------------------

    @Test fun signInUsesPkceS256AndAFreshState() {
        val account = account(Auth(issuer) { Canned.ok("{}") })
        val a = account.prepareSignIn("http://127.0.0.1:1455/auth/callback")
        val b = account.prepareSignIn("http://127.0.0.1:1455/auth/callback")
        val query = URI(a.authorizeUrl).rawQuery.split("&").associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertTrue(a.authorizeUrl.startsWith(ChatGptAccount.AUTH_ENDPOINT + "?"))
        assertEquals("code", query["response_type"])
        assertEquals(ChatGptAccount.CLIENT_ID, query["client_id"])
        assertEquals("http://127.0.0.1:1455/auth/callback", query["redirect_uri"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals(a.state, query["state"])
        assertTrue(query["scope"]!!.split(" ").containsAll(listOf("openid", "offline_access")))
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(a.verifier.toByteArray()))
        assertEquals(expected, query["code_challenge"])
        assertFalse("the verifier itself is never sent in the browser URL", a.authorizeUrl.contains(a.verifier))
        assertNotEquals(a.state, b.state)
        assertNotEquals(a.verifier, b.verifier)
        assertTrue(a.verifier.length >= 43)
        assertEquals("SignInAttempt(redacted)", a.toString())
    }

    // ---- finishing sign-in -------------------------------------------------------------------------------------------

    @Test fun finishSignInStoresAVerifiedSession() = runBlocking {
        val auth = Auth(issuer) { Canned.ok(tokenJson()) }
        val account = account(auth)
        val attempt = account.prepareSignIn("http://127.0.0.1:1455/auth/callback")
        val session = account.finishSignIn(attempt, mapOf("code" to "the-code", "state" to attempt.state))
        assertEquals("user@example.com", session.email)
        val form = auth.tokenForms.single()
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("the-code", form["code"])
        assertEquals(attempt.verifier, form["code_verifier"])
        assertEquals(attempt.redirectUri, form["redirect_uri"])
        assertEquals(ChatGptAccount.CLIENT_ID, form["client_id"])
        assertTrue(account.connection() is ChatGptConnection.Connected)
        assertEquals(CredentialStatus.SAVED, account.connectionStatus())
        assertEquals("acct-123", account.accountId())
        assertEquals("ChatGptSession(redacted)", session.toString())
    }

    @Test fun deniedOrCodelessCallbacksStoreNothing() {
        val auth = Auth(issuer) { Canned.ok(tokenJson()) }
        val account = account(auth)
        val attempt = account.prepareSignIn("http://127.0.0.1:1455/auth/callback")
        assertEquals("OAUTH_DENIED", fail { account.finishSignIn(attempt, mapOf("error" to "access_denied", "error_description" to "no")) }.code)
        assertEquals("OAUTH_FAILED", fail { account.finishSignIn(attempt, mapOf("state" to attempt.state)) }.code)
        assertTrue(auth.tokenForms.isEmpty())
        assertNull(storage.value)
    }

    @Test fun anInvalidIdentityTokenStoresNothing() {
        fun attempt(id: String): Pair<ChatGptAccount, SignInAttempt> {
            storage.value = null
            val a = account(Auth(issuer) { Canned.ok(tokenJson(id = id)) })
            return a to a.prepareSignIn("http://127.0.0.1:1455/auth/callback")
        }
        val bad = mapOf(
            "wrong audience" to issuer.token(clientId = "someone-else", now = now),
            "wrong issuer" to issuer.token(issuer = "https://evil.example", now = now),
            "expired" to issuer.token(now = now - 7_200_000, lifetimeSeconds = 60),
            "tampered signature" to issuer.token(now = now, tamper = true),
            "wrong algorithm" to issuer.token(now = now, header = JSONObject().put("alg", "none").put("kid", issuer.kid)),
            "unknown key" to issuer.token(now = now, header = JSONObject().put("alg", "RS256").put("kid", "other")),
            "garbage" to "not.a.jwt"
        )
        bad.forEach { (label, id) ->
            val (a, at) = attempt(id)
            assertEquals(label, "OAUTH_INVALID_ID_TOKEN", fail { a.finishSignIn(at, mapOf("code" to "c", "state" to at.state)) }.code)
            assertNull("$label stored a session", storage.value)
        }
    }

    @Test fun aTokenSignedByAnotherKeyIsRejected() {
        val other = TestIssuer(kid = issuer.kid) // same key id, different key
        val a = account(Auth(issuer) { Canned.ok(tokenJson(id = other.token(now = now))) })
        val at = a.prepareSignIn("http://127.0.0.1:1455/auth/callback")
        assertEquals("OAUTH_INVALID_ID_TOKEN", fail { a.finishSignIn(at, mapOf("code" to "c")) }.code)
        assertNull(storage.value)
    }

    @Test fun incompleteTokenResponsesAreRefused() {
        listOf(
            JSONObject().put("access_token", "a").put("refresh_token", "r"),
            JSONObject().put("id_token", issuer.token(now = now)).put("refresh_token", "r"),
            JSONObject().put("id_token", issuer.token(now = now)).put("access_token", "a")
        ).forEach { body ->
            storage.value = null
            val a = account(Auth(issuer) { Canned.ok(body.toString()) })
            val at = a.prepareSignIn("http://127.0.0.1:1455/auth/callback")
            assertEquals("OAUTH_FAILED", fail { a.finishSignIn(at, mapOf("code" to "c")) }.code)
            assertNull(storage.value)
        }
    }

    @Test fun tokenEndpointErrorsAreNormalizedAndScrubbed() {
        val a = account(Auth(issuer) { Canned.json(400, """{"error":"invalid_grant","error_description":"code the-code was already used"}""") })
        val at = a.prepareSignIn("http://127.0.0.1:1455/auth/callback")
        val e = fail { a.finishSignIn(at, mapOf("code" to "the-code")) }
        assertEquals("OAUTH_invalid_grant", e.code)
        assertFalse(e.message!!.contains("the-code"))
    }

    // ---- refresh -----------------------------------------------------------------------------------------------------

    @Test fun aFreshTokenIsReturnedWithoutRefreshing() = runBlocking {
        val auth = Auth(issuer) { Canned.ok(tokenJson()) }
        connected()
        assertEquals("access-1", account(auth).accessToken())
        assertTrue(auth.tokenForms.isEmpty())
    }

    @Test fun anExpiringTokenIsRefreshedAndTheRotatedRefreshTokenIsSaved() = runBlocking {
        val auth = Auth(issuer) { Canned.ok(tokenJson(access = "access-2", refresh = "refresh-2", expiresIn = 7200)) }
        connected(expiresInMillis = 30_000) // inside the refresh margin
        assertEquals("access-2", account(auth).accessToken())
        val form = auth.tokenForms.single()
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("refresh-1", form["refresh_token"])
        val saved = ChatGptSessionCodec.decode(storage.value!!)!!
        assertEquals("refresh-2", saved.refreshToken)
        assertEquals(now + 7_200_000, saved.expiresAtMillis)
    }

    @Test fun aRefreshWithoutANewRefreshTokenKeepsTheOldOne() = runBlocking {
        val body = JSONObject().put("access_token", "access-2").put("expires_in", 3600).toString()
        connected(expiresInMillis = 0)
        account(Auth(issuer) { Canned.ok(body) }).accessToken()
        assertEquals("refresh-1", ChatGptSessionCodec.decode(storage.value!!)!!.refreshToken)
    }

    @Test fun concurrentRequestsShareASingleRefresh() = runBlocking {
        val calls = AtomicInteger()
        val auth = Auth(issuer) { calls.incrementAndGet(); Thread.sleep(50); Canned.ok(tokenJson(access = "access-2", refresh = "refresh-2")) }
        connected(expiresInMillis = 0)
        val a = account(auth)
        val tokens = (1..8).map { async { a.accessToken() } }.awaitAll()
        assertEquals(1, calls.get())
        assertEquals(setOf("access-2"), tokens.toSet())
    }

    @Test fun aRejectedTokenForcesOneRefreshUnlessAnotherRequestAlreadyReplacedIt() = runBlocking {
        val auth = Auth(issuer) { Canned.ok(tokenJson(access = "access-2", refresh = "refresh-2")) }
        connected() // valid for an hour, yet the server said 401
        val a = account(auth)
        assertEquals("access-2", a.accessToken(rejectedToken = "access-1"))
        assertEquals(1, auth.tokenForms.size)
        // A second request that was also rejected with the old token reuses the new one.
        assertEquals("access-2", a.accessToken(rejectedToken = "access-1"))
        assertEquals(1, auth.tokenForms.size)
    }

    @Test fun aRevokedSessionDisconnectsAndAsksToReconnect() {
        val auth = Auth(issuer) { Canned.json(400, """{"error":"invalid_grant"}""") }
        connected(expiresInMillis = 0)
        val e = fail { account(auth).accessToken() }
        assertEquals("SESSION_EXPIRED", e.code)
        assertNull(storage.value)
        assertEquals("NOT_CONNECTED", fail { account(auth).accessToken() }.code)
    }

    @Test fun otherRefreshFailuresKeepTheSessionForARetry() {
        connected(expiresInMillis = 0)
        val e = fail { account(Auth(issuer) { Canned.json(503, "") }).accessToken() }
        assertNotEquals("SESSION_EXPIRED", e.code)
        assertTrue(storage.value != null)
    }

    @Test fun storageProblemsAreReportedNotGuessed() {
        val a = account(Auth(issuer) { Canned.ok(tokenJson()) })
        assertEquals("NOT_CONNECTED", fail { a.accessToken() }.code)
        assertEquals(CredentialStatus.NOT_SAVED, a.connectionStatus())
        storage.unreadable = true
        assertEquals("CREDENTIALS_UNREADABLE", fail { a.accessToken() }.code)
        assertEquals(CredentialStatus.UNREADABLE, a.connectionStatus())
        assertEquals(AccountLink.UNREADABLE, a.connection().link())
        storage.unreadable = false
        storage.value = "{damaged"
        assertEquals("NOT_CONNECTED", fail { a.accessToken() }.code)
    }

    @Test fun aSessionFromAnotherRegistrationIsDiscarded() {
        connected(clientId = "app_other")
        val a = account(Auth(issuer) { Canned.ok(tokenJson()) })
        assertTrue(a.connection() is ChatGptConnection.NotConnected)
        assertNull(storage.value)
    }

    @Test fun disconnectErasesTheSession() {
        connected()
        val a = account(Auth(issuer) { Canned.ok("{}") })
        a.disconnect()
        assertNull(storage.value)
        assertNull(a.accountId())
        assertEquals(AccountLink.NOT_CONNECTED, a.connection().link())
    }

    // ---- the provider ------------------------------------------------------------------------------------------------

    private val streamOk = listOf(
        """data: {"type":"response.created"}""", "",
        """data: {"type":"response.output_text.delta","delta":"Hel"}""", "",
        """data: {"type":"response.output_text.delta","delta":"lo"}""", "",
        """data: {"type":"response.completed","response":{}}""", ""
    ).joinToString("\n")

    private fun chatGptProfile(endpoint: String = ProviderCatalog.CHATGPT_RESPONSES_ENDPOINT) =
        profile(ProviderCatalog.CHATGPT_ACCOUNT, name = "My ChatGPT", endpoint = endpoint)

    @Test fun theProviderStreamsToTheFixedEndpointWithAccountHeaders() = runBlocking {
        connected()
        val api = FakeHttp(Canned.ok(streamOk))
        val a = account(Auth(issuer) { Canned.ok("{}") })
        // Even a tampered profile endpoint cannot redirect the token.
        val provider = ChatGptAccountProvider(chatGptProfile("https://attacker.example/collect"), a, api) { "session-1" }
        val result = provider.complete(listOf(AiMessage("system", "Be kind."), AiMessage("user", "Hi")))
        assertEquals(AiCompletion("Hello", "gpt-6-luna", "My ChatGPT"), result)
        val request = api.only()
        assertEquals(ProviderCatalog.CHATGPT_RESPONSES_ENDPOINT, request.url)
        assertEquals("Bearer access-1", request.headers["Authorization"])
        assertEquals("acct-123", request.headers["ChatGPT-Account-ID"])
        assertEquals("session-1", request.headers["session_id"])
        val body = JSONObject(request.body)
        assertEquals("Be kind.", body.getString("instructions"))
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.getBoolean("store"))
    }

    @Test fun aUnauthorizedAnswerRefreshesOnceAndRetries() = runBlocking {
        connected()
        val auth = Auth(issuer) { Canned.ok(tokenJson(access = "access-2", refresh = "refresh-2")) }
        val api = FakeHttp { r -> if (r.headers["Authorization"] == "Bearer access-1") Canned.json(401, "expired") else Canned.ok(streamOk) }
        val result = ChatGptAccountProvider(chatGptProfile(), account(auth), api).complete(userMessage())
        assertEquals("Hello", result.content)
        assertEquals(listOf("Bearer access-1", "Bearer access-2"), api.requests.map { it.headers["Authorization"] })
        assertEquals(1, auth.tokenForms.size)
    }

    @Test fun aSecondUnauthorizedAnswerAsksToReconnect() {
        connected()
        val auth = Auth(issuer) { Canned.ok(tokenJson(access = "access-2", refresh = "refresh-2")) }
        val api = FakeHttp(Canned.json(401, "nope"))
        val e = fail { ChatGptAccountProvider(chatGptProfile(), account(auth), api).complete(userMessage()) }
        assertEquals("SESSION_EXPIRED", e.code)
        assertEquals(2, api.requests.size)
        assertEquals(1, auth.tokenForms.size)
    }

    @Test fun notConnectedFailsBeforeAnyRequest() {
        val api = FakeHttp(Canned.ok(streamOk))
        val e = fail { ChatGptAccountProvider(chatGptProfile(), account(Auth(issuer) { Canned.ok("{}") }), api).complete(userMessage()) }
        assertEquals("NOT_CONNECTED", e.code)
        assertTrue(api.requests.isEmpty())
    }

    @Test fun serverErrorsAreNormalizedAndNeverEchoTheToken() {
        connected()
        val api = FakeHttp(Canned.json(500, """{"error":{"message":"boom access-1"}}"""))
        val e = fail { ChatGptAccountProvider(chatGptProfile(), account(Auth(issuer) { Canned.ok("{}") }), api).complete(userMessage()) }
        assertEquals("HTTP_500", e.code)
        assertFalse(e.message!!.contains("access-1"))
    }

    @Test fun streamParsingHandlesEverySuccessAndFailureShape() {
        fun stream(vararg events: String) = events.joinToString("\n\n") { "data: $it" }.lineSequence()
        assertEquals("ab", ChatGptAccountProvider.parseStream(stream(
            """{"type":"response.output_text.delta","delta":"a"}""", """{"type":"response.output_text.delta","delta":"b"}""", """{"type":"response.completed"}""", "[DONE]"
        )))
        fun code(vararg events: String) = assertThrows(AiProviderException::class.java) { ChatGptAccountProvider.parseStream(stream(*events)) }.code
        assertEquals("INVALID_RESPONSE", code("""{"type":"response.output_text.delta","delta":"cut off"}"""))
        assertEquals("EMPTY_RESPONSE", code("""{"type":"response.completed"}"""))
        assertEquals("PROVIDER_ERROR", code("""{"type":"response.failed","response":{"error":{"code":"rate_limit","message":"slow down"}}}"""))
        assertEquals("PROVIDER_ERROR", code("""{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}"""))
        assertEquals("PROVIDER_ERROR", code("""{"type":"error","error":{"message":"bad"}}"""))
        // Non-data lines and malformed events are skipped.
        assertEquals("ok", ChatGptAccountProvider.parseStream(sequenceOf(
            "event: x", "data: {broken", """data: {"type":"response.output_text.delta","delta":"ok"}""", """data: {"type":"response.completed"}"""
        )))
    }

    @Test fun requestBodyMapsRolesToResponsesItems() {
        val body = ChatGptAccountProvider.requestBody(listOf(AiMessage("user", "q"), AiMessage("assistant", "a"), AiMessage("user", "q2")), "gpt-6-luna")
        assertEquals(ChatGptAccountProvider.DEFAULT_INSTRUCTIONS, body.getString("instructions"))
        val input = body.getJSONArray("input")
        val types = (0 until input.length()).map { input.getJSONObject(it).getJSONArray("content").getJSONObject(0).getString("type") }
        assertEquals(listOf("input_text", "output_text", "input_text"), types)
    }

    // ---- separation from the OpenAI API-key provider ----------------------------------------------------------------

    @Test fun apiKeysAndAccountTokensNeverMix() = runBlocking {
        connected()
        val keys = MapSecrets().also { it.map[null] = "sk-legacy"; it.map["key-p-openai"] = "sk-openai" }
        // The OpenAI adapter sends only its API key; the account token never reaches it.
        val openAiHttp = FakeHttp(Canned.ok("""{"choices":[{"message":{"content":"x"}}]}"""))
        AiProviderClient(profile(ProviderCatalog.OPENAI, id = "p-openai", secretName = "key-p-openai"), keys, openAiHttp).complete(userMessage())
        assertEquals("Bearer sk-openai", openAiHttp.only().headers["Authorization"])
        assertFalse(openAiHttp.only().body.contains("access-1"))
        // The account adapter sends only the account token; no stored API key reaches it.
        val accountHttp = FakeHttp(Canned.ok(streamOk))
        ChatGptAccountProvider(chatGptProfile(), account(Auth(issuer) { Canned.ok("{}") }), accountHttp).complete(userMessage())
        assertEquals("Bearer access-1", accountHttp.only().headers["Authorization"])
        assertTrue(accountHttp.only().headers.values.none { it.contains("sk-") })
        // Disconnecting the account leaves every API key alone, and the reverse.
        account(Auth(issuer) { Canned.ok("{}") }).disconnect()
        assertEquals("sk-openai", keys.map["key-p-openai"])
        assertEquals("sk-legacy", keys.map[null])
    }

    @Test fun nothingSecretLeaksThroughToString() {
        connected()
        val session = ChatGptSessionCodec.decode(storage.value!!)!!
        assertFalse(session.toString().contains("access-1") || session.toString().contains("refresh-1"))
        assertFalse(ChatGptAccountProvider::class.java.name.isEmpty())
        assertNull(ChatGptSessionCodec.decode("{\"client_id\":\"x\"}"))
    }
}
