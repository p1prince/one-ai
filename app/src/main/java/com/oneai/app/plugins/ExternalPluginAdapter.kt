package com.oneai.app.plugins

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** A failure reported by, or while talking to, a plugin. The code is safe to show; the message is bounded. */
class PluginChannelException(val code: String, message: String) : Exception(message)

/**
 * Host-side view of one live connection to a plugin APK. The Android implementation wraps the bound
 * AIDL interface; tests use a fake. Everything that crosses it is already bounded by the SDK contract.
 */
interface PluginChannel : AutoCloseable {
    suspend fun describe(): String
    suspend fun execute(requestId: String, command: String, args: Map<String, String>, onActivity: (String) -> Unit): PluginActionOutput
    suspend fun view(serviceId: String): String?
    fun cancel(requestId: String)
}

/**
 * Opens connections. [verify] must be called after the connection is established and before any call is
 * made, and the connection must be refused when it returns false (this closes the gap between the
 * signature check and the bind).
 */
fun interface PluginChannelFactory {
    suspend fun open(packageName: String, verify: () -> Boolean): PluginChannel
}

object PluginHandshake {
    private val sdkApi = Regex("\"sdkApi\"\\s*:\\s*(\\d{1,4})")

    /** The protocol level the plugin speaks, or null if the reply is not a valid handshake. */
    fun parse(json: String?): Int? =
        json?.takeIf { it.length <= 200 }?.let { sdkApi.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    fun isSupported(json: String?, hostLevel: Int = PluginApi.LEVEL): Boolean =
        parse(json)?.let { it in 1..hostLevel } == true
}

/**
 * Executes the commands a trusted external plugin declared. The host decides everything: the command must
 * be in the trusted manifest, its capability must match the declaration, and the plugin must still be
 * trusted (signature pinned, no update waiting) at call time. The plugin only executes.
 */
class ExternalPluginAdapter(
    private val trusted: ExternalPluginManifest,
    private val trust: PluginTrustRegistry,
    private val channels: PluginChannelFactory,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val report: (pluginId: String, message: String) -> Unit = { _, _ -> },
    private val newRequestId: () -> String = { UUID.randomUUID().toString() }
) : PluginAdapter {
    override val manifest: PluginManifest = trusted.toRecordManifest()

    override suspend fun execute(input: PluginActionInput): PluginActionOutput {
        val declared = trusted.commands.firstOrNull { it.name == input.actionId }
            ?: throw IllegalArgumentException("The plugin does not declare that command")
        require(declared.capability == input.capability) { "Capability does not match the command's declaration" }
        requireStillTrusted()

        val packageName = trusted.packageName
        val channel = channels.open(packageName) { isStillTrusted() }
        val requestId = newRequestId()
        try {
            return withTimeout(timeoutMs) {
                channel.execute(requestId, declared.name, input.values) { line -> report(trusted.id, "Plugin: $line") }
            }
        } catch (timeout: TimeoutCancellationException) {
            runCatching { channel.cancel(requestId) }
            report(trusted.id, "Timed out after ${timeoutMs / 1000}s: ${declared.name}")
            throw PluginChannelException("TIMEOUT", "The plugin took too long to respond")
        } catch (cancelled: CancellationException) {
            runCatching { channel.cancel(requestId) }
            throw cancelled
        } catch (failure: PluginChannelException) {
            report(trusted.id, "Failed (${failure.code}): ${declared.name}")
            throw failure
        } finally {
            runCatching { channel.close() }
        }
    }

    /** Bubble view JSON for a service the plugin declared, or null. Same trust rules as a command. */
    suspend fun view(serviceId: String): String? {
        if (trusted.bubble?.service != serviceId || !isStillTrusted()) return null
        val channel = channels.open(trusted.packageName) { isStillTrusted() }
        return try {
            withTimeout(VIEW_TIMEOUT_MS) { channel.view(serviceId) }
        } catch (e: TimeoutCancellationException) {
            null
        } finally {
            runCatching { channel.close() }
        }
    }

    private fun isStillTrusted(): Boolean =
        (trust.check(trusted.packageName) as? TrustCheck.Trusted)?.entry?.trustedManifest == trusted

    private fun requireStillTrusted() {
        if (!isStillTrusted()) throw SecurityException("The plugin is no longer trusted in this form")
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 35_000L // SDK default is 30 s; the host waits slightly longer for its answer
        const val VIEW_TIMEOUT_MS = 5_000L
    }
}
