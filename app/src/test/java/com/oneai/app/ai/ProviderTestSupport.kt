package com.oneai.app.ai

import com.oneai.app.security.SecretRead
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** A canned HTTP answer. [body] is the whole body; for a stream each line of it is a line of the event stream. */
class Canned(val status: Int, val body: String = "") {
    companion object {
        fun ok(body: String) = Canned(200, body)
        fun json(status: Int, body: String) = Canned(status, body)
    }
}

/**
 * Mock transport: no socket is ever opened, so tests need no real key and no network. Every request is recorded; a handler
 * decides the answer, or throws to simulate a network failure.
 */
class FakeHttp(private val handler: (AiHttpRequest) -> Canned) : AiHttp {
    val requests = ArrayList<AiHttpRequest>()
    constructor(answer: Canned) : this({ answer })

    override fun open(request: AiHttpRequest): AiHttpResponse {
        synchronized(requests) { requests += request }
        val canned = handler(request)
        return object : AiHttpResponse {
            override val status = canned.status
            override fun readBody(maxBytes: Int): String? = if (canned.body.toByteArray().size > maxBytes) null else canned.body
            override fun lines(maxBytes: Int): Sequence<String> = canned.body.lineSequence()
            override fun close() {}
        }
    }

    fun only(): AiHttpRequest = requests.single()
}

class MapSecrets : ProfileSecrets {
    val map = HashMap<String?, String>()
    val unreadable = HashSet<String?>()
    override fun read(secretName: String?): SecretRead =
        if (secretName in unreadable) SecretRead.Unreadable
        else map[secretName]?.let { SecretRead.Present(it) } ?: SecretRead.Absent
    override fun save(secretName: String?, value: String) {
        if (value.isBlank()) map.remove(secretName) else map[secretName] = value
    }
}

class MemoryProfileStorage(
    var profiles: String? = null,
    var activeId: String? = null,
    val legacy: LegacyProviderSettings = LegacyProviderSettings(null, null, null)
) : ProviderProfileStorage {
    override fun readProfiles() = profiles
    override fun writeProfiles(json: String) { profiles = json }
    override fun readActiveId() = activeId
    override fun writeActiveId(id: String) { activeId = id }
    override fun legacySettings() = legacy
}

class MemorySession : ChatGptSessionStorage {
    var value: String? = null
    var unreadable = false
    override fun read(): SecretRead = when {
        unreadable -> SecretRead.Unreadable
        value == null -> SecretRead.Absent
        else -> SecretRead.Present(value!!)
    }
    override fun write(value: String) { this.value = value }
    override fun clear() { value = null }
}

fun profile(
    kind: String,
    id: String = "p-$kind",
    name: String = kind,
    endpoint: String = ProviderCatalog.descriptor(kind)!!.defaultEndpoint,
    model: String = ProviderCatalog.descriptor(kind)!!.defaultModel,
    secretName: String? = "key-$id"
) = AiProviderProfile(id, name, kind, endpoint, model, secretName)

fun userMessage(text: String = "Hello") = listOf(AiMessage("user", text))

/** Signs identity tokens so verification is tested against real RS256 signatures. */
class TestIssuer(val kid: String = "test-key") {
    private val pair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val enc = Base64.getUrlEncoder().withoutPadding()

    val jwks: String get() {
        val pub = pair.public as RSAPublicKey
        fun b64(n: BigInteger) = enc.encodeToString(n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
        return """{"keys":[{"kty":"RSA","kid":"$kid","alg":"RS256","use":"sig","n":"${b64(pub.modulus)}","e":"${b64(pub.publicExponent)}"}]}"""
    }

    fun token(
        clientId: String = ChatGptAccount.CLIENT_ID,
        issuer: String = ChatGptJwt.ISSUER,
        now: Long,
        lifetimeSeconds: Long = 3600,
        email: String = "user@example.com",
        accountId: String? = "acct-123",
        header: JSONObject = JSONObject().put("alg", "RS256").put("kid", kid).put("typ", "JWT"),
        tamper: Boolean = false
    ): String {
        val payload = JSONObject().put("iss", issuer).put("aud", clientId).put("sub", "user-1").put("email", email)
            .put("iat", now / 1000).put("exp", now / 1000 + lifetimeSeconds)
        if (accountId != null) payload.put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", accountId))
        val signingInput = enc.encodeToString(header.toString().toByteArray()) + "." + enc.encodeToString(payload.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update(signingInput.toByteArray(Charsets.US_ASCII))
            sign()
        }
        val sig = if (tamper) ByteArray(signature.size) else signature
        return signingInput + "." + enc.encodeToString(sig)
    }
}
