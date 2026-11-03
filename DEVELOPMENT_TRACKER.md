# ONE AI — Development Batch Tracker

This is the shared progress record for the foundation build. A batch is marked complete only after implementation and review.

**Current focus: Batch 4 — Command System and reusable commands.**

Batch 3 implementation includes provider-neutral contracts, the HTTPS adapter, normalized errors, shared orchestration, cancellation handling and main/bubble routing. Orchestrator and command-router unit tests are added; final completion awaits a confirmed non-APK validation workflow result. Batch 4 is underway: validated command creation, disabled-by-default commands (including the data-model default), enable/disable/remove lifecycle, shared execution routing from the main screen and bubble, and unit tests. Provider endpoints now reject embedded credentials/fragments and redirects are disabled.

| # | Batch | Status | Completion criteria |
|---:|---|---|---|
| 1 | App shell, dashboard and navigation | 🟡 Existing foundation; verification pending | Main shell, navigation, responsive screens and error states reviewed |
| 2 | Core architecture and persistent state | 🟡 Existing foundation; strengthening pending | Stable shared contracts, resilient persistence, lifecycle-safe state and validation |
| 3 | AI provider engine and orchestration | 🟡 IMPLEMENTED — validation pending | Provider-neutral contract, secure request handling, normalized responses/errors, shared orchestration entry point, provider configuration and tests |
| 4 | Command system and reusable commands | 🔵 IN PROGRESS | Validated command model, disabled-by-default creation, enable/disable/remove lifecycle, shared AI execution routing, results, audit trail and unit tests |
| 5 | Plugin architecture and developer SDK | ⏳ UP NEXT | Versioned manifest, capability declarations, dependency validation, lifecycle and safe declarative SDK |
| 6 | Security, permissions and action approvals | ⏳ UP NEXT | Capability policy, just-in-time consent, sensitive-action confirmation, secure storage and audit |
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
