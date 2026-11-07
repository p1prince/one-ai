# ONE AI Security Model — Foundation

## Capability declarations are not grants

A plugin manifest may declare a capability, but installation and enablement do not grant Android permissions and do not authorize an action. Each execution path must verify that the plugin is installed and enabled, that the adapter is trusted, and that the requested capability is declared and permitted.

## Risk policy

The current policy blocks disabled plugins, malformed action requests, and unknown capabilities. Command capabilities are classified as low risk. Plugin network access is sensitive because it can transmit user data outside the device and therefore requires just-in-time approval. Screen capture, microphone, device actions, accessibility, automation, and file access are classified as sensitive and require just-in-time user approval.

This is a baseline classification, not a substitute for action-specific review. An adapter may impose stricter checks. No adapter may downgrade a sensitive action.

## One-time approvals

The foundation approval gate issues a process-local ticket only after the caller has obtained user approval. Each ticket is bound to one plugin ID, action ID, capability, and exact user-visible action description; it expires quickly, can be revoked, and is consumed on the first attempt (including a mismatched attempt). Tickets are not persisted and cannot be reused as standing permission grants. Disabling or removing a plugin revokes all pending tickets; a process restart naturally invalidates every ticket.

The reusable `PluginActionButton` is the user-facing action entry point for Compose-based trusted adapters. The Floating Bubble implements equivalent explicit approval paths for Screen Intelligence, Web Research and Voice Interface. Screen capture and image upload are separate sensitive actions: the first approval permits one capture session; a second approval is required before the selected image is transmitted to the configured AI provider. Web Research requires a separate exact-action approval before a query is sent to Brave Search. Voice Interface requires a separate exact-action approval before the app opens the one-shot speech capture Activity; Android `RECORD_AUDIO` permission is then requested at runtime, and the recognized transcript is sent to the configured AI provider. The runtime rechecks that the plugin remains installed, enabled, and registered before issuing a one-use ticket.

## Android permission boundaries

Use Android's native permission and consent mechanisms in addition to ONE AI's own approval:
- Screen capture: ONE AI's exact-action approval followed by Android MediaProjection user consent for each capture session. The Stage 7 capture service is one-shot and the region selector runs outside the Bubble.
- Microphone: RECORD_AUDIO is declared in the manifest and requested at action time, only after ONE AI's own explicit action approval. Denial prevents adapter execution.
- Accessibility: user enables the service in Android Settings.
- Device settings: supported intents/APIs and system confirmation where required.
- Files: Storage Access Framework or scoped permissions.
- Internet: INTERNET manifest permission (normal permission).

A ONE AI approval ticket never bypasses Android permission checks, system dialogs, app sandboxing, or OS restrictions.

## Audit

The action gateway records policy decisions and approval events. PluginRuntime now also records bounded completion, failure, and cancellation outcomes through the same audit logger; it deliberately excludes plugin output, request values, and exception text. Feature-specific adapters must still avoid recording secrets or unnecessary captured content.

## Integration status and remaining limitations

Stage 8 now contains a trusted Web Research adapter backed by the Brave Search API and a Screen Intelligence adapter backed by an OpenAI-compatible HTTPS vision provider. Both use the central plugin runtime and require explicit action approval for internet transmission. Web Research stores its API credential in a separate Android Keystore-encrypted slot; it does not overwrite the primary AI provider credential. These integrations are implemented in source but remain subject to CI and end-to-end device verification.

App Control is implemented as an explicitly approved launcher-only integration and passed CI on runs #388–#389. Device Control is being implemented as an explicitly approved hand-off to a fixed allowlist of Android Settings screens; it does not silently change restricted settings. Voice Interface passed CI on runs #366–#369. Physical-device checks for MediaProjection, capture-to-analysis, web search credentials, voice services, app launch and Settings hand-off remain scheduled for Stage 11. Accessibility still requires a guided user-enabled Settings hand-off. Other catalogue entries remain inert until their adapters are implemented.


## App launch boundary

The App Control adapter exposes only an explicit launch action for launcher-visible packages. It uses Android's package-visibility query for launcher activities and does not request broad package visibility. Each launch requires a fresh approval bound to the selected app label and package name. The adapter resolves the package's launcher intent and starts only that package's exported launcher activity. It does not read another app's private storage, sign in, invoke hidden components, or claim control over screens inside that app.


## Device Settings boundary

The Device Actions adapter accepts only five fixed action IDs that map internally to Android's Wi-Fi, Bluetooth, Sound, Display and Location Settings activities. The Floating Bubble requires a new exact-action, one-use approval before each Settings hand-off. No caller-supplied intent action, component, URI or extra is accepted. Android Settings owns all setting changes, and the user must make and confirm them there. The adapter does not attempt restricted Wi-Fi/Bluetooth toggles, secure-setting writes, device-admin operations or hidden APIs. A Settings destination may be unavailable or differ across Android versions and manufacturers; failures are reported rather than represented as successful changes.


## AutoDroid integration boundary (Stage 9)

AutoDroid remains a separately installed and independently maintained Android application. ONE AI's trusted adapter is limited to opening the fixed package com.aditsyal.autodroid through its launcher activity after a fresh, exact-action, one-use automation approval. Package visibility is limited to that named package; no broad package enumeration is requested.

The current AutoDroid repository exposes its launcher activity but does not provide a documented, authenticated cross-app macro execution API. Its internal automation services and receivers are not a supported external control surface. Therefore ONE AI does not read AutoDroid's database, pass macro IDs or action configurations, invoke internal components, or claim to run macros. The user selects and starts macros inside AutoDroid, where AutoDroid's own permission prompts and controls remain authoritative.

Any future remote macro API must be separately reviewed and versioned, authenticate the calling app, validate a narrow request schema, require a user-visible confirmation in AutoDroid, preserve AutoDroid's own Android permission checks, and return bounded result codes. An exported unprotected service, arbitrary intent forwarding, shared database access, or bypass of AutoDroid's UI/consent is not an acceptable integration.
