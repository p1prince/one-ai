package com.oneai.plugin.sdk;

import android.app.PendingIntent;

/** Implemented by ONE AI. The plugin reports exactly one onResult or onError per request. */
oneway interface IPluginCallback {
    void onResult(String requestId, String title, String content);
    void onError(String requestId, String code, String message);
    /**
     * Plugin API 2. Optional, at most once per request and before onResult: a screen the plugin asks ONE AI to
     * open once the user agrees (for example another app, or the plugin's own permission screen). The plugin
     * only describes it; ONE AI decides, asks the user and then sends the PendingIntent. [label] is the plugin's
     * own claim about the screen and cannot be verified by ONE AI.
     */
    void onLaunch(String requestId, String label, in PendingIntent screen);
    /** Bounded progress line shown in Activity as plugin-originated. Never put secrets here. */
    void onActivity(String requestId, String message);
}
