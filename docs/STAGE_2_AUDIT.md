# ONE AI — Stage 2 Repository Audit and Proposed Correction Plan

Status: **Hybrid extension model approved by the owner (D4); Stage 2 audit completion remains in progress. No application code was changed.**
Source of truth: *ONE AI Official Open-Shell Development Plan* (revised 3 November 2026).
Initial audit baseline: repository `p1prince/one-ai`, branch `foundation/batch-03-ai-engine`, commit `1cdfd117c986ed0ae275c150720d19164c79e5af`. The audit was reconciled against branch head `6433404d5c272714405dd32c65af07bdcef838f8` on 2026-11-05; the initial findings below are historical until the remaining file-by-file checks are completed.

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
| F1 | Navigation has **six** sections (Home, Plugins, Develop, **Commands**, Activity, Settings). The plan fixes five. **Resolved by decision D2:** Command Manager becomes a Home module; fix scheduled in Stage 4. | §2, Stage 4 | High (approved-design deviation) |
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

## 7. Approved extension model (F6) — owner decision D4

The owner approved the **hybrid model** on 2026-11-05:

1. **Stable core SDK contract:** ONE AI owns a versioned plugin contract, generic runtime/management, security and approval boundary, command registration interfaces, and generic Bubble contribution protocol.
2. **Built-in reference plugin modules:** capability implementations are separated from the permanent core and developed as reference modules against the same SDK. They demonstrate and test the contract; they must not become capability-specific code in the core shell. These modules do not replace the external plugin model.
3. **External APK plugins:** independently developed extensions are separately installed, signed APKs using the same documented contract. The current Stage 8 design's bound-service AIDL, package-name identity, explicit fingerprint trust, per-bind certificate verification, bounded IPC, host-controlled approvals, declarative Bubble UI and failure isolation remain the secure external boundary.

D1 remains in force: compile-time-only modules are not accepted as the sole extension model. Arbitrary dex/class loading from plugin folders remains rejected. New plugins must not require capability-specific edits to core source.

## 8. Correction plan and approval state

The documentation-reconciliation portion is now complete: the tracker already uses the official 12 stages, the AutoDroid integration document already labels AutoDroid as a future plugin, and D4 now records the owner's hybrid-model approval. This is a docs-only decision record; it does not complete the Stage 2 repository audit.

Remaining audit work, in order:
1. **Complete the file-by-file review:** read the outstanding portions of `FloatingBubbleService`, `ActionSecurity`, `SecretVault`, screen-capture/voice activities, build scripts and dependencies, and inspect the actual assertions in the test suite. Reconfirm earlier classifications against current branch code rather than relying on file names or the old tracker.
2. **Complete dependency analysis:** generate/update the mechanical package and class-level dependency map; explicitly trace and plan removal of inverted `plugins → bubble`, `security → plugins`, and `core → plugins` edges, plus hard-coded plugin ID/catalogue tables.
3. **Reconcile implementation to D4:** map each existing built-in capability and its UI, permissions, settings, secrets and tests to a reference plugin module using the stable SDK; keep only generic infrastructure in core. Confirm the external signed-APK AIDL contract remains the public extension path.
4. **Update this audit and correction plan** with verified file-level evidence, corrected classifications, migration order, dependencies and test coverage. Only then is the Stage 2 audit ready for final owner review/acceptance.
5. After Stage 2 acceptance, implement corrections in official 12-stage order: core decoupling and empty-catalogue proof; generic empty-by-default Bubble and independent per-plugin Bubble access; stable plugin contract integration; capability extraction into reference modules; permission minimisation; command extension interface; and Stage 11 acceptance matrix. Each implementation change must be scoped to its official stage and CI-checked on the exact commit.

Rules: no merge of PR #1, no APK, no invented design details, and no change to the official 12-stage sequence.

---

## 9. Update 2 — dependency map and deeper read (step 2 of the correction plan)

