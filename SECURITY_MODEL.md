# ONE AI Security Model — Foundation

## Capability declarations are not grants

A plugin manifest may declare a capability, but installation and enablement do not grant Android permissions and do not authorize an action. Each execution path must verify that the plugin is installed and enabled, that the adapter is trusted, and that the requested capability is declared and permitted.

## Risk policy

The current policy blocks disabled plugins, malformed action requests, and unknown capabilities. Command capabilities are classified as low risk. Plugin network access is sensitive because it can transmit user data outside the device and therefore requires just-in-time approval. Screen capture, microphone, device actions, accessibility, automation, and file access are classified as sensitive and require just-in-time user approval.

This is a baseline classification, not a substitute for action-specific review. An adapter may impose stricter checks. No adapter may downgrade a sensitive action.

## One-time approvals

The foundation approval gate issues a process-local ticket only after the caller has obtained user approval. Each ticket is bound to one plugin ID, action ID, capability, and exact user-visible action description; it expires quickly, can be revoked, and is consumed on the first attempt (including a mismatched attempt). Tickets are not persisted and cannot be reused as standing permission grants. Disabling or removing a plugin revokes all pending tickets; a process restart naturally invalidates every ticket.

The reusable `PluginActionButton` is the user-facing action entry point for trusted adapters. It invokes the runtime, displays `SensitiveActionApprovalDialog` with the exact plugin/action/capability/description, and obtains a one-use ticket through `PluginRuntime.approveSensitiveAction` only after the user chooses “Allow once”. The runtime rechecks that the plugin remains installed, enabled, and registered before issuing the ticket. This is a reusable integration surface; no feature adapters are registered yet, so there is not yet a production device-action producer/executor.

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

## Not implemented yet

There is no general device-action executor, global permission manager, or real web/voice integration in this batch. Stage 7 now implements the screen-capture consent/session and external region-selection infrastructure, including a one-time private selected-image artifact hand-off; it does not implement image interpretation or register a Screen Intelligence adapter. Accessibility still needs a guided Settings hand-off; files and device settings need their appropriate dedicated Android flows. The reusable approval/action host and microphone runtime-permission path are implemented, but no feature adapters are registered yet. Real-device testing of the capture flow remains outstanding. This is a security foundation, not a claim that every capability is already operational.
