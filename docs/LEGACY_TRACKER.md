> **SUPERSEDED.** Historical record kept for reference. It uses a stage structure that differs from the Official Open-Shell Development Plan and its "complete" statuses must not be trusted. See `/DEVELOPMENT_TRACKER.md`.

# ONE AI — Development Batch Tracker

This is the fixed 12-stage completion tracker. No additional stages will be added.

**Current focus: Stage 11 — full integration review and testing.** Stage 10 settings, activity history and user controls passed Android validation runs #398 and #399 on ca2f300 (compilation and unit tests; no APK). Stage 8 passed Device Control CI runs #390–#391 on 282bde7. Stage 9's trusted AutoDroid launcher boundary passed CI run #394 on 7d5ad3b. No APK has been built.

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
| 8 | Web, screen, voice, app and device integrations | ✅ IMPLEMENTED AND CI VERIFIED — Screen Intelligence and Web Research; Voice Interface (#366–#369); App Control (#388–#389); Device Control (#390–#391 on 282bde7) | Real permissioned integrations; physical-device verification is reserved for Stage 11 |
| 9 | AutoDroid integration boundary | ✅ IMPLEMENTED AND CI VERIFIED (#394 on 7d5ad3b) — trusted, one-use-approved launcher hand-off; AutoDroid repository remains independent; physical launch test reserved for Stage 11 | Review the separate project, define a trusted adapter, preserve permission/consent boundaries, and keep its repository independent |
| 10 | Settings, activity history and user controls | ✅ IMPLEMENTED AND CI VERIFIED (#398–#399 on ca2f300) | Unified configuration, clear status, logs and recovery controls |
| 11 | Full integration review and testing | 🔵 IN PROGRESS | Source review, automated tests, device testing, build validation and documented limitations |
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

### App Control — implemented in source; CI verified
- Adds an optional `one.app.control` plugin with the `app_launch` capability and Bubble service.
- Lists launcher-visible apps through a narrow Android package-visibility query; does not request `QUERY_ALL_PACKAGES`.
- Each selected app launch routes through the trusted `PluginRuntime` and requires an exact-action, one-use approval before starting that app's exported launcher activity.
- Does not sign in, inspect private app data, automate in-app screens, or bypass the target app's security.
- Focused adapter and package-name policy tests added. Validation runs #388–#389 passed on commit 1c7c315. Real-device launch/catalogue validation remains for Stage 11.

### Device Control — implemented in source; CI verified
- Adds the optional one.device.actions trusted adapter and device_control Bubble service.
- Offers only five allowlisted Android Settings destinations: Wi-Fi, Bluetooth, Sound & vibration, Display and Location.
- Every action requires a fresh exact-action, one-use approval before ONE AI opens the corresponding Android Settings screen.
- Android remains responsible for changing settings, showing system confirmations and enforcing OS restrictions. The adapter does not silently toggle restricted settings or execute arbitrary intents.
- Focused adapter and allowlist policy tests added. Validation runs #390 and #391 both passed on commit 282bde7. Physical-device checks remain for Stage 11.

## Stage 11 — Full integration review and testing (in progress)
- Reviewed the AI provider request path and found an unbounded response-body read that could allocate excessive memory on oversized or hostile provider responses.
- Reused the existing bounded UTF-8 response reader in `AiProviderClient`; provider success and error bodies now share a strict 1 MiB ceiling and oversized bodies return a clear `RESPONSE_TOO_LARGE` error. Existing reader tests cover in-limit and over-limit behavior.
- Further cross-feature review and the resulting validation run are in progress. Physical-device tests still require installation and interaction on an Android device; no APK build is authorized in this stage.

## Stage 10 — Settings, activity history and user controls
- Settings shows AI provider, Web Research credential and Floating Bubble readiness, plus enabled plugin and command counts.
- Provider endpoint input is validated as HTTPS and rejects malformed URLs, embedded user-info and fragments before settings can be saved.
- AI provider and Brave Search credentials remain separately encrypted with Android Keystore; each can be explicitly removed after a confirmation dialog.
- Activity history displays local timestamps, retains a bounded 150-event window and offers a confirmation-protected clear action. Existing message-only activity reads remain backward compatible.
- Validation runs #398 and #399 passed on commit ca2f300, including Android compilation and unit tests without building an APK.
- Stage 10 is complete. Physical-device settings and persistence checks remain part of Stage 11.

## Stage 9 — AutoDroid integration boundary
- AutoDroid was reviewed as a separate Kotlin/Android automation application using its own Room database, repository and macro execution use cases.
- Its current manifest exposes only the launcher activity as a normal external entry point; its automation services and internal receivers are not a documented cross-app API.
- ONE AI adds a trusted one.automation.engine adapter that opens only the fixed AutoDroid package after an exact-action, one-use approval.
- Macro selection and execution remain inside AutoDroid. No macro IDs, action configurations, database content or arbitrary intents cross the boundary.
- The user must install and enable the Automation Engine plugin and enable its Bubble service. If AutoDroid is absent, ONE AI reports that it is unavailable.
- A remote macro-execution API is out of scope until a versioned, authenticated, user-confirmed contract is designed and tested.

- Validation run #394 passed on commit 7d5ad3b. The validation job completed compilation and unit tests without building an APK.
- Stage 9 is complete at the defined launcher-boundary scope. Physical install/launch verification remains reserved for Stage 11.

## Important validation notes

- The capture service now deletes a partially written screenshot if frame preparation or selector launch fails. It catches ordinary exceptions rather than swallowing fatal JVM errors, and releases temporary bitmap resources.
- The screenshot and region selector remain outside the Bubble. Only bounded result text and the private selected-image reference return to the Bubble.
- MediaProjection consent and actual overlay restoration still require device-level validation; that check is reserved for Stage 11 to preserve the no-APK-before-Stage-12 rule.
- Screen Intelligence and Web Research are not considered release-verified until their end-to-end paths are tested. A successful compile alone does not prove provider compatibility or device behavior.
- Web Research uses a third-party Brave Search API; users must provide their own API key and explicitly approve each outbound query.
- No APK has been built. The draft PR remains unmerged.
- The Web Research implementation passed #364/#365. Voice Interface passed #366–#369, including #368/#369 on 50b00c9. Device Control passed validation runs #390–#391 on commit 282bde7. Physical-device checks for Settings hand-off remain reserved for Stage 11.

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
