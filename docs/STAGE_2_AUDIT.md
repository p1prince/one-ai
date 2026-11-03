# ONE AI — Stage 2 Repository Audit and Proposed Correction Plan

Status: **DRAFT FOR USER APPROVAL. No code was changed.**
Source of truth: *ONE AI Official Open-Shell Development Plan* (revised 3 November 2026).
Audited: repository `p1prince/one-ai`, branch `foundation/batch-03-ai-engine`, commit `1cdfd117c986ed0ae275c150720d19164c79e5af`.

## 1. Verified facts (checked live)

| Item | Result |
|---|---|
| Branch head | `1cdfd117…`, matches the plan |
| PR #1 | open, **draft**, base `main`, not merged |
| CI on that commit | "Android Validation" #402 and #403 succeeded on `1cdfd11` |
| CI scope | Compilation and JVM unit tests only. No emulator or physical-device test has been run |
| APK | `Android Build` workflow is manual-only (`workflow_dispatch`); no APK built |

## 2. Review depth (what was and was not read)

- **Read in detail:** `PluginManager.kt`, `PluginManifest.kt`, the top of `PluginRuntime.kt` and `CommandRouter.kt`, `AndroidManifest.xml` permissions, navigation enum and Bubble settings in `MainActivity.kt`, the Bubble-related store keys in `OneAiStore.kt`, `DEVELOPMENT_TRACKER.md`, and both CI workflows.
- **Classified from file name, size, imports and tracker text only (not read line by line):** everything marked *(inferred)* below. These must be confirmed in the full file-by-file pass.
- **Not reviewed:** `FloatingBubbleService.kt` internals (843 lines), `ActionSecurity.kt`, `SecretVault.kt`, build scripts, dependencies, test contents.
- **Not verifiable here:** anything on a device, and the visual design (the original mockup was never recovered; no colour, typography or dimension claims are made).

## 3. Findings: conflicts with the plan

| # | Finding | Plan reference | Severity |
|---|---|---|---|
| F1 | Navigation has **six** sections (Home, Plugins, Develop, **Commands**, Activity, Settings). The plan fixes five. | §2, Stage 4 | High (approved-design deviation) |
| F2 | Seven capability plugins are compiled into the core and listed in a hard-coded catalogue in `PluginManager`. Installation is from in-code templates, not from an external package. | §5, Stage 8/9, rule 3 | High |
| F3 | Capability code lives in core packages: `ai/BraveSearchProvider`, `ai/OpenAiCompatibleVisionProvider`, `ai/Web*`, `bubble/Screen*`, `bubble/VoiceCaptureActivity`, `plugins/*Adapter`. | §5 | High |
| F4 | `FloatingBubbleService` (843 lines) contains capability-specific flows (search query field, voice, screen capture/upload approvals) rather than only a generic plugin area. | Stage 7 | High |
| F5 | Core manifest declares `RECORD_AUDIO` and `FOREGROUND_SERVICE_MEDIA_PROJECTION`, which only future plugins need. `SYSTEM_ALERT_WINDOW` is legitimately core. | §5, Stage 7 | Medium |
| F6 | No extension model: the manifest is declarative only, `PluginRuntime` explicitly is not a loader, and there is no import/install path for an independent plugin. Android packaging, signing and inter-app communication are undesigned. | Stage 8 | High (blocks "independent developer can build an extension") |
| F7 | Manifest has no core-compatibility declaration, and no Bubble, settings or activity integration fields. | Stage 8 | Medium |
| F8 | Core command framework is a saved-prompt runner (`CommandRouter`: run, add, remove). There is no command registration by extensions, identifiers, routing table or extension command interface. | Stage 6 | Medium |
| F9 | `DEVELOPMENT_TRACKER.md` uses a different 12-stage structure, defines Stages 8–9 as the integrations above, and marks stages complete. | §7, rules 12, 15 | Medium |
| F10 | `docs/AUTODROID_INTEGRATION.md` and `AutoDroidAdapter` encode AutoDroid as a built-in integration. | §5 | Medium |
| F11 | `OneAiStore` imports `PluginRuntime`, so core state depends on the plugin package. | Stage 3 | Low–Medium (verify) |

## 4. Findings: matches the plan (retain)

- Bubble disabled by default (`bubble_enabled` = false); `bubble_services` defaults to empty; plugins install disabled.
- Disabling a plugin unregisters its adapter, revokes pending approvals and removes its Bubble service.
- Dependency checks on enable/disable; manifest validation (reverse-domain ID, semver, bounded capabilities).
- Exact-action, one-use approval gateway; encrypted credential vault; bounded provider response bodies.
- Provider-neutral AI contract and shared `AiOrchestrator`.
- Disciplined CI: validation on `foundation/**` pushes, APK workflow manual-only.

**To verify:** that the Plugins screen exposes enablement and Bubble access as two separate controls per plugin (the Settings screen has the Bubble toggles); that the core runs with an empty catalogue.

## 5. File classification

Legend: **C** Core · **PI** Plugin infrastructure · **FP** Future plugin · **R** Reusable as is · **RC** Requires correction. *(inferred)* = not read line by line.