Evidence: import graph generated mechanically from `app/src/main/java`; additionally read in full or in part: `FloatingBubbleService.showPanel/showService`, `PluginActionGateway.kt`, the Plugins/Developer/Commands screens in `MainActivity.kt`, `PluginManager.kt` (all), `build.gradle.kts`. CI on the audit commit `d792357`: runs #404 and #405 passed.

### 9.1 Mechanical package dependency map

Edge counts are import statements between top-level packages (A → B means A imports B).

| From → To | Imports | Assessment |
|---|---:|---|
| bubble → plugins | 17 | Expected only for a generic contract; currently capability-specific (F4) |
| bubble → ai | 5 | Capability providers called from the Bubble |
| bubble → security / core / commands | 3 / 1 / 1 | Acceptable (core) |
| plugins → ai | 4 | Capability adapters call AI/search/vision providers |
| plugins → security / core | 3 / 3 | Acceptable |
| plugins → bubble | 2 | **Inverted**: plugin code depends on Bubble classes |
| security → plugins | 3 | **Core → plugin**: `PluginActionButton` imports the runtime |
| core → plugins / commands / ai / security | 1 / 1 / 1 / 2 | `OneAiStore` imports `PluginRuntime` (F11 confirmed) |
| ai → security / core | 3 / 2 | Acceptable |
| commands → ai / core | 2 / 1 | Acceptable |

Conclusion: the layers are not cleanly separable today. Extracting capabilities (F2/F3) requires first inverting `plugins → bubble` and `core/security → plugins`.

### 9.2 New and confirmed findings

| # | Finding | Evidence |
|---|---|---|
| F12 | Plugin IDs are hard-coded in **three** places that must change together: `PluginManager.catalogue`, `PluginManager.serviceFor()`, `BubbleUiPolicy.availableServices()`. `MainActivity` Settings also hard-codes four of the six. Adding a plugin therefore needs core edits, contradicting Stage 8 ("no core source edits per extension"). | `PluginManager.kt:14–19, 72–79`; `BubbleUiPolicy.kt:28–33`; `MainActivity.kt:229–232` |
| F13 | `FloatingBubbleService.showService()` is an if/else chain on capability name with inline UI for web search (text field), app list, settings list and AutoDroid. A generic plugin-contributed area does not exist. | `FloatingBubbleService.kt:177–250` |
| F14 | Dead/stale code: a fallback branch still says "Voice Interface is not connected yet" and "Automation is not connected yet", but both services are handled earlier in the chain, so the branch is unreachable for them. Misleading text should not survive. | `FloatingBubbleService.kt:243–248` |
| F15 | **Separation of plugin enablement vs. Bubble access exists in storage** (`plugins[].enabled` vs `bubble_services` set, empty by default) **and in Settings** (per-service switches). It is **not** exposed on the Plugins screen, which shows only install/remove-type actions and descriptions. Resolves the earlier "to verify" item: separate controls exist but live in Settings, keyed by hard-coded service names. | `OneAiStore.kt:108–109`; `MainActivity.kt:224–241`; Plugins screen |
| F16 | Bubble hosts a generic **`command_manager`** service that runs enabled core commands without any plugin. This is core behaviour and consistent with the plan; it should be kept. It is also the one thing the Bubble shows when no plugin is installed. | `FloatingBubbleService.kt:178–199` |
| F17 | Empty-by-default Bubble behaviour is present: with no enabled services the panel shows "No services enabled…". Positive finding; needs a test (none found for the panel). | `FloatingBubbleService.kt:155–170` |
| F18 | Developer Mode creates **declarative descriptors only** (default capability `commands`). They install as records with no runnable code, which is safe but cannot become working extensions under the current design (F6). | `PluginManager.create`, Developer screen |
| F19 | Credential storage supports a single provider-key alias in `SecretVault`; the tracker describes a second Brave credential slot. Plugin-owned secrets need a generic, plugin-scoped mechanism before Web Research can leave the core. (Second slot not re-read here; verify.) | `SecretVault.kt:16–21` |
| F20 | The approval gateway (`PluginActionGateway`) is generic: it checks plugin existence and enablement, capability policy, requires a one-use ticket for sensitive actions, audits every outcome and can revoke pending approvals. It has no capability-specific code and is **reusable as the core security boundary** (class **C, R**, now read in full). | `PluginActionGateway.kt` |
| F21 | Build is a single Gradle module (`app`) with only Compose, activity, lifecycle and coroutines dependencies plus JUnit. No Android instrumentation-test dependencies, so emulator tests cannot run today. minSdk 26, targetSdk 35, compileSdk 36. | `app/build.gradle.kts` |

