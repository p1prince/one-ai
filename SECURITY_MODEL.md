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

There is no general device-action executor, global permission manager, or operational App Control or Device Control adapter yet. Voice Interface is implemented in source and awaits CI and physical-device validation. Accessibility still needs a guided Settings hand-off; files and device settings need their appropriate dedicated Android flows. Real-device testing of MediaProjection, the complete capture-to-analysis path, and Bubble web search remains scheduled for Stage 11. Other plugin catalogue entries are not operational until their adapters are implemented.
