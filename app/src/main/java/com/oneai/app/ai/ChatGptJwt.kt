package com.oneai.app.ai

import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * The little JWT handling the ChatGPT sign-in needs, on the JDK alone (no JOSE library): RS256 signature check against
 * the issuer's JWKS, the identity-token claim checks, and reading the ChatGPT account id. Pure, so it is JVM-tested with
 * tokens signed in the test.
 */
object ChatGptJwt {
    const val ISSUER = "https://auth.openai.com"
    private const val SKEW_MILLIS = 5_000L

    class Parts(val header: JSONObject, val payload: JSONObject, val signingInput: String, val signature: ByteArray)

    /** Splits and decodes a compact JWT, or null if it is not one. Nothing is verified here. */
    fun parse(token: String): Parts? = runCatching {
        val pieces = token.split(".")
        require(pieces.size == 3)
        val decoder = Base64.getUrlDecoder()
        Parts(
            JSONObject(String(decoder.decode(pieces[0]), Charsets.UTF_8)),
            JSONObject(String(decoder.decode(pieces[1]), Charsets.UTF_8)),
            pieces[0] + "." + pieces[1],
            decoder.decode(pieces[2])
        )
    }.getOrNull()

    /**
     * Verifies [token] as an RS256 identity token issued to [clientId] and returns its email (possibly empty).
     * Throws [AiProviderException] with code OAUTH_INVALID_ID_TOKEN naming the failed check, never the token.
     */
    fun verifyIdToken(token: String, jwksJson: String, clientId: String, nowMillis: Long): String {
        val parts = parse(token) ?: fail("The identity token is malformed.")
        if (parts.header.optString("alg") != "RS256") fail("Unexpected identity token signature algorithm.")
        val keyId = parts.header.optString("kid")
        val jwk = runCatching { JSONObject(jwksJson).getJSONArray("keys") }.getOrNull()
            ?.let { keys -> (0 until keys.length()).map { keys.getJSONObject(it) } }
            ?.firstOrNull { it.optString("kid") == keyId && it.optString("kty") == "RSA" }
            ?: fail("Could not find the signing key for the identity token.")
        val verified = runCatching {
            val decoder = Base64.getUrlDecoder()
            val key = KeyFactory.getInstance("RSA").generatePublic(
                RSAPublicKeySpec(BigInteger(1, decoder.decode(jwk.getString("n"))), BigInteger(1, decoder.decode(jwk.getString("e"))))
            )
            Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update(parts.signingInput.toByteArray(Charsets.US_ASCII))
                verify(parts.signature)
            }
        }.getOrDefault(false)
        if (!verified) fail("The identity token signature is invalid.")

        val claims = parts.payload
        if (claims.optString("iss") != ISSUER) fail("The identity issuer did not match.")
        val audience = audiences(claims)
        if (clientId !in audience) fail("The identity audience did not match this app's registration.")
        if (audience.size > 1 && claims.optString("azp") != clientId) fail("The identity authorized party did not match.")
        if (claims.optString("sub").isBlank()) fail("The identity token has no subject.")
        val issuedAt = claims.optLong("iat", Long.MIN_VALUE)
        if (issuedAt == Long.MIN_VALUE || issuedAt * 1000 > nowMillis + SKEW_MILLIS) fail("The identity token issue time is invalid.")
        val expires = claims.optLong("exp", Long.MIN_VALUE)
        if (expires == Long.MIN_VALUE || expires * 1000 < nowMillis - SKEW_MILLIS) fail("The identity token has expired.")
        return claims.optString("email")
    }

    /** The ChatGPT account id carried by a token, or "" when it has none. */
    fun accountId(token: String): String {
        val claims = parse(token)?.payload ?: return ""
        claims.optString("chatgpt_account_id").takeIf { it.isNotBlank() }?.let { return it }
        claims.optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id")?.takeIf { it.isNotBlank() }?.let { return it }
        return claims.optString("https://api.openai.com/auth.chatgpt_account_id")
    }

    /** Constant-time string comparison for OAuth `state` values. */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun audiences(claims: JSONObject): Set<String> {
        val value = claims.opt("aud")
        return when (value) {
            is String -> setOf(value)
            is org.json.JSONArray -> (0 until value.length()).map { value.optString(it) }.toSet()
            else -> emptySet()
        }
    }

    private fun fail(message: String): Nothing = throw AiProviderException("OAUTH_INVALID_ID_TOKEN", message)
}
