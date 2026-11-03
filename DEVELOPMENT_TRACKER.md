# ONE AI — Development Batch Tracker

This is the fixed 12-stage completion tracker. No additional stages will be added.

**Current focus: Stage 8 — real integrations.** The first Screen Intelligence path is implemented in source. The latest capture-cleanup correction is committed and its CI validation is pending. No APK has been built.

## Current status

| # | Stage | Status | Completion criteria |
|---:|---|---|---|
| 1 | App shell, dashboard and navigation | 🟡 Implemented foundation; verification pending | Main shell, navigation, responsive screens and error states reviewed |
| 2 | Core architecture and persistent state | 🟡 Implemented foundation; strengthening/verification pending | Shared contracts, resilient persistence, lifecycle-safe state and validation |
| 3 | AI provider engine and orchestration | 🟡 Implemented; CI passed; integration review pending | Provider-neutral contract, secure request handling, normalized responses/errors, shared orchestration, provider configuration and tests |
| 4 | Command system and reusable commands | 🟡 Implemented; CI passed; integration review pending | Validated commands, disabled-by-default creation, lifecycle controls, shared AI execution, results, audit trail and tests |
| 5 | Plugin architecture and developer SDK | 🟠 Implementation frozen; verification pending | Versioned manifest, capability/dependency validation, lifecycle, trusted adapter registry, security-gated invocation and declarative SDK |
| 6 | Security, permissions and action approvals | 🟠 Implementation frozen; verification pending | Capability policy, exact-action approval, just-in-time microphone permission, bounded execution audit and regression tests |
| 7 | Floating Bubble and independent execution | 🟡 Source and automated policy tests implemented; latest capture-cleanup patch CI pending; physical-device test reserved for Stage 11 | Independent overlay, command execution, preferences, bounded result handoff, explicit ONE AI approval, Android MediaProjection consent, one-frame capture, external region selector and private one-time artifact |
| 8 | Web, screen, voice, app and device integrations | 🔵 IN PROGRESS — Screen Intelligence implementation added; end-to-end validation pending | Real permissioned integrations; no placeholder presented as working functionality |
| 9 | AutoDroid integration boundary | ⏳ UP NEXT | Review separate AutoDroid project and define adapter; keep its repository independent |
| 10 | Settings, activity history and user controls | ⏳ UP NEXT | Unified configuration, clear status, logs and recovery controls |
| 11 | Full integration review and testing | ⏳ UP NEXT | Source review, automated tests, device testing, build validation and documented limitations |
| 12 | First foundation release / APK | 🔒 BLOCKED — do not build yet | Only after Stages 1–11 are complete and the user authorizes the release build |

## Stage 8 — Screen Intelligence implementation

Implemented in source:
- Provider-neutral `AiVisionProvider` contract.
- OpenAI-compatible HTTPS vision adapter; configured model must support image input.
- Trusted `ScreenIntelligenceAdapter` registered through the plugin runtime.
- Screen Intelligence declares both `screen_capture` and `internet`.
- The Bubble requires a second, separate one-time approval before the selected image is sent to the configured provider. Capture approval does not authorize upload.
- The adapter consumes the private selected-image artifact once, validates its path, size, age and image signature, and deletes it after analysis.
- The result returns to the Bubble.

Validation already passed on the previous integration head: Android Validation runs #322 and #323. The capture cleanup correction is now committed at `a5e00dd7c397ccb70945293d17880f51280550a2`; push validation #326 is queued and runs #324/#325 are still in progress at the time of this update.

Still required:
- Confirm the latest capture-cleanup commit passes CI.
- Add focused automated coverage for the vision request/adapter boundary where practical.
- Validate the complete capture → select → approve upload → analysis → Bubble result path on a real Android device.
- Implement the remaining Web, Voice, App and Device integrations during Stage 8. They are not complete yet.

- Vision provider response bodies are now read with a strict 1 MiB ceiling; oversized provider responses fail with a bounded, user-safe error instead of being loaded without limit.
- Added unit tests for bounded UTF-8 response reading, over-limit rejection and invalid size limits.

## Important validation notes

- The capture service now deletes a partially written screenshot if frame preparation or selector launch fails. It catches ordinary exceptions rather than swallowing fatal JVM errors, and releases temporary bitmap resources.
- The screenshot and region selector remain outside the Bubble. Only bounded result text and the private selected-image reference return to the Bubble.
- MediaProjection consent and actual overlay restoration still require device-level validation; that check is reserved for Stage 11 to preserve the no-APK-before-Stage-12 rule.
- Screen Intelligence is not considered complete until the end-to-end path is tested. A successful compile alone does not prove provider compatibility or device behavior.
- No APK has been built. The draft PR remains unmerged.

## Working rules

- Keep the Android shell usable when optional plugins are absent or disabled.
- New plugins and bubble services default to disabled.
- Do not dynamically load untrusted executable plugin code.
- Android actions require the appropriate platform API, explicit user permission and confirmation where sensitive.
- Do not claim an integration works until its end-to-end path has been implemented and tested.
- Do not run APK builds while batches 1–11 are in progress.
- **Pending verification means implementation is frozen:** no planned feature additions; fix only genuine defects found by verification. Feature-specific platform consent flows are implemented with their corresponding integrations, not by endlessly reopening the shared security batch.
- **Completed and verified means implementation and required checks passed:** move forward and reopen only for a regression or a concrete integration defect.
- Update this tracker at the end of each batch and identify the next active batch.
