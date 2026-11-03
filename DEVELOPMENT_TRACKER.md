# ONE AI — Development Batch Tracker

This is the fixed 12-stage completion tracker. No additional stages will be added.

**Current focus: Stage 8 — real integrations.** Screen Intelligence, Web Research and Voice Interface are implemented in source. Voice Interface passed full validation runs #366–#369, including both latest runs #368 and #369 on commit 50b00c9. App Control is now being added as a user-approved Android app launcher. No APK has been built.

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
| 8 | Web, screen, voice, app and device integrations | 🔵 IN PROGRESS — Screen Intelligence and Web Research CI verified; Voice Interface CI verified (#366–#369); App Control implementation added, awaiting CI; Device Control remains | Real permissioned integrations; no placeholder presented as working functionality |
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

### Web Research — implemented in source; CI verified on #364–#365
- Provider-neutral `WebSearchProvider` contract and Brave Search API connector using HTTPS.
- Separate `brave_search` credential slot encrypted with Android Keystore; it does not overwrite the primary AI provider key.
- Response body bounded to 1 MiB; results limited and result URLs restricted to HTTP/HTTPS without embedded credentials or fragments.
- Floating Bubble provides a query field and routes searches through `PluginRuntime`.
- Every search requires an exact-action, one-use `internet` approval before the query is transmitted to Brave Search; the approved action description includes the normalized query itself.
- User must install and enable Web Research, enable its Bubble service, and configure a Brave Search API key in Settings.
- Unit tests cover query validation, result URL validation and result formatting. API-key entry is masked in Settings. Validation runs #364–#365 passed. Physical-device/provider credential verification remains for Stage 11.

### Voice Interface — implemented in source; CI verified
- Trusted `VoiceInterfaceAdapter` uses the shared AI orchestrator, one-shot Android speech recognition, and Android TextToSpeech.
- The Floating Bubble asks for a separate ONE AI approval before opening the microphone flow. Only after approval does the capture Activity request Android `RECORD_AUDIO` permission and launch the system speech-recognition UI outside the Bubble.
- A single approved session captures one transcript, sends that transcript to the configured AI provider, and speaks the response. The transcript and answer return to the Bubble.
- Speech recognition and speech output have bounded text policies and focused unit tests.
- Voice capture is optional and remains disabled until the user installs/enables the plugin and enables its Bubble service.
- Validation runs #366–#369 passed, including latest commit 50b00c9 (#368 and #369). Physical-device microphone, recognizer and TextToSpeech validation remains for Stage 11.

### App Control — implementation added; CI pending
- Adds an optional `one.app.control` plugin with the `app_launch` capability and Bubble service.
- Lists launcher-visible apps through a narrow Android package-visibility query; does not request `QUERY_ALL_PACKAGES`.
- Each selected app launch routes through the trusted `PluginRuntime` and requires an exact-action, one-use approval before starting that app's exported launcher activity.
- Does not sign in, inspect private app data, automate in-app screens, or bypass the target app's security.
- Focused adapter and package-name policy tests added. CI must pass before this integration is marked verified; real-device validation remains for Stage 11.

### Still required in Stage 8
- Confirm App Control passes CI; fix genuine failures only.
- Implement Device Control using supported Android settings intents/APIs and clear OS limitations.
- Do not treat source compilation as proof of provider compatibility or device behavior.

## Important validation notes

- The capture service now deletes a partially written screenshot if frame preparation or selector launch fails. It catches ordinary exceptions rather than swallowing fatal JVM errors, and releases temporary bitmap resources.
- The screenshot and region selector remain outside the Bubble. Only bounded result text and the private selected-image reference return to the Bubble.
- MediaProjection consent and actual overlay restoration still require device-level validation; that check is reserved for Stage 11 to preserve the no-APK-before-Stage-12 rule.
- Screen Intelligence and Web Research are not considered release-verified until their end-to-end paths are tested. A successful compile alone does not prove provider compatibility or device behavior.
- Web Research uses a third-party Brave Search API; users must provide their own API key and explicitly approve each outbound query.
- No APK has been built. The draft PR remains unmerged.
- The Web Research implementation passed #364/#365. Voice Interface passed #366–#369, including #368/#369 on 50b00c9. App Control is newer and must pass its own CI before being marked verified.

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
