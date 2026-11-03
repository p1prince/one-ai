# ONE AI — Development Batch Tracker

This is the shared progress record for the foundation build. A batch is marked complete only after implementation and review.

**Current focus: Batch 7 — Floating Bubble and independent execution.**

Batch 3 and Batch 4 implementation is in place: provider-neutral AI contracts, HTTPS adapter, shared orchestration, cancellation handling, validated disabled-by-default commands, lifecycle controls, shared main/bubble execution routing and unit tests. Initial CI validation found Kotlin compile errors; the callback and JSON array issues were corrected, but the latest branch head still needs a fresh validation run. Provider endpoints reject embedded credentials/fragments and redirects are disabled. The bubble command path reuses one store instance per execution. Batch 5 adds a versioned declarative plugin manifest, capability and dependency validation, dependency-aware enable/disable/remove rules, backwards-compatible manifest metadata persistence, Plugin Centre error feedback, validator tests and SDK documentation; review and fresh validation remain pending. Batch 6 now also classifies network access as sensitive, rejects malformed or oversized action descriptions, and has corrected policy tests. It remains in progress pending full integration and validation: the reusable approval dialog is not wired to a live action producer or device-action executor, and Android runtime permission flows are not implemented. Batch 7 has begun: the Bubble now filters stale saved service toggles against the currently installed and enabled plugin set, retains the independent overlay and in-bubble command path, ties command jobs/results to the foreground service lifecycle, clamps restored and dragged icon coordinates to the visible display, and handles overlay-window failures without crashing the service. Additional security tests now cover malformed and oversized action details. The screen-capture hand-off path is not present in the currently reviewed source and must be implemented as a separate capture-session flow that keeps selection outside the Bubble and returns results inside it; device testing remains required.

| # | Batch | Status | Completion criteria |
|---:|---|---|---|
| 1 | App shell, dashboard and navigation | 🟡 Existing foundation; verification pending | Main shell, navigation, responsive screens and error states reviewed |
| 2 | Core architecture and persistent state | 🟡 Existing foundation; strengthening pending | Stable shared contracts, resilient persistence, lifecycle-safe state and validation |
| 3 | AI provider engine and orchestration | 🟡 IMPLEMENTED — validation pending | Provider-neutral contract, secure request handling, normalized responses/errors, shared orchestration entry point, provider configuration and tests |
| 4 | Command system and reusable commands | 🟡 IMPLEMENTED — validation pending | Validated command model, disabled-by-default creation, enable/disable/remove lifecycle, shared AI execution routing, results, audit trail and unit tests |
| 5 | Plugin architecture and developer SDK | 🔵 IN PROGRESS | Versioned manifest, capability declarations, dependency validation, lifecycle and safe declarative SDK |
| 6 | Security, permissions and action approvals | 🔵 IN PROGRESS | Capability policy, just-in-time consent, sensitive-action confirmation, secure storage and audit |
| 7 | Floating Bubble and independent execution | 🔵 IN PROGRESS | Shared runtime, independent bubble navigation, reliable position/settings, capture hand-off and service lifecycle |
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
