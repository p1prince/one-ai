package com.oneai.app.plugins

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import com.oneai.plugin.sdk.IOneAiPlugin
import com.oneai.plugin.sdk.IPluginCallback
import com.oneai.plugin.sdk.PluginBounds
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Opens a bound-service connection to a plugin APK. The sequence is: resolve exactly one exported service
 * in the package, bind, then (off the main thread) re-verify trust and check the protocol handshake before
 * returning the channel. A channel that fails either check is unbound and never used.
 */
class BoundPluginChannelFactory(
    private val context: Context,
    private val connectTimeoutMs: Long = 5_000,
    private val launchBroker: PluginLaunchApprovalBroker = PluginLaunchApprovalBroker()
) : PluginChannelFactory {

    override suspend fun open(packageName: String, verify: () -> Boolean): PluginChannel {
        val component = resolve(packageName)
        val intent = Intent(PluginApi.SERVICE_ACTION).setComponent(component)
        val channel = try {
            withTimeout(connectTimeoutMs) { connect(intent) }
        } catch (e: TimeoutCancellationException) {
            throw PluginChannelException("CONNECT_TIMEOUT", "The plugin did not start in time")
        }
        try {
            val handshake = withContext(Dispatchers.IO) {
                if (!verify()) throw PluginChannelException("NOT_TRUSTED", "The plugin is no longer trusted")
                withTimeout(connectTimeoutMs) { channel.describe() }
            }
            if (!PluginHandshake.isSupported(handshake)) throw PluginChannelException("INCOMPATIBLE", "The plugin speaks an unsupported protocol")
        } catch (t: Throwable) {
            channel.close()
            throw t
        }
        return channel
    }

    private fun resolve(packageName: String): ComponentName {
        val pm = context.packageManager
        val intent = Intent(PluginApi.SERVICE_ACTION).setPackage(packageName)
        @Suppress("DEPRECATION")
        val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            pm.queryIntentServices(intent, 0)
        }.mapNotNull { it.serviceInfo }.filter { it.exported && it.permission == null && it.packageName == packageName }
        val service = services.singleOrNull()
            ?: throw PluginChannelException("NOT_AVAILABLE", "The plugin must expose exactly one plugin service")
        return ComponentName(service.packageName, service.name)
    }

    private suspend fun connect(intent: Intent): AidlPluginChannel = suspendCancellableCoroutine { continuation ->
        val binding = Binding(context)
        val connection = object : ServiceConnection {
            @Volatile private var channel: AidlPluginChannel? = null

            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val plugin = service?.let { IOneAiPlugin.Stub.asInterface(it) }
                if (plugin == null || !continuation.isActive) { binding.unbind(); return }
                channel = AidlPluginChannel(plugin, binding, context, intent.component?.packageName.orEmpty(), launchBroker)
                continuation.resume(channel!!)
            }

            override fun onServiceDisconnected(name: ComponentName?) { channel?.markDead() }

            override fun onBindingDied(name: ComponentName?) {
                channel?.markDead()
                if (continuation.isActive) continuation.resumeWithException(PluginChannelException("PLUGIN_DIED", "The plugin stopped unexpectedly"))
                binding.unbind()
            }

            override fun onNullBinding(name: ComponentName?) {
                if (continuation.isActive) continuation.resumeWithException(PluginChannelException("NOT_AVAILABLE", "The plugin did not provide a service"))
                binding.unbind()
            }
        }
        binding.connection = connection
        val started = try { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) } catch (e: SecurityException) { false }
        if (!started) {
            binding.unbind()
            continuation.resumeWithException(PluginChannelException("NOT_AVAILABLE", "The plugin could not be started"))
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { binding.unbind() }
    }

    /** Unbinds at most once, whichever path gets there first. */
    private class Binding(private val context: Context) {
        private val unbound = AtomicBoolean(false)
        lateinit var connection: ServiceConnection
        fun unbind() {
            if (unbound.compareAndSet(false, true) && ::connection.isInitialized) runCatching { context.unbindService(connection) }
        }
    }

    private class AidlPluginChannel(
        private val plugin: IOneAiPlugin,
        private val binding: Binding,
        private val context: Context,
        private val pluginPackage: String,
        private val launchBroker: PluginLaunchApprovalBroker
    ) : PluginChannel {
        @Volatile private var dead = false
        @Volatile private var pending: CancellableContinuation<PluginActionOutput>? = null
        @Volatile private var pendingRequestId: String? = null

        fun markDead() {
            dead = true
            pendingRequestId?.let(launchBroker::reject)
            pending?.takeIf { it.isActive }?.resumeWithException(PluginChannelException("PLUGIN_DIED", "The plugin stopped unexpectedly"))
        }

        override suspend fun describe(): String = withContext(Dispatchers.IO) { call { plugin.describe() }.orEmpty() }

        override suspend fun view(serviceId: String): String? = withContext(Dispatchers.IO) { call { plugin.view(serviceId) } }

        override suspend fun execute(
            requestId: String, command: String, args: Map<String, String>, activity: (String) -> Unit
        ): PluginActionOutput = suspendCancellableCoroutine { continuation ->
            if (dead) {
                continuation.resumeWithException(PluginChannelException("PLUGIN_DIED", "The plugin stopped unexpectedly"))
                return@suspendCancellableCoroutine
            }
            pending = continuation
            pendingRequestId = requestId
            val callback = object : IPluginCallback.Stub() {
                private val terminal = AtomicBoolean(false)
                private val launchDelivered = AtomicBoolean(false)

                override fun onResult(id: String?, title: String?, content: String?) {
                    if (id == requestId && continuation.isActive && terminal.compareAndSet(false, true)) {
                        continuation.resume(PluginActionOutput(title.orEmpty(), content.orEmpty()))
                    }
                }

                override fun onError(id: String?, code: String?, message: String?) {
                    if (id == requestId && continuation.isActive && terminal.compareAndSet(false, true)) {
                        launchBroker.reject(requestId)
                        continuation.resumeWithException(
                            PluginChannelException(PluginBounds.safeErrorCode(code), PluginBounds.bound(message, PluginBounds.MAX_ERROR_MESSAGE))
                        )
                    }
                }

                // Stage 9.4b: capture only a plugin-created, identity-matched PendingIntent.
                // Stage 9.4c will surface it in the host Bubble approval UI; this code never sends it.
                override fun onLaunch(id: String?, label: String?, screen: android.app.PendingIntent?) {
                    if (id != requestId || screen == null || !continuation.isActive || terminal.get()) return
                    if (!launchDelivered.compareAndSet(false, true)) return
                    val expectedUid = runCatching {
                        context.packageManager.getApplicationInfo(pluginPackage, 0).uid
                    }.getOrNull() ?: return
                    val immutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) screen.isImmutable else null
                    if (!PluginLaunchIdentityPolicy.accepts(
                            pluginPackage, expectedUid, screen.creatorPackage, screen.creatorUid, immutable
                        )
                    ) return
                    launchBroker.offer(PluginLaunchRequest(requestId, pluginPackage, label.orEmpty(), screen))
                }

                override fun onActivity(id: String?, message: String?) {
                    if (id == requestId && continuation.isActive) activity(PluginBounds.bound(message, PluginBounds.MAX_ACTIVITY))
                }
            }
            try {
                plugin.execute(requestId, command, args.keys.toTypedArray(), args.values.toTypedArray(), callback)
            } catch (e: RemoteException) {
                launchBroker.reject(requestId)
                continuation.resumeWithException(PluginChannelException("PLUGIN_UNAVAILABLE", "The plugin could not be reached"))
            } catch (e: SecurityException) {
                launchBroker.reject(requestId)
                continuation.resumeWithException(PluginChannelException("PLUGIN_UNAVAILABLE", "The plugin refused the request"))
            }
            continuation.invokeOnCancellation {
                pending = null
                launchBroker.reject(requestId)
            }
        }

        override fun cancel(requestId: String) {
            launchBroker.reject(requestId)
            runCatching { plugin.cancel(requestId) }
        }

        override fun close() { binding.unbind() }

        private fun <T> call(block: () -> T): T = try {
            block()
        } catch (e: RemoteException) {
            throw PluginChannelException("PLUGIN_UNAVAILABLE", "The plugin could not be reached")
        } catch (e: SecurityException) {
            throw PluginChannelException("PLUGIN_UNAVAILABLE", "The plugin refused the request")
        }
    }
}
