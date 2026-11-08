package com.oneai.app.core

/**
 * The one list of capability identifiers a plugin may declare (Stage 10 SDK review, decision D10 / D-Q).
 *
 * The vocabulary is deliberately **closed and versioned**: the host must know what approval and what Android
 * permission each capability implies, so an identifier it does not know cannot be risk-classified and is
 * rejected both when a manifest is validated and when the gateway checks an action. The identifiers are
 * capability classes, not plugins; nothing here implies that any capability plugin ships with ONE AI.
 * Adding an identifier is a Core release change and must be noted against the plugin API level.
 *
 * `PluginManifestValidator.supportedCapabilities` and `CapabilityPolicy` both derive from this object so the
 * lists can no longer drift apart. `PLUGIN_SDK.md` and `docs/PLUGIN_DEVELOPER_GUIDE.md` must list exactly
 * these identifiers (a unit test compares them).
 */
object CapabilityVocabulary {
    /** Every identifier a plugin may declare, in documentation order. */
    val all: Set<String> = linkedSetOf(
        "commands", "internet", "screen_capture", "microphone", "audio",
        "device_actions", "accessibility", "automation", "files", "app_launch"
    )

    /** Identifiers whose every use asks the user first (one-use, for the exact action). A subset of [all]. */
    val approvalRequired: Set<String> = linkedSetOf(
        "screen_capture", "microphone", "device_actions", "accessibility",
        "automation", "files", "internet", "app_launch"
    )
}
