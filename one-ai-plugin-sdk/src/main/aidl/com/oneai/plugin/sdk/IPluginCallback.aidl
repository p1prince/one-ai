package com.oneai.plugin.sdk;

/** Implemented by ONE AI. The plugin reports exactly one onResult or onError per request. */
oneway interface IPluginCallback {
    void onResult(String requestId, String title, String content);
    void onError(String requestId, String code, String message);
    /** Bounded progress line shown in Activity as plugin-originated. Never put secrets here. */
    void onActivity(String requestId, String message);
}
