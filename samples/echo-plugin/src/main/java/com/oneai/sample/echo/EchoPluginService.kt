package com.oneai.sample.echo

import android.content.pm.ApplicationInfo
import com.oneai.plugin.sdk.CallContext
import com.oneai.plugin.sdk.HostVerifier
import com.oneai.plugin.sdk.OneAiPluginService
import com.oneai.plugin.sdk.PluginOutput

/**
 * Entry point ONE AI binds to. Everything here is wiring; the behaviour is in [EchoCommands].
 * Declared in AndroidManifest.xml as an exported service with the ONE AI plugin intent action.
 */
class EchoPluginService : OneAiPluginService() {
    private val commands = EchoCommands()

    override fun onCommand(command: String, args: Map<String, String>, context: CallContext): PluginOutput =
        commands.run(command, args, context)

    /** The Bubble view is plain data (JSON); the core draws it. Only the service declared in the manifest exists. */
    override fun onView(serviceId: String): String? =
        if (serviceId == BUBBLE_SERVICE) resources.openRawResource(R.raw.echo_view).bufferedReader(Charsets.UTF_8).use { it.readText() }
        else null

    /**
     * Who may call this plugin. A release build trusts only ONE AI's release certificates (the SDK default,
     * which currently fails closed because no release key exists yet). A debuggable build, such as the one
     * used on an emulator, accepts the ONE AI package under any certificate. Never ship that in a release.
     */
    override fun hostVerifier(): HostVerifier =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) HostVerifier(allowAnyCertificate = true)
        else HostVerifier()

    private companion object {
        const val BUBBLE_SERVICE = "echo"
    }
}
