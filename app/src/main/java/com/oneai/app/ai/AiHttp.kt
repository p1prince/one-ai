package com.oneai.app.ai

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Size and time limits shared by every provider transport. */
object AiHttpLimits {
    const val CONNECT_TIMEOUT_MS = 20_000
    const val READ_TIMEOUT_MS = 60_000
    /** Streaming answers can pause for a long time before the first token. */
    const val STREAM_READ_TIMEOUT_MS = 180_000
    const val MAX_BODY_BYTES = 1024 * 1024
    const val MAX_STREAM_BYTES = 4 * 1024 * 1024
}

/**
 * One outgoing HTTP call. [headers] usually carry a credential, so [toString] never prints them.
 * Adapters validate [url] before building a request; the transport does not follow redirects.
 */
class AiHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
    val method: String = "POST",
    val readTimeoutMs: Int = AiHttpLimits.READ_TIMEOUT_MS
) {
    override fun toString() = "AiHttpRequest($method)"
}

/** The answer to an [AiHttpRequest]. Must be closed. Reading is bounded so a hostile server cannot exhaust memory. */
interface AiHttpResponse : Closeable {
    val status: Int

    /** The whole body as UTF-8, `""` when there is none, or `null` when it is larger than [maxBytes]. */
    fun readBody(maxBytes: Int): String?

    /** The body as lines (for `text/event-stream`), read lazily. Throws RESPONSE_TOO_LARGE past [maxBytes]. */
    fun lines(maxBytes: Int = AiHttpLimits.MAX_STREAM_BYTES): Sequence<String>
}

/** The only place the AI layer touches the network. Tests replace it with canned responses, so CI needs no real key. */
fun interface AiHttp {
    fun open(request: AiHttpRequest): AiHttpResponse
}

/** Production transport over [HttpURLConnection]. No Android types, so it also runs in JVM tests against a local server. */
class HttpUrlConnectionAiHttp : AiHttp {
    override fun open(request: AiHttpRequest): AiHttpResponse {
        val connection = URL(request.url).openConnection() as? HttpURLConnection
            ?: throw IOException("Could not open the provider connection.")
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = request.method
            connection.connectTimeout = AiHttpLimits.CONNECT_TIMEOUT_MS
            connection.readTimeout = request.readTimeoutMs
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (request.method == "POST") {
                connection.doOutput = true
                connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            return Live(connection, connection.responseCode)
        } catch (error: Throwable) {
            connection.disconnect()
            throw error
        }
    }

    private class Live(private val connection: HttpURLConnection, override val status: Int) : AiHttpResponse {
        private fun stream(): InputStream? = if (status in 200..299) connection.inputStream else connection.errorStream

        override fun readBody(maxBytes: Int): String? {
            val input = stream() ?: return ""
            return input.use { AiResponseBodyReader.readUtf8(it, maxBytes) }
        }

        override fun lines(maxBytes: Int): Sequence<String> {
            val input = stream() ?: return emptySequence()
            val reader = Limited(input, maxBytes.toLong()).bufferedReader(Charsets.UTF_8)
            return generateSequence { reader.readLine() }
        }

        override fun close() = connection.disconnect()
    }

    /** Counts bytes and refuses to deliver more than the ceiling. */
    private class Limited(private val inner: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            val value = inner.read()
            if (value >= 0 && --remaining < 0) throw tooLarge()
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = inner.read(buffer, offset, length)
            if (count > 0) {
                remaining -= count
                if (remaining < 0) throw tooLarge()
            }
            return count
        }

        override fun close() = inner.close()

        private fun tooLarge() = AiProviderException("RESPONSE_TOO_LARGE", "The AI provider response exceeded the safety limit.")
    }
}
