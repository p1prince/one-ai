package com.oneai.plugin.sdk;

import com.oneai.plugin.sdk.IPluginCallback;

/**
 * Contract between ONE AI (host) and a plugin APK. Only ONE AI may call it; the SDK base service
 * rejects every other caller. Arguments travel as parallel key/value arrays so no custom parcelable
 * (and no Bundle) crosses the boundary. All sizes are bounded (see PluginBounds).
 */
interface IOneAiPlugin {
    /** Protocol handshake, JSON such as {"sdkApi":1}. The manifest XML stays the source of truth. */
    String describe();

    /** Runs a declared command asynchronously; the answer arrives on [callback] exactly once. */
    oneway void execute(String requestId, String commandName, in String[] argKeys, in String[] argValues, IPluginCallback callback);

    /** Declarative Bubble view as JSON for a service declared in the manifest, or null. */
    String view(String serviceId);

    /** Asks the plugin to stop the request. The host has already given up on it. */
    oneway void cancel(String requestId);
}
