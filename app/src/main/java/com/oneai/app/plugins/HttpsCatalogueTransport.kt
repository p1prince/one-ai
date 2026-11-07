package com.oneai.app.plugins

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Production [CatalogueTransport]: plain HTTPS GET with the platform trust store, short timeouts, a hard size
 * cap and redirects followed only to other https URLs ([RedirectPolicy]). No cookies, no credentials, no
 * cleartext fallback. Not exercised by JVM tests (no network); real behaviour is a Stage 11 item.
 */
class HttpsCatalogueTransport(
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 8_000
) : CatalogueTransport {
    override fun fetch(url: String, maxBytes: Int): Result<String> = runCatching {
        var current = CatalogueValidator.validateUrl(url).getOrThrow()
        for (attempt in 0..RedirectPolicy.MAX_REDIRECTS) {
            val connection = URL(current).openConnection() as? HttpsURLConnection ?: throw IOException("Not an https connection")
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                when (val code = connection.responseCode) {
                    java.net.HttpURLConnection.HTTP_OK -> return@runCatching readBounded(connection.inputStream, maxBytes)
                    301, 302, 303, 307, 308 -> current = RedirectPolicy.resolve(current, connection.getHeaderField("Location")).getOrThrow()
                    else -> throw IOException("HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun readBounded(input: InputStream, maxBytes: Int): String = input.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (out.size() + read > maxBytes) throw IOException("Response too large")
            out.write(buffer, 0, read)
        }
        out.toString(Charsets.UTF_8.name())
    }
}
