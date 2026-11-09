package com.oneai.app.ai

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class OAuthCallbackTest {
    private val path = OAuthCallback.PATH

    @Test fun aMatchingStateIsAccepted() {
        val handled = OAuthCallback.handle("GET $path?code=abc%20d&state=S1 HTTP/1.1", "S1")
        assertEquals(200, handled.reply.status)
        assertEquals("abc d", handled.params!!["code"])
    }

    @Test fun aWrongOrMissingStateIsRejected() {
        listOf("GET $path?code=a&state=OTHER HTTP/1.1", "GET $path?code=a HTTP/1.1", "GET $path?code=a&state= HTTP/1.1").forEach {
            val handled = OAuthCallback.handle(it, "S1")
            assertEquals(it, 400, handled.reply.status)
            assertNull(it, handled.params)
        }
    }

    @Test fun otherPathsAndMethodsAreIgnored() {
        listOf("GET /favicon.ico HTTP/1.1", "POST $path?state=S1 HTTP/1.1", "GET / HTTP/1.1", "").forEach {
            val handled = OAuthCallback.handle(it, "S1")
            assertEquals(it, 404, handled.reply.status)
            assertNull(handled.params)
        }
    }

    @Test fun providerErrorsReachTheCallerWhenTheStateMatches() {
        val handled = OAuthCallback.handle("GET $path?error=access_denied&error_description=No&state=S1 HTTP/1.1", "S1")
        assertEquals("access_denied", handled.params!!["error"])
    }

    @Test fun theReplyPageNeverEchoesTheRequest() {
        val handled = OAuthCallback.handle("GET $path?state=<script>alert(1)</script> HTTP/1.1", "S1")
        val page = String(OAuthCallback.render(handled.reply))
        assertFalse(page.contains("script"))
        assertTrue(page.startsWith("HTTP/1.1 400"))
    }

    @Test fun queryParsingDecodesAndKeepsTheFirstValue() {
        val q = OAuthCallback.parseQuery("a=1&a=2&b=%41%20c&c&=x&%zz=1")
        assertEquals("1", q["a"])
        assertEquals("A c", q["b"])
        assertEquals("", q["c"])
        assertNull(q["%zz"])
    }

    // ---- real socket ------------------------------------------------------------------------------------------------

    private fun get(port: Int, target: String): Int {
        val c = URL("http://127.0.0.1:$port$target").openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 3000
        return try { c.responseCode } finally { c.disconnect() }
    }

    @Test fun theReceiverIgnoresStrangersAndAcceptsOnlyTheMatchingCallback() {
        LoopbackReceiver.bind(listOf(0), timeoutMs = 10_000).use { receiver ->
            assertTrue(receiver.port > 0)
            val result = AtomicReference<Map<String, String>>()
            val done = CountDownLatch(1)
            thread { result.set(receiver.awaitCallback("GOOD")); done.countDown() }
            assertEquals(404, get(receiver.port, "/other"))
            assertEquals(400, get(receiver.port, "$path?code=evil&state=BAD"))
            assertEquals(1, done.count)
            assertEquals(200, get(receiver.port, "$path?code=real&state=GOOD"))
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("real", result.get()["code"])
        }
    }

    @Test fun theReceiverListensOnLoopbackOnly() {
        LoopbackReceiver.bind(listOf(0)).use { receiver ->
            val field = receiver.javaClass.getDeclaredField("server").apply { isAccessible = true }
            val server = field.get(receiver) as java.net.ServerSocket
            assertTrue(server.inetAddress.isLoopbackAddress)
        }
    }

    @Test fun bindFallsThroughToTheNextPortAndFailsCleanlyWhenNoneIsFree() {
        LoopbackReceiver.bind(listOf(0)).use { first ->
            LoopbackReceiver.bind(listOf(first.port, 0)).use { second -> assertTrue(second.port != first.port) }
            val e = assertThrows(AiProviderException::class.java) { LoopbackReceiver.bind(listOf(first.port)) }
            assertEquals("OAUTH_PORT_BUSY", e.code)
        }
    }

    @Test fun closingTheReceiverCancelsAWaitingSignIn() {
        val receiver = LoopbackReceiver.bind(listOf(0), timeoutMs = 10_000)
        val error = AtomicReference<Throwable>()
        val t = thread { try { receiver.awaitCallback("S") } catch (e: Throwable) { error.set(e) } }
        Thread.sleep(200)
        receiver.close()
        t.join(5000)
        assertTrue(error.get() is CancellationException)
    }

    @Test fun aTimeoutIsReportedAsATimeout() {
        LoopbackReceiver.bind(listOf(0), timeoutMs = 200).use {
            val e = assertThrows(AiProviderException::class.java) { it.awaitCallback("S") }
            assertEquals("OAUTH_TIMEOUT", e.code)
        }
        assertNotNull(OAuthCallback.render(OAuthCallback.Reply(200, "t", "m")))
    }
}
