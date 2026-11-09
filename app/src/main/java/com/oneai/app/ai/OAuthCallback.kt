package com.oneai.app.ai

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import kotlinx.coroutines.CancellationException

/**
 * Handling of the browser's return to the app after sign-in. [handle] is pure: it looks at one HTTP request line and
 * says what to answer and whether it was this attempt's callback. A request is accepted only when the path is the
 * callback path AND `state` equals the value generated for this attempt, compared in constant time, so a stale tab, a
 * browser prefetch or a forged request cannot complete or poison a sign-in.
 */
object OAuthCallback {
    const val PATH = "/auth/callback"

    class Reply(val status: Int, val title: String, val message: String)

    /** [params] is non-null only for a callback whose state matched. */
    class Handled(val reply: Reply, val params: Map<String, String>?)

    fun handle(requestLine: String, expectedState: String): Handled {
        val pieces = requestLine.trim().split(" ")
        val method = pieces.getOrNull(0).orEmpty()
        val target = pieces.getOrNull(1) ?: "/"
        val path = target.substringBefore('?')
        if (method != "GET" || path != PATH) {
            return Handled(Reply(404, "Not found", "This address is only used to finish signing in to ONE AI."), null)
        }
        val params = parseQuery(target.substringAfter('?', ""))
        val returned = params["state"].orEmpty()
        if (returned.isEmpty() || !ChatGptJwt.constantTimeEquals(expectedState, returned)) {
            return Handled(Reply(400, "Sign-in page is out of date", "Return to ONE AI and try signing in again."), null)
        }
        return Handled(Reply(200, "Authorization received", "You can safely return to ONE AI."), params)
    }

    fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        query.split("&").forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            val decodedName = runCatching { URLDecoder.decode(name, "UTF-8") }.getOrNull() ?: return@forEach
            val decodedValue = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: return@forEach
            result.putIfAbsent(decodedName, decodedValue)
        }
        return result
    }

    /** The complete HTTP response bytes for [reply]. The page shows only fixed text, never anything from the request. */
    fun render(reply: Reply): ByteArray {
        val html = ("<html><head><meta name='viewport' content='width=device-width'></head>" +
            "<body style='font-family:sans-serif;padding:32px'><h2>" + reply.title +
            "</h2><p>" + reply.message + "</p></body></html>").toByteArray(Charsets.UTF_8)
        val reason = when (reply.status) { 200 -> "OK"; 404 -> "Not Found"; else -> "Bad Request" }
        val head = ("HTTP/1.1 " + reply.status + " " + reason +
            "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + html.size +
            "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        return head + html
    }
}

/**
 * The listener behind the redirect address. It binds 127.0.0.1 only (never an outside interface), tries each registered
 * port in order, and answers requests until this attempt's own callback arrives or the time runs out.
 */
class LoopbackReceiver private constructor(private val server: ServerSocket) : AutoCloseable {
    val port: Int get() = server.localPort
    @Volatile private var closed = false

    /** Blocks until the matching callback arrives; returns its parameters. */
    fun awaitCallback(expectedState: String): Map<String, String> {
        while (true) {
            val client = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                throw AiProviderException("OAUTH_TIMEOUT", "ChatGPT sign-in timed out. Return to ONE AI and try again.")
            } catch (error: SocketException) {
                if (closed) throw CancellationException("Sign-in was cancelled.")
                throw AiProviderException("OAUTH_FAILED", "The sign-in receiver stopped unexpectedly.", error)
            }
            try {
                client.soTimeout = 15_000
                val input = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                val requestLine = input.readLine().orEmpty()
                while (true) {
                    val header = input.readLine() ?: break
                    if (header.isEmpty()) break
                }
                val handled = OAuthCallback.handle(requestLine, expectedState)
                client.getOutputStream().apply {
                    write(OAuthCallback.render(handled.reply))
                    flush()
                }
                handled.params?.let { return it }
            } catch (_: java.io.IOException) {
                // A probe that disconnected or stalled must not end the attempt; keep waiting for the real callback.
            } finally {
                runCatching { client.close() }
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
    }

    companion object {
        /** Binds the first free port in [ports] on 127.0.0.1. Port 0 picks any free port (used in tests). */
        fun bind(ports: List<Int>, timeoutMs: Int = 600_000): LoopbackReceiver {
            val server = ports.firstNotNullOfOrNull { port ->
                runCatching {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                        soTimeout = timeoutMs
                    }
                }.getOrNull()
            } ?: throw AiProviderException(
                "OAUTH_PORT_BUSY",
                "ONE AI could not open its local sign-in receiver on port ${ports.joinToString(" or ")}. Close another app using it and try again."
            )
            return LoopbackReceiver(server)
        }
    }
}
