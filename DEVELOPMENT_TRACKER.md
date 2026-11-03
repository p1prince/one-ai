# ONE AI — Development Batch Tracker

This is the fixed 12-stage completion tracker. No additional stages will be added.

**Current focus: Stage 8 — real integrations.** Screen Intelligence and the first real Web Research connector are implemented in source. The latest confirmed CI runs #338–#339 passed on the prior code head; the Web Research implementation and its integration checks are running on the current branch. No APK has been built.

## Current status

| # | Stage | Status | Completion criteria |
|---:|---|---|---|
| 1 | App shell, dashboard and navigation | 🟡 Implemented foundation; verification pending | Main shell, navigation, responsive screens and error states reviewed |
| 2 | Core architecture and persistent state | 🟡 Implemented foundation; strengthening/verification pending | Shared contracts, resilient persistence, lifecycle-safe state and validation |
| 3 | AI provider engine and orchestration | 🟡 Implemented; CI passed; integration review pending | Provider-neutral contract, secure request handling, normalized responses/errors, shared orchestration, provider configuration and tests |
| 4 | Command system and reusable commands | 🟡 Implemented; CI passed; integration review pending | Validated commands, disabled-by-default creation, lifecycle controls, shared AI execution, results, audit trail and tests |
| 5 | Plugin architecture and developer SDK | 🟠 Implementation frozen; verification pending | Versioned manifest, capability/dependency validation, lifecycle, trusted adapter registry, security-gated invocation and declarative SDK |
| 6 | Security, permissions and action approvals | 🟠 Implementation frozen; verification pending | Capability policy, exact-action approval, just-in-time microphone permission, bounded execution audit and regression tests |
| 7 | Floating Bubble and independent execution | 🟡 Source and automated policy tests implemented; capture-cleanup and artifact/image-policy CI passed; physical-device test reserved for Stage 11 | Independent overlay, command execution, preferences, bounded result handoff, explicit ONE AI approval, Android MediaProjection consent, one-frame capture, external region selector and private one-time artifact |
| 8 | Web, screen, voice, app and device integrations | 🔵 IN PROGRESS — Screen Intelligence plus Web Research implemented in source; current Web Research CI pending; Voice, App and Device integrations remain | Real permissioned integrations; no placeholder presented as working functionality |
| 9 | AutoDroid integration boundary | ⏳ UP NEXT | Review separate AutoDroid project and define adapter; keep its repository independent |
| 10 | Settings, activity history and user controls | ⏳ UP NEXT | Unified configuration, clear status, logs and recovery controls |
| 11 | Full integration review and testing | ⏳ UP NEXT | Source review, automated tests, device testing, build validation and documented limitations |
| 12 | First foundation release / APK | 🔒 BLOCKED — do not build yet | Only after Stages 1–11 are complete and the user authorizes the release build |

## Stage 8 — Screen Intelligence and Web Research

### Screen Intelligence — implemented in source
- Provider-neutral `AiVisionProvider` contract and OpenAI-compatible HTTPS vision adapter; configured model must support image input.
- Trusted `ScreenIntelligenceAdapter` registered through the plugin runtime; Screen Intelligence declares both `screen_capture` and `internet`.
- The Bubble requires separate one-time approvals for capture and for sending the selected image to the configured provider. Capture approval does not authorize upload.
- The adapter consumes the private selected-image artifact once, validates its path, size, age and image signature (PNG, JPEG and WebP), and deletes it after analysis.
- Vision-provider response bodies are bounded to 1 MiB; oversized responses are rejected.
- The result returns to the Bubble.
- Capture cleanup, image-format and bounded response-reader tests have passed on earlier validation heads. Real-device end-to-end capture → select → approve upload → analysis → Bubble result remains for Stage 11.

### Web Research — implemented in source; current CI pending
- Provider-neutral `WebSearchProvider` contract and Brave Search API connector using HTTPS.
- Separate `brave_search` credential slot encrypted with Android Keystore; it does not overwrite the primary AI provider key.
- Response body bounded to 1 MiB; results limited and result URLs restricted to HTTP/HTTPS without embedded credentials or fragments.
- Floating Bubble provides a query field and routes searches through `PluginRuntime`.
- Every search requires an exact-action, one-use `internet` approval before the query is transmitted to Brave Search.
- User must install and enable Web Research, enable its Bubble service, and configure a Brave Search API key in Settings.
- Unit tests cover query validation, result URL validation and result formatting. Current branch CI is still running; do not mark the connector verified until it passes.

### Still required in Stage 8
- Confirm the latest Web Research code and documentation commits pass CI; fix any genuine failures.
- Implement real Voice Interface, App Control and Device Control integrations. These are not complete and must not be presented as operational.
- Do not treat source compilation as proof of provider compatibility or device behavior.

## Important validation notes

- The capture service now deletes a partially written screenshot if frame preparation or selector launch fails. It catches ordinary exceptions rather than swallowing fatal JVM errors, and releases temporary bitmap resources.
- The screenshot and region selector remain outside the Bubble. Only bounded result text and the private selected-image reference return to the Bubble.
- MediaProjection consent and actual overlay restoration still require device-level validation; that check is reserved for Stage 11 to preserve the no-APK-before-Stage-12 rule.
- Screen Intelligence and Web Research are not considered release-verified until their end-to-end paths are tested. A successful compile alone does not prove provider compatibility or device behavior.
- Web Research uses a third-party Brave Search API; users must provide their own API key and explicitly approve each outbound query.
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