### 9.3 Effect on the proposed plan

- Step 4 (core decoupling) must also invert `plugins → bubble` and `security → plugins`, and replace the three hard-coded ID tables (F12) with data supplied by registered plugins.
- Step 5 (generic Bubble) now has a concrete shape: keep the shell, `command_manager`, ordering and visibility policy; replace the if/else chain with a plugin-contributed view contract; delete the stale branch (F14).
- Step 6 must design the APK-plugin contract chosen in D1, including how Bubble UI is contributed across process boundaries (likely plugin-supplied declarative content rather than views).
- Add a step: expose both controls (enabled, Bubble access) per plugin on the **Plugins** screen (F15), as Stage 9 requires.
- Add tests for the empty Bubble panel (F17) and the empty-catalogue core (Stage 11).

### 9.4 Still not done

- Line-by-line read of the remaining 800 lines of `FloatingBubbleService` (the request/capture methods), `ActionSecurity.kt`, `SecretVault.kt` beyond line 21, the screen-capture activities, and the tests' actual assertions.
- Dependency map is package-level; a class-level graph is needed before extraction begins.
- Nothing has been run on a device or emulator. CI success means compile + unit tests only.
- Visual design cannot be audited: the original reference was not recovered.


## 10. Update 3 — owner decision and current reconciliation (2026-11-05)

- Owner approved the hybrid extension model recorded as D4 in `docs/DECISIONS.md`.
- Branch head at reconciliation: `6433404d5c272714405dd32c65af07bdcef838f8`. The preceding `6999052` had CI #488 pass. CI #489 on `6433404` failed before a runner or any job steps were recorded; this is not evidence of a source/test failure. No usable job log was available to identify the infrastructure cause.
- The current Stage 2 gate remains open. The line-by-line reviews and class-level dependency map listed in §9.4 have not been completed by this update. Do not infer their completion from the earlier package-level graph.
- No application code, tests, Gradle configuration, APK or release artifact was changed or built by this documentation reconciliation.


## 11. Update 4 — source-level verification against the current implementation (2026-11-05)

### Files and assertions inspected in this pass

Read the current FloatingBubbleService.kt, BubbleContract.kt, BubbleUiPolicy.kt, BuiltInBubbleProviders.kt, BuiltInProviderSupport.kt, BuiltInCatalogue.kt, PluginManager.kt, PluginRuntime.kt, ExternalPluginCenter.kt, OneAiStore.kt, PluginActionGateway.kt, ActionSecurity.kt, SecretVault.kt, MainActivity.kt, AndroidManifest.xml, app/build.gradle.kts, root build.gradle.kts, the screen-capture and voice activities/services, and selected Bubble, catalogue, manager, external-plugin, security and screen-artifact unit tests.

### Earlier findings whose status has changed