| File | Class | Note |
|---|---|---|
| `MainActivity.kt` | C, **RC** | Remove/relocate Commands tab (F1) |
| `core/OneAiStore.kt` | C, **RC** | Break dependency on plugin package (F11) |
| `ai/AiOrchestrator.kt`, `AiProvider.kt`, `AiProviderClient.kt`, `AiResponseBodyReader.kt`, `ProviderSettingsPolicy.kt` | C, R | Provider-neutral engine; keep |
| `ai/AiVisionProvider.kt`, `OpenAiCompatibleVisionProvider.kt` | FP (Screen Intelligence) *(inferred)* | Move out of core |
| `ai/WebSearchProvider.kt`, `WebSearchPolicy.kt`, `BraveSearchProvider.kt` | FP (Web Research) *(inferred)* | Move out of core; credential slot needs a generic plugin-secret mechanism |
| `commands/CommandRouter.kt` | C, **RC** | Extend into registration/routing framework (F8) |
| `plugins/PluginManifest.kt` | PI, **RC** | Add compatibility and Bubble/settings/activity declarations (F7) |
| `plugins/PluginRuntime.kt` | PI, **RC** | Keep gateway/approval boundary; add external adapter model (F6) |
| `plugins/PluginManager.kt` | PI, **RC** | Remove hard-coded catalogue; add import/discovery (F2) |
| `plugins/WebResearchAdapter.kt` | FP *(inferred)* | Move |
| `plugins/VoiceInterfaceAdapter.kt`, `VoiceTextPolicy.kt` | FP *(inferred)* | Move |
| `plugins/ScreenIntelligenceAdapter.kt`, `ScreenIntelligenceImagePolicy.kt` | FP *(inferred)* | Move |
| `plugins/AppControlAdapter.kt` | FP *(inferred)* | Move |
| `plugins/DeviceControlAdapter.kt` | FP *(inferred)* | Move |
| `plugins/AutoDroidAdapter.kt` | FP *(inferred)* | Move; AutoDroid stays independent |
| `bubble/FloatingBubbleService.kt` | C, **RC** | Split generic shell from capability UI (F4); not yet read in detail |
| `bubble/BubbleUiPolicy.kt`, `BubbleResultHandoff.kt` | C, R *(inferred)* | Generic ordering/visibility/handoff |
| `bubble/Screen*` (5 files), `VoiceCaptureActivity.kt` | FP *(inferred)* | Move with their plugins |
| `security/ActionSecurity.kt`, `PluginActionGateway.kt`, `PluginActionButton.kt`, `SensitiveActionApprovalDialog.kt`, `SecretVault.kt` | C, R *(inferred)* | Keep; read in full before relying on them |
| `AndroidManifest.xml` | C, **RC** | Trim capability permissions/services (F5) |
| `docs/AUTODROID_INTEGRATION.md`, `DEVELOPMENT_TRACKER.md` | **RC** | Rewrite to plan (F9, F10) |
| `PLUGIN_SDK.md`, `SECURITY_MODEL.md`, `README.md` | PI/C, **RC** *(inferred)* | Update after architecture decision |
| `.github/workflows/*` | C, R | Keep manual-only APK workflow |
| Unit tests (21 files) | Follow their subject | Tests for moved code move with it |

## 6. Dependency map (summary)

- `core/OneAiStore` → `plugins/PluginRuntime` → `security/*`.
- `plugins/PluginManager` → `OneAiStore`, `security`.
- `bubble/FloatingBubbleService` → `ai`, `security`, `core`, `plugins` (to be confirmed).
- `MainActivity` → `bubble`, `core`, `plugins`.
- Capability adapters → `ai` providers and `bubble` activities (capability code is cross-package, so extraction must move these together).

The full import graph should be generated mechanically in the audit completion step.

## 7. Key decision needed before code changes (F6)

Stage 8 requires choosing and documenting a secure Android extension model. Options to be decided by the user:

1. **Separate APK plugins** (signed, discovered via an exported service/intent contract, communicating through bound services or content providers, with signature and permission checks). Strongest isolation; most work.
2. **In-repo modules behind a stable interface** (separate Gradle modules compiled into optional build variants). Simple, but not independently installable.
3. **Hybrid:** the core defines the SDK contract first; built-in capabilities become reference plugin modules, and external APK plugins follow once the contract is proven.

Arbitrary dex or class loading from a plugin folder is rejected, as the plan requires.

## 8. Proposed correction plan (for approval)

Each step is one commit, CI-checked on its exact commit, with no merge and no APK.

1. **Docs only:** rewrite `DEVELOPMENT_TRACKER.md` to the plan's 12 stages with honest statuses; update the AutoDroid doc; record the extension-model decision.
2. **Mechanical dependency map** and read-through of the not-yet-read files; update this audit.
3. **Navigation:** reduce to five sections; place command management under Develop or Settings (user to choose).
4. **Core decoupling:** remove `OneAiStore` → `PluginRuntime` coupling; confirm the core runs with an empty catalogue (add a test).
5. **Generic Bubble:** split `FloatingBubbleService` into a generic shell and a plugin-contributed area; add per-plugin Bubble-access as a distinct stored setting.
6. **Plugin contract:** manifest additions and the extension model from section 7.
7. **Extract capability code** one plugin at a time (Web Research first, as the smallest), retaining existing tests.
8. **Trim manifest permissions**; re-run CI.
9. **Command framework** extension interface.
10. Stage 11 acceptance matrix, separating CI, unit, integration, emulator and device results.

Rules honoured: no merge of PR #1, no APK, no invented design details, 12 stages preserved.
