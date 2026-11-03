# ONE AI Security Model — Foundation

## Capability declarations are not grants

A plugin manifest may declare a capability, but installation and enablement do not grant Android permissions and do not authorize an action. Each execution path must verify that the plugin is installed and enabled, that the adapter is trusted, and that the requested capability is declared and permitted.

## Risk policy

The current policy blocks disabled plugins, malformed action requests, and unknown capabilities. Command capabilities are classified as low risk. Plugin network access is sensitive because it can transmit user data outside the device and therefore requires just-in-time approval. Screen capture, microphone, device actions, accessibility, automation, and file access are classified as sensitive and require just-in-time user approval.

This is a baseline classification, not a substitute for action-specific review. An adapter may impose stricter checks. No adapter may downgrade a sensitive action.

## One-time approvals

The foundation approval gate issues a process-local ticket only after the caller has obtained user approval. Each ticket is bound to one plugin ID, action ID, capability, and exact user-visible action description; it expires quickly, can be revoked, and is consumed on the first attempt (including a mismatched attempt). Tickets are not persisted and cannot be reused as standing permission grants. Disabling or removing a plugin revokes all pending tickets; a process restart naturally invalidates every ticket.

The UI and action adapters must display a clear action description and invoke the gate only after a real affirmative user action. This contract is not yet wired to a user-facing approval dialog or device-action executor.

## Android permission boundaries

Use Android's native permission and consent mechanisms in addition to ONE AI's own approval:
- Screen capture: MediaProjection user consent.
- Microphone: RECORD_AUDIO runtime permission.
- Accessibility: user enables the service in Android Settings.
- Device settings: supported intents/APIs and system confirmation where required.
- Files: Storage Access Framework or scoped permissions.
- Internet: INTERNET manifest permission (normal permission).

A ONE AI approval ticket never bypasses Android permission checks, system dialogs, app sandboxing, or OS restrictions.

## Audit

Sensitive-action adapters must log request, approval/denial, execution result, and failure without recording secrets or unnecessary captured content. The audit logger contract is present; wiring it to all action paths remains future integration work.

## Not implemented yet

There is no general device-action executor, global approval dialog, persistent permission manager, or real web/screen/voice integration in this batch. The security model is a foundation contract and tests, not a claim that those capabilities are already operational.
