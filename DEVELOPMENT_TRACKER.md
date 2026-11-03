# ONE AI — Development Batch Tracker

This is the shared progress record for the foundation build. A batch is marked complete only after implementation and review.

**Current focus: Batch 6 — Security, permissions and action approvals.**

Batch 3 and Batch 4 implementation is in place: provider-neutral AI contracts, HTTPS adapter, shared orchestration, cancellation handling, validated disabled-by-default commands, lifecycle controls, shared main/bubble execution routing and unit tests. Initial CI validation found Kotlin compile errors; the callback and JSON array issues were corrected, but the latest branch head still needs a fresh validation run. Provider endpoints reject embedded credentials/fragments and redirects are disabled. The bubble command path reuses one store instance per execution. Batch 5 adds a versioned declarative plugin manifest, capability and dependency validation, dependency-aware enable/disable/remove rules, backwards-compatible manifest metadata persistence, Plugin Centre error feedback, validator tests and SDK documentation; review and fresh validation remain pending. Batch 6 has started with a capability risk policy that also verifies each plugin declared the requested capability, Android permission guidance, a central plugin action gateway that checks installation, enabled state and declared capabilities, process-local one-use approval tickets bound to exact action descriptions, expiry/revocation, a shared per-store gateway, activity-log auditing, security documentation and unit tests. A reusable sensitive-action approval dialog is implemented, but no live action producer or device-action executor is wired to it yet.

| # | Batch | Status | Completion criteria |
|---:|---|---|---|
| 1 | App shell, dashboard and navigation | 🟡 Existing foundation; verification pending | Main shell, navigation, responsive screens and error states reviewed |
| 2 | Core architecture and persistent state | 🟡 Existing foundation; strengthening pending | Stable shared contracts, resilient persistence, lifecycle-safe state and validation |
| 3 | AI provider engine and orchestration | 🟡 IMPLEMENTED — validation pending | Provider-neutral contract, secure request handling, normalized responses/errors, shared orchestration entry point, provider configuration and tests |
| 4 | Command system and reusable commands | 🟡 IMPLEMENTED — validation pending | Validated command model, disabled-by-default creation, enable/disable/remove lifecycle, shared AI execution routing, results, audit trail and unit tests |
| 5 | Plugin architecture and developer SDK | 🔵 IN PROGRESS | Versioned manifest, capability declarations, dependency validation, lifecycle and safe declarative SDK |
| 6 | Security, permissions and action approvals | 🔵 IN PROGRESS | Capability policy, just-in-time consent, sensitive-action confirmation, secure storage and audit |
| 7 | Floating Bubble and independent execution | ⏳ UP NEXT | Shared runtime, independent bubble navigation, reliable position/settings, capture hand-off and service lifecycle |
| 8 | Web, screen, voice, app and device integrations | ⏳ UP NEXT | Real permissioned integrations; no placeholder presented as a working capability |
| 9 | AutoDroid integration boundary | ⏳ UP NEXT | Review separate AutoDroid project and define adapter; keep its repository independent |
| 10 | Settings, activity history and user controls | ⏳ UP NEXT | Unified configuration, clear status, logs and recovery controls |
| 11 | Full integration review and testing | ⏳ UP NEXT | Source review, automated tests, build validation and documented known limitations |
| 12 | First foundation release / APK | 🔒 BLOCKED — do not build yet | Only after batches 1–11 are complete and the user authorizes the release build |

## Working rules

- Keep the Android shell usable when optional plugins are absent or disabled.
- New plugins and bubble services default to disabled.
- Do not dynamically load untrusted executable plugin code.
- Android actions require the appropriate platform API, explicit user permission and confirmation where sensitive.
- Do not claim an integration works until its end-to-end path has been implemented and tested.
- Do not run APK builds while batches 1–11 are in progress.
- Update this tracker at the end of each batch and identify the next active batch.