| Finding | Current status | Evidence / qualification |
|---|---|---|
| F1 — six navigation sections | Resolved in code | MainActivity.Page.destinations contains Home, Plugins, Develop, Activity and Settings; Commands is a Home sub-screen. The UI itself has not been visually exercised. |
| F4 — capability-specific Bubble shell | Resolved at shell level | FloatingBubbleService now uses BubbleProviderRegistry and generic BubbleServiceProvider contracts; rendering is registry-driven and has no capability-name branch. Capability-specific code remains outside that shell in the interim provider package. |
| F6 — no external extension model | Partially resolved | The signed APK/AIDL SDK, discovery, trust and external plugin centre exist in code. Their real Android binding and process boundary remain unverified; built-in capability code is still in the app module. |
| F7 — no compatibility/Bubble declarations | Partially resolved | External manifest v2 includes min/target core API, commands, capabilities, dependencies and optional Bubble declaration. A plugin settings/activity integration declaration is not present in the manifest model reviewed here. |
| F8 — no extension command framework | Resolved at automated-test level | CommandRegistry/executor and external registrar paths exist; external manifest declarations are registered through the host. Real external plugin binding remains unverified. |
| F9 — tracker has a different stage structure | Resolved | DEVELOPMENT_TRACKER.md uses the official 12-stage sequence. |
| F10 — AutoDroid documented as built-in | Partially resolved | The integration document labels AutoDroid as a future plugin and forbids assuming a remote macro API. The interim adapter/provider and catalogue entry still ship in the app module. |
| F13 — capability-specific showService chain | Resolved | showService resolves a provider by service ID and renders its generic BubbleView. |
| F14 — stale Voice/Automation fallback text | Resolved | The old capability-specific fallback branch is absent from the current FloatingBubbleService. |
| F15 — separate enablement and Bubble-access controls | Implemented in screen source; visual verification pending | PluginsPage shows an Enabled switch and a distinct “Show in Floating Bubble” switch, disabled until the plugin is enabled. The UI has not been rendered/clicked on an emulator or device. |
| F19 — only one named credential slot | Partially resolved | SecretVault now has saveNamed/readNamed encrypted aliases in addition to the primary provider key. The API is app-vault scoped, not a demonstrated plugin-owned secret store; external plugins should keep their own secrets as the Stage 8 design specifies. |
| F21 — no instrumentation dependencies | Resolved as scaffolding | app/build.gradle.kts includes AndroidX test runner/JUnit dependencies and validate.yml compiles androidTest sources. This is compile-only; no instrumentation test has run. |

### Current findings confirmed or added

| # | Finding | Evidence | Required audit/correction consequence |
|---|---|---|---|
| F22 | The six interim built-in Bubble contributors are still declared together in app/src/main/java/com/oneai/app/builtin/BuiltInBubbleProviders.kt. They directly construct app-internal adapters and use OneAiStore, core AI providers, PluginRuntime, and Bubble classes. They are not yet separate reference Gradle modules with a clean module boundary. | BuiltInBubbleProviders.kt; BuiltInProviderSupport.kt | Under D4, separate capability implementations into reference plugin modules that implement the stable SDK contract; do not simply rename the existing package or move UI code while leaving capability logic in core. |
| F23 | BuiltInCatalogue.templates still hard-codes seven plugin records, and PluginManager defaults to that catalogue. Provider bindings are also a hard-coded list in BuiltInBubbleProviders. | BuiltInCatalogue.kt; PluginManager constructor; BuiltInBubbleProviders.bindings | F2/F12 remain open. Replace the production catalogue/bindings with registration/discovery data; retain only generic plugin infrastructure in core. |
| F24 | The core manifest still declares RECORD_AUDIO and FOREGROUND_SERVICE_MEDIA_PROJECTION; it also declares VoiceCaptureActivity, ScreenCaptureConsentActivity, ScreenRegionSelectionActivity and ScreenProjectionCaptureService in the app. | app/src/main/AndroidManifest.xml; voice/screen activity/service source | F5 remains open. Move microphone and MediaProjection ownership to the appropriate future plugin package/module and remove capability-only core permissions/components when safe. Keep overlay permission and the Floating Bubble foreground service as core requirements. |
| F25 | OneAiStore still constructs and exposes PluginRuntime and PluginActionGateway, so core persistence remains coupled to the plugin runtime package. | core/OneAiStore.kt imports and sharedCore() | F11 remains open. Audit an inversion through core-owned interfaces/composition before proposing a safe change; do not break the shared process-wide runtime/gateway behaviour. |
| F26 | The zero-plugin unit tests construct PluginManager with an explicitly empty catalogue and verify BubbleProviderRegistry emptiness in isolation. In production, PluginManager defaults to BuiltInCatalogue.templates, and OneAiApplication calls BuiltInBubbleProviders.registerAll on startup. Thus those unit tests do not yet prove the full production composition starts with no optional plugins available/activated. | PluginManagerTest.coreWorksWithNoPluginsAndAnEmptyCatalogue; BubbleContractTest.registryStartsEmpty; OneAiApplication.onCreate() | Add a composition-level empty-catalogue test after the reference-module registration model is designed. Preserve the user-visible empty-by-default Bubble; distinguish “providers known to the host” from “plugin enabled/Bubble access granted.” |
| F27 | The Bubble's core command_manager still creates a CommandRouter directly and executes saved commands through it rather than the Stage 6 CommandExecutor/CommandRegistry path used by Home. | FloatingBubbleService.renderCommandManager; MainActivity command execution | Carry forward the tracker gap; reconcile the Bubble's core command execution path during the appropriate later stage, not as part of this audit-only pass. |
| F28 | Tests cover policy and contracts but do not render the actual Android Bubble panel or prove the app composition starts empty. Screen-artifact tests cover path containment, size and expiry; region-policy tests cover coordinate mapping. | BubbleContractTest, BubbleUiPolicyTest, ScreenCaptureArtifactPolicyTest, ScreenRegionPolicyTest | Add explicit integration coverage at the appropriate stage; keep emulator/device acceptance separate. |
| F29 | Developer Mode's current plugin creation path persists a validated declarative PluginRecord; it does not compile, package or run plugin code. | PluginManager.create() and Developer screen | Align Developer Mode with the SDK/reference-module workflow. Do not introduce arbitrary in-process code loading. |

### Security and external-plugin test observations

- CapabilityPolicy rejects disabled plugins, unknown or undeclared capabilities, and marks sensitive capabilities for approval. OneTimeApprovalGate binds a short-lived ticket to the exact plugin/action/capability/description and consumes it once.
- PluginActionGateway re-checks installed/enabled state and records bounded outcomes; PluginRuntime validates the installed declaration, checks approval immediately before execution, re-checks lifecycle state and contains adapter exceptions.
- ExternalPluginCenterTest exercises fingerprint confirmation, disabled-by-default import, update consent, certificate mismatch, dependency/service conflicts, separate Bubble access and removal using fake signatures/channel dependencies. It does not prove real Android binder IPC.
- ExternalPluginManifestTest covers identity/version/API validation, declared capabilities, command bounds, optional Bubble declarations, dependency validation and update consent.
- ExternalBubbleProviderTest checks declarative view parsing and that buttons produce host-mediated plugin calls with exact argument review.
- BubbleContractTest includes a source guard against capability-specific references in the Bubble shell; the guard is a static text check, not a runtime proof.
- docs/EMULATOR_TEST_PLAN.md remains scaffolding only. Its nine real-binder instrumented tests and manual plugin/Bubble checks have not been run. No APK or emulator/device run was performed during this audit.

### Audit completion still outstanding

This pass narrows and corrects the previous findings, but it is not a complete file-by-file audit. Remaining work includes a full class-level dependency map; review of all AI orchestration/provider and command registry/executor implementations; full review of app composition, settings and activity-history persistence; the remaining external SDK/registrar/discovery/AIDL implementation; the complete test suite's assertions; and reconciliation of every file classification and migration dependency against D4. Stage 2 must remain in progress until those items and the final correction plan are complete.
