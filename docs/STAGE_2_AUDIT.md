# ONE AI — Stage 2 Repository Audit and Proposed Correction Plan

> **IMPORTANT — HISTORICAL AUDIT / SCOPE LOCK:** This file contains older recommendations written under a mistaken interpretation of plugin scope. The owner-approved 2026-11-07 correction in the final section and `docs/PROJECT_CONTINUATION_RULES.md` supersedes every older capability-extraction/reference-module sequence in this file. Do not execute the old Stage 9 extraction plan. Stage 8 is the generic SDK; Stage 9 is the generic Kodi-inspired Plugin Centre and generic Bubble integration. Named capability plugins are future examples only.

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


## 12. Update 5 — completed Stage 2 source review and final dependency/migration map (2026-11-05)

This update supersedes the earlier “still not done” statements in §§2, 5, 6, 8, 9.4 and 11 where they conflict with the later source review. The audit is now ready for owner review; implementation corrections have **not** begun.

### 12.1 Evidence baseline and official-plan alignment

- Reviewed against immutable source commit `ea7573a2883a3b4b700663edab450471b4d8a90a` and the documentation-only tracker reconciliation at `8894a9f83cd806b3bd445da1fd5abf6b3f923495`.
- CI #493 on `ea7573a` and CI #494 on `8894a9f` both failed before a runner/validation steps were recorded; neither returned usable job logs. They do not establish a Kotlin, Android, lint or test failure. The precise infrastructure cause is unknown.
- The official plan PDF (revised 3 November 2026) was read through all seven pages. Its Stage 2 exit criterion is a file-by-file audit, dependency map and approved correction plan. Its fixed sequence remains Stages 1–12; no stage has been added, removed or reordered.
- The plan explicitly classifies the AI orchestration engine, generic command framework, plugin manager/SDK, security/approvals, settings/activity, Floating Bubble shell/settings and plugin-to-Bubble integration framework as core. Voice, screen capture/Screen Intelligence, Web Research, App Control, Device Control, AutoDroid and future automation are future plugins.
- D4 (hybrid model) is owner-approved. The detailed migration plan below is ready for owner review; Stage 2 should not be marked accepted until that correction plan is accepted.

### 12.2 Class-level dependency map (reviewed imports and composition)

This is the concrete class-level graph for the audited execution path; arrows mean “depends on / constructs / calls”, not merely a conceptual ownership relationship.

| Source class | Direct dependencies observed | Architectural assessment |
|---|---|---|
| `OneAiApplication` | `BuiltInBubbleProviders.registerAll`, `BubbleProviders.registry`, `AppServices.get().restorePlugins()` | Composition root currently registers all six interim capability providers directly. Under D4 this registration must come from reference-plugin registration, not a permanent core capability list. |
| `AppServices` | `OneAiStore`, `CommandRegistry`, `PluginManager`, `ExternalPluginCenter`, `ExternalPluginRegistrar`, `PluginDiscovery`, `BoundPluginChannelFactory`, `PluginTrustRegistry`, `BubbleProviders.registry`, `BuiltInCatalogue` | Useful single process-wide composition point. It currently owns built-in reserved IDs and uses the hard-coded catalogue; keep composition in core but inject/receive module registrations. |
| `MainActivity` / `OneAiApp` | `AppServices`, `OneAiStore`, `AiProviderClient`, `AiOrchestrator`, `RetryingAiProvider`, `CommandRouter`, `CommandRegistry`, `CommandExecutor`, `PluginManager`, `ExternalPluginCenter`, `SecretVault`, `FloatingBubbleService` | Core UI. Five navigation destinations are correct. Settings still contains capability-specific Web Research credential controls and built-in Bubble binding logic; move plugin-specific settings into the owning reference/external plugin while keeping generic plugin settings and Bubble controls in core. |
| `OneAiStore` / `PluginStore` | Implements `AiActivityLogger`, `CommandRepository`, `ActionAuditLogger`, `PluginStore`; constructs `PluginActionGateway` and `PluginRuntime` | Confirmed inversion: core persistence imports and exposes plugin runtime/security concrete types. Shared process-wide runtime behaviour is needed, but it must be composed above storage through core-owned contracts. |
| `AiOrchestrator` | `AiProvider`, `AiActivityLogger`, `AiConversation`, `ContextPolicy` | Good provider-neutral core boundary. It validates prompt size, records completed exchanges only, preserves cancellation and bounds context/history. |
| `AiProviderClient` | `OneAiStore`, `SecretVault`, HTTP/JSON transport, `AiResponseBodyReader` | Reusable core OpenAI-compatible chat adapter. Store access should eventually be expressed through a small provider-settings interface rather than the full persistence class. |
| `OpenAiCompatibleVisionProvider` / `BraveSearchProvider` | Vision provider → `OneAiStore`, `SecretVault`, Android Base64 and HTTP; Brave → `SecretVault`, `WebSearchPolicy`, HTTP | Capability-specific future-plugin implementations, not permanent core AI features. The provider-neutral `AiVisionProvider` and `WebSearchProvider` seams can remain core contracts only if they are capability-neutral and do not own the capability implementations. |
| `CommandRouter` | `AiOrchestrator`, `CommandRepository`, `CommandRecord` | Saved-prompt command runner. This is distinct from extension command registration; preserve the saved-command feature but route it through the common core command execution surface. |
| `CommandRegistry` / `CommandExecutor` | `CoreError/CoreErrors`, `PluginActionInput`, `PluginInvocationResult`, `PluginManifestValidator`; extension invoker supplied by composition | Generic core command infrastructure. Namespacing, owner availability, bounds, result statuses and runtime invocation are present and tested. |
| `PluginManager` | `PluginStore`, `PluginRecord`, default `BuiltInCatalogue.templates`, `PluginRuntime`, `PluginActionGateway` through the store | Generic lifecycle/enablement logic is reusable. The production default catalogue and its built-in service lookup are the hard-coded extension list to remove. |
| `PluginRuntime` | `PluginActionGateway`, `ActionRequest`, `PluginRecord` | Reusable host execution/security boundary. It currently depends on app persistence's `PluginRecord`; move the record/contract shape to the core SDK boundary without allowing feature code into the core. |
| `PluginActionGateway` / `CapabilityPolicy` / `OneTimeApprovalGate` | `PluginRecord`, generic action policy and audit interface | Reusable generic security infrastructure. Approval tickets are one-use, exact-action, short-lived and revoked on lifecycle changes. |
| `PluginActionButton` | `PluginRuntime`, `PluginActionInput`, `PluginInvocationResult` from the plugin package | Inverted dependency: security/UI code imports plugin implementation types. Replace with a core-owned action-execution interface. |
| `BubbleContract` / `BubbleProviderRegistry` | `PluginActionInput/Output`, `PluginInvocationResult`, `PluginRuntime`, `ActionRequest` | The generic Bubble contract is a core responsibility, but it is typed directly against plugin runtime classes. Move its public types to the stable core SDK contract and inject a generic action gateway/runtime interface. |
| `FloatingBubbleService` | Bubble contract/registry, `OneAiStore`, `AiProviderClient`, `AiOrchestrator`, `RetryingAiProvider`, `CommandRouter`, `PluginRuntime`, `ActionGateResult`, `SecretVault` | Capability-specific branches have been removed and provider rendering is registry-driven. It still directly constructs AI/storage/command services and uses the legacy saved-command router; reduce it to overlay, generic service rendering, generic approval/result handling and shared core commands. |
| `BuiltInBubbleProviders` / `BuiltInProviderSupport` / `VoiceSupport` | All six capability adapters, AI/search/vision providers, Bubble capture/voice activities, store, runtime, security and vault | Transitional feature composition, not core. Split by capability into reference modules; move voice and screen-capture Android components with their plugins. |
| `ScreenIntelligenceAdapter` | `AiVisionProvider`, `ScreenCaptureArtifactPolicy/Store` from Bubble, `Context` | Explicit plugin→Bubble dependency. Move artifact ownership/consumption behind a screen-plugin-owned contract or a generic host-provided artifact handle; do not make the generic Bubble depend on Screen Intelligence. |
| `WebResearchAdapter` / `VoiceInterfaceAdapter` | Web adapter → search provider/policy; Voice adapter → shared `AiOrchestrator` | Feature adapters can use the stable AI service contract, but their UI, credentials and platform capture/speech implementations belong to the corresponding reference plugin. |
| `ExternalPluginCenter` / `ExternalPluginRegistrar` | `PluginTrustRegistry`, `PluginManager`, `CommandRegistry`, `PluginStore`, `ExternalPluginAdapter`, Bubble registration callbacks | Generic plugin infrastructure. It already separates import, enablement, Bubble access, update consent and removal; keep this host-owned and capability-neutral. |
| `BoundPluginChannelFactory` / `AndroidPluginPlatform` | Android PackageManager, ServiceConnection, AIDL `IOneAiPlugin/IPluginCallback`, `PluginBounds`, signature/trust store and XML resources | External APK transport and discovery. Keep in the Android host infrastructure; verify with Stage 11 runtime tests. |
| `OneAiPluginService` / `PluginCallRunner` / `HostVerifier` | SDK AIDL, bounds, caller package/certificate check, worker/watchdog and callback | Reusable SDK-side contract. Fail-closed release certificate configuration remains blocked until the release key exists. |
| `EchoPluginService` / `EchoCommands` | Only `:one-ai-plugin-sdk` | Valid separate-APK reference for the external contract; it is not yet a built-in capability reference module. |

### 12.3 Confirmed dependency cycles and boundary corrections

1. **Core → plugin implementation:** `core/OneAiStore.kt` imports `PluginRuntime` and `PluginActionGateway`; `core/PluginStore.kt` exposes those concrete types. The store is not a neutral core boundary.
2. **Security → plugin implementation:** `security/PluginActionButton.kt` imports plugin action/result/runtime classes.
3. **Core Bubble contract → plugin implementation:** `bubble/BubbleContract.kt` exposes plugin action/result/runtime types in `BubbleAction` and `BubbleServiceProvider.ensureReady`.
4. **Plugin → Bubble:** `plugins/ScreenIntelligenceAdapter.kt` imports Bubble screen-artifact policy/store. The capture artifact must become plugin-owned or a generic host capability handle.
5. **Feature composition in core:** `OneAiApplication` invokes `BuiltInBubbleProviders.registerAll`; `AppServices` and `PluginManager` rely on `BuiltInCatalogue`; the settings screen reads `BuiltInCatalogue.bubbleBindings`. The same capability catalogue is therefore a source of truth across composition, manager, Bubble and settings.
6. **Command path split:** Home uses `CommandExecutor` and `CommandRegistry`; the Bubble's `renderCommandManager` constructs a new `CommandRouter` and `AiProviderClient` directly. `core/run_saved` also depends on `AppServices.commandRouter`, which is populated only while `MainActivity` is alive. Saved-prompt commands and plugin-registered commands are distinct concepts; retain both, but expose a single core execution path to the Bubble and screens.
7. **Plugin settings ownership:** `MainActivity.SettingsPage` owns Brave Search API-key controls and built-in capability binding selection. The AI provider key belongs to core AI; Brave Search credential/configuration belongs to Web Research. Plugin-specific configuration screens are not represented in the external manifest/registration contract yet.

### 12.4 AI, command, app-composition, settings and activity review

**AI engine (core / reusable):**
- `AiProvider`, `AiOrchestrator`, `AiConversation`, `ContextPolicy`, `RetryingAiProvider`, `AiResponseBodyReader` are generic and should remain core.
- Chat provider requests require HTTPS, disallow embedded credentials/fragments and redirects, apply connect/read timeouts, cap response bodies at 1 MiB and avoid logging credentials or prompt/response content.
- Conversation history is in-memory only, bounded to 200 stored messages and 40/24,000-character context. It is intentionally not persisted.
- Retry is limited to network/request, HTTP 429 and HTTP 5xx errors; cancellation is propagated. The tracker’s known duplicate-billing risk after a timed-out request remains.
- Tests cover orchestration, context limits, retry classification/backoff, cancellation, response-body bounds and endpoint validation. There are **no HTTP integration tests** for `AiProviderClient`, `OpenAiCompatibleVisionProvider` or `BraveSearchProvider`; no live provider call has been made. Vision and search adapters are future-plugin implementations.
- `AiOrchestrator` truncates provider exception text when logging failure. The provider client maps failures to safe codes/messages, but generic provider adapters must preserve that no-secret/no-prompt logging rule.

**Command framework (core / reusable, with correction):**
- `CommandRegistry` owns `owner/name` IDs, core/extension distinction, duplicate protection, owner-enabled filtering and per-plugin command count bounds.
- `CommandExecutor` validates arguments/output, maps completion/failure/rejection/approval/not-found statuses, contains plugin exceptions, propagates cancellation and invokes extension actions through the injected plugin runtime.
- `ExternalPluginRegistrar` registers only manifest-declared external commands. External integration tests cover approval, disabled state, trust changes, timeouts, updates, restore, removal and command namespacing using fake channels.
- `CommandRouter` is a saved-prompt repository/runner, not a replacement for `CommandRegistry`. Bubble still bypasses `CommandExecutor`; no registry command browser or general execution-status UI exists. These are migration/test gaps, not reasons to remove saved commands.

**App composition / persistence:**
- `AppServices` is a process singleton shared by UI and Bubble, and `OneAiApplication` restores trusted external plugins on process start. This is reusable.
- There is no composition-level test proving the complete production app starts with zero optional capability providers; current zero-plugin tests exercise a supplied empty catalogue and a standalone empty Bubble registry only.
- `OneAiStore` persists plugin records, saved commands, Bubble enabled/services/order, AI endpoint/model/provider, Developer Mode and up to 150 activity events in SharedPreferences. Activity entries have timestamps. Conversation text is not persisted.
- No direct unit/instrumented persistence test covers `OneAiStore`, `AppServices`, `OneAiApplication` or the production composition lifecycle.

**Settings / activity / credentials:**
- Core AI endpoint/model/provider configuration and its primary key are core settings. `SecretVault` uses separate Android Keystore AES-GCM aliases and SharedPreferences ciphertext/IV storage.
- The Brave Search key uses a named vault alias, but it is still stored in the host app; it must move to the Web Research reference/external plugin with that feature.
- Activity retains the newest 150 local events and can be cleared by the user. The UI displays plugin progress as plugin-originated. AI prompt/response content and command arguments are not intentionally logged by the reviewed paths.
- `SecretVault` has no Android Keystore instrumentation coverage; no tests exercise key rotation, invalidated keys or persistence across process/device restart. Plugin-scoped settings and settings-Activity integration are not yet part of the manifest model.

### 12.5 External SDK, AIDL and security review

- Manifest v2 uses APK package identity, semantic version/versionCode, min/target core API, declared capabilities/commands/dependencies and optional declarative Bubble service. Validation bounds command counts, capabilities, dependencies, identifiers and text.
- Import requires explicit fingerprint confirmation; trust is pinned by package name and certificate, checked before every bind, and updates that increase declared powers require consent. Multiple signers are rejected. Discovery reads package metadata/XML without executing plugin code.
- Host binding resolves exactly one exported, permission-free plugin service, binds it, rechecks trust after connection, then validates the JSON handshake. AIDL uses bounded parallel string arrays, callbacks, view JSON and cancellation. SDK runner enforces bounded arguments/results, one in-flight request, deadline, cancellation, one reply, safe error codes and failure containment.
- `CapabilityPolicy`, `PluginActionGateway` and `PluginRuntime` retain host-owned approval and execution decisions. The plugin cannot grant itself capabilities or draw a Bubble overlay.
- The external-plugin, manifest, trust, center, view parser, SDK runner and sample-contract tests are JVM/fake-channel tests. The one Android instrumentation class contains nine real-package/binder checks, but CI only compiles it; it has never run. No emulator/device evidence exists for discovery, package resource reading, signing checks, binding, callbacks or process isolation.
- **PendingIntent screen-launch path is incomplete:** the SDK runner emits `onLaunch` and the AIDL callback declares it, but `BoundPluginChannelFactory` currently drops `onLaunch` (commented Stage 9.4c work). Do not describe plugin-requested screens as working.
- **Version-label reconciliation (resolved in Stage 8 review):** PluginApi.LEVEL is the manifest host-feature API; PluginBounds.SDK_API is the AIDL SDK wire-contract version returned by describe(). They are distinct concepts. The current SDK wire version is now 2 (the API 2 callback is part of the contract); the host accepts wire versions 1 through 2. Manifest minCoreApi="2" is required when a plugin uses the API 2 screen-launch feature. Regression tests cover v1 backward compatibility, v2 acceptance and rejection by a v1 host.
- `OneAiHost.RELEASE_CERTIFICATES` is empty; the SDK correctly fails closed for release callers until the owner supplies a release signing key/certificate. Development override is not a release solution.
- Documentation drift was reconciled in the Stage 8 review: the developer guide now describes API 2 and the existing Stage 9 Plugins UI; the Stage 8 design records the implemented AIDL contract and current gaps; the emulator plan treats import UI checks as available but unverified. PLUGIN_SDK.md remains explicitly legacy manifest-v1 documentation and is not the current external SDK guide.

### 12.6 Test inventory and evidence classification

The repository tree at the audited SHA contains **41 test source files**: 40 JVM test files across app, SDK and sample modules, plus one Android instrumentation test file containing nine tests.

- AI tests cover orchestration, context windows, retries, cancellation, provider endpoint policy, web URL/query policy and bounded response reading. No live HTTP/provider tests.
- Command tests cover saved-command validation/lifecycle, registry namespacing, extension execution, approvals, bounds, safe errors, cancellation and log privacy.
- Core/security tests cover core service lifecycle, safe errors, capability policy, one-use approval and gateway outcomes.
- Plugin tests cover catalogue invariants, plugin lifecycle/dependencies, runtime validation, manifest parsing/compatibility, certificate trust/update consent, external center, fake-channel execution, Bubble declarations, adapter policies and sample contract.
- Bubble tests cover registry/policy, generic shell source guard, view parser, handoff bounds, screen artifact path/size/expiry and region coordinate mapping. They do not render the real Android Bubble panel or test production composition.
- SDK/sample tests cover host verification logic, bounds, runner concurrency/deadlines/cancellation/errors, launch callback ordering and sample commands. The real AIDL/Android service boundary remains unexecuted.
- The Android instrumentation file has nine tests for discovery, manifest resources, fingerprint import, real binding, handshake, commands, view, progress and timeout. It is **compile-only in CI**, never executed.
- Missing coverage that must be added in the appropriate later stage: production empty-catalogue composition; `OneAiStore`/vault persistence; HTTP transport/error/credential handling; actual Compose settings/import controls; actual Bubble panel rendering and Bubble command execution through the shared executor; PendingIntent launch host approval; real Android bind/signature/process-isolation tests.

CI definition remains compile + JVM unit tests + lint + compile-only Android-test sources. No APK, emulator or device run is implied by a green CI result.

### 12.7 HISTORICAL / SUPERSEDED migration order — DO NOT EXECUTE (2026-11-07 owner scope correction)

The order below is a correction sequence mapped to the existing stages, not a replacement plan or a new stage.

1. **Stage 3 — Core boundary and empty-shell composition:** define the stable core-owned plugin/command/Bubble contracts; invert `PluginStore`/storage away from concrete `PluginRuntime` and `PluginActionGateway`; replace the `PluginActionButton` dependency on plugin implementation types with a core action interface; move plugin action/result types used by Bubble to the core SDK contract. Preserve one process-wide runtime/gateway through composition. Add a production-composition test proving the core starts with no optional providers.
2. **Stage 6 — One core command execution surface:** retain saved-prompt `CommandRouter` as a repository/handler, but route Home and Bubble through the shared `CommandExecutor`/registry. Remove Bubble's direct construction of `AiProviderClient`, `SecretVault` and a second router. Keep extension commands owner-scoped and routed through the host runtime/approval boundary.
3. **Stage 7 — Generic Bubble acceptance:** retain overlay, drag/size/position, core command entry, generic provider registry, result/approval rendering and empty-by-default state. Make the Bubble contract depend only on core SDK interfaces. Add production-panel and independent Bubble-access tests. Do not add capability-specific code back into `FloatingBubbleService`.
4. **Stage 8 — Stable SDK and hybrid reference contract:** reconcile the versioned SDK contract, AIDL handshake/version semantics, command registration, declarative Bubble contributions, plugin settings/activity hooks and error reporting. Keep external signed APKs on the secure AIDL boundary; no folder/dex loader.
5. **Stage 9 — Replace compiled-in catalogue and extract reference plugins:** remove the default seven-entry `BuiltInCatalogue` and hard-coded provider bindings from core composition. Register optional reference modules through the stable contract. Extract capabilities in the already-recorded Stage 9 order: **App Control → Device Control → AutoDroid → Web Research → Voice → Screen Intelligence**. Move each capability's UI, adapters, Android activities/services/permissions, credential/settings ownership and tests together. The six reference modules must implement the same contract used by external APK developers.
6. **Stage 10 — Security, permissions, settings and activity ownership:** keep generic approvals, trust, credential encryption and Activity in core. Move `RECORD_AUDIO`, MediaProjection service/consent and capability-only package visibility/queries to their plugin modules when extraction is complete; retain overlay and special-use foreground-service requirements for the core Bubble. Move Brave Search credentials to Web Research. Add plugin-owned settings integration and verify activity/log redaction.
7. **Stage 9.4 follow-through / Stage 8 contract validation:** complete host handling for plugin `PendingIntent` launch only with explicit user confirmation and creator/identity validation; document and test the distinction between manifest core API and AIDL wire SDK version. This work remains a plugin integration dependency, not permission to bypass Android background-activity restrictions.
8. **Stage 11 — Full acceptance:** execute the documented acceptance matrix with separate CI, JVM, integration, emulator and physical-device results. Include fresh install/empty catalogue, AI without plugins, all five sections, Bubble empty on first enable, separate plugin/Bubble toggles, plugin import/bind/restore/removal, permission denial, failure isolation, settings persistence, Bubble command routing and real PendingIntent flow. Instrumented tests require explicit APK-build authorisation before execution.
9. **Stage 12 — Foundation release preparation:** configure the owner-approved release signing key/certificate, populate `OneAiHost.RELEASE_CERTIFICATES`, complete final security/design/SDK review and release documentation. APK build/release remain blocked until explicit owner authorisation.

### 12.8 Stage 2 disposition

The original source review and test inventory have now been reconciled against the owner-approved 2026-11-07 scope. **Stage 2 is complete as a repository audit and disposition record; this does not mean Stages 3–10 are implementation-complete.** The final corrected-scope findings and retain/correct/remove/implement classifications are recorded in §12.10. No application source, test, manifest, Gradle configuration or runtime behaviour was changed during this reconciliation. Android Validation #584 passed on exact audited SHA `19164516fbb2cb62a0848651d73bd758c1fed73f`; it ran compile/JVM tests/lint and compile-only instrumentation sources, not an APK or device test. No APK was built and no merge was performed.


### 12.9 Historical Stage 8 SDK review notes (2026-11-05; capability-extraction recommendations superseded)

The Stage 8 implementation was reviewed against owner-approved D4 and the six-step breakdown in docs/STAGE_8_DESIGN.md §11.

- External SDK baseline present: manifest v2 and compatibility checks; explicit fingerprint trust and update-consent rules; separate one-ai-plugin-sdk Android library with AIDL, host verification, bounded execution/deadlines/cancellation; host discovery, binding, adapter and registrar; strict declarative Bubble views; and the separately installed Echo sample APK.
- Versioning corrected: PluginBounds.SDK_API now reports wire SDK API 2, matching the implemented AIDL callback contract. Host compatibility accepts wire versions 1–2. PluginApi.LEVEL remains the separate host-feature API; API 2 screen-launch use requires minCoreApi="2". Tests cover both wire versions and the older-host rejection case.
- D4 mapping: the external signed-APK contract is the public extension path. The Echo sample validates that path but is not a built-in reference capability. The six built-in capabilities remain interim in-app implementations and must be extracted in Stage 9 as separate reference modules using this same contract. No arbitrary dex/class loading is introduced.
- Already implemented in Stage 9: Plugins discovery/import UI, fingerprint review, enabled and independent Bubble-access switches, update consent/removal, process-wide restoration and Settings listing. These are not to be rebuilt; they remain visually and device unverified.
- Still outstanding: the host currently drops API 2 onLaunch callbacks; Stage 9.4c must implement user confirmation and PendingIntent creator/identity validation. The standalone plugin-author fake-host test kit listed in design §8 is not packaged yet. Real Android binding/discovery/signature/process-isolation and Bubble rendering remain Stage 11 acceptance work. OneAiHost.RELEASE_CERTIFICATES remains empty until the release key is configured in Stage 12.
- Disposition: the Stage 8 source/design review is reconciled; this does not mark Stage 8 Android acceptance complete, does not close Stage 9, and does not authorize APK builds or PR merge.


# OWNER-APPROVED SCOPE CORRECTION — 2026-11-07 (AUTHORITATIVE)

This section supersedes any conflicting migration sequence or recommendation earlier in this historical audit. The owner approved the corrected roadmap and clarified that individual capability plugins are not current deliverables.

## Binding interpretation

- Stage 8 is the generic plugin architecture, secure contract, SDK, and developer tooling. It does not require built-in reference capability modules.
- Stage 9 is the generic Kodi-inspired Plugin Centre and generic integration with the ONE AI host and Floating Bubble. It includes discovery/catalogue, import/install, installed-plugin information, trust, compatibility, dependencies, enable/disable, configuration, updates, removal, lifecycle, status, permissions, approvals, and independent Bubble access.
- Browser/Web Research, Voice, Screen Intelligence, App Control, Device Control, AutoDroid, automation, and any other named capabilities are illustrative future plugin ideas only. They are not current tasks, milestones, acceptance criteria, or foundation-release dependencies.
- Do not extract, migrate, package, build, integrate, or prioritise individual capability plugins. Do not resume the old capability-extraction sequence.
- Existing capability code may be inspected only where needed to identify a concrete dependency preventing standalone Core operation. Preserve code/history and avoid speculative deletion or migration.
- Existing generic SDK, trust, AIDL, plugin lifecycle, Bubble, and security work should be reused; do not rebuild completed work.

## Audit disposition correction

The previous audit's sections describing six reference capability modules, a named capability extraction sequence, and capability-specific permission/credential migration are historical proposals and are **superseded**. They are not authorized work. Existing reference modules are not current priorities or acceptance dependencies. Any future removal/exclusion from the foundation build must be justified by a focused dependency audit and remain within the approved stage and branch boundaries.

## Required next audit action

Reconcile the actual repository against the corrected 12-stage roadmap. Report concrete generic Core/Plugin Centre requirements as retain, correct, remove, or implement, with file/dependency evidence and existing test/CI evidence. Do not make application-code changes until that reconciliation is complete and the relevant implementation scope is authorized under the project rules.

See `docs/PROJECT_CONTINUATION_RULES.md` for mandatory cross-AI handoff rules and source-of-truth hierarchy.


### 12.10 Final repository reconciliation against the owner-approved scope (2026-11-07)

**Audited branch:** `foundation/validation-core-shell-startup-isolation`  
**Audited HEAD:** `19164516fbb2cb62a0848651d73bd758c1fed73f`  
**Validation:** Android Validation #584 / run `42373107263` — **success** on the exact audited SHA. The workflow compiled the app, ran the JVM unit-test suite, lint and compile-only Android instrumentation-test sources. It did not build an APK or execute instrumentation, emulator or device tests.

This is a corrected-scope audit, not a second implementation pass. Existing work and test evidence are retained; the obsolete capability-extraction sequence in §12.7 is historical only and must not be executed.

#### Retain — existing generic platform work

- **Standalone Core composition:** `AppServices.kt` creates `PluginHostComposition` with an empty optional-plugin catalogue and the process-wide `BubbleProviders.registry`; the only automatically registered command is the generic `core/run_saved`. `PluginHostComposition.kt`, `PluginExecutionServices.kt`, `OneAiStore.kt`, `PluginRestorationGate.kt`, and `OneAiApplication.kt` establish the shared host/runtime and non-blocking startup restoration boundary. The source-level production-boundary regression test and CI #581/#584 evidence are retained. This proves the current production composition is empty-by-default at source/test level, not Android runtime acceptance.
- **Generic command and Bubble contracts:** retain the core-owned action/result DTOs, shared command executor, generic Bubble provider registry, independent Bubble access, and empty-by-default panel policy. Do not reintroduce capability-specific registrations into production composition.
- **External plugin contract and trust:** retain manifest v2 validation/compatibility, signer fingerprint confirmation and re-check, update-consent policy, AIDL SDK module, bounded execution, host adapter/registrar, declarative Bubble parser/provider, and the Echo contract sample. These are generic extension-platform components. The sample is a contract test/reference, not a required capability plugin.
- **Plugin Centre operations already present:** retain device package discovery, fingerprint-reviewed import, installed-plugin listing, enable/disable, separate Bubble access, one-shot explicit update checking, update review, removal, and process restoration of only previously enabled, still-trusted plugins. Reuse existing UI and tests; do not rebuild these flows.
- **Generic dependency and compatibility foundations:** retain manifest dependency declarations, compatibility checks, enable-time dependency enforcement, dependent-disable/removal guards, and signer/update blocking. Their user-facing completeness is a separate remaining item below.
- **Security and audit foundations:** retain the central capability policy, one-use approval gateway, plugin runtime failure containment, trust records, encrypted vault, Activity logging, and core Settings/Activity surfaces.

#### Correct — documentation and residual generic-host boundaries

- `docs/STAGE_8_DESIGN.md` still describes built-in capability extraction as Stage 9 work. That text conflicts with D4 and the mandatory 2026-11-07 continuation rules. Mark the extraction sequence superseded; Stage 8 remains generic SDK/contracts/developer tooling and Stage 9 remains the generic Plugin Centre.
- `docs/HANDOFF_PROMPT.md` is a stale historical handoff: it directs a future AI to work on the protected original branch `foundation/batch-03-ai-engine`, and its snapshot predates the current Plugins UI and corrected scope. It must not be used as the live continuation instruction. `docs/PROJECT_CONTINUATION_RULES.md` is authoritative.
- `docs/AUTODROID_INTEGRATION.md` describes the old `one.automation.engine` built-in adapter as slated for extraction. Treat it as historical implementation context only, not an active roadmap item.
- `BuiltInCatalogue.kt` is no longer the production optional-plugin catalogue: `AppServices.kt` passes `emptyList()` to `PluginHostComposition`. However, the old catalogue is still consulted to reserve IDs and Bubble service names in `AppServices.kt`/the registrar. Review this reservation source in the appropriate generic Plugin Centre stage so optional capability names do not accidentally become permanent Core-reserved identifiers. Do not delete the old catalogue or adapters as part of this audit.
- `MainActivity.kt` currently labels the Plugin Centre as an optional-capability manager, which is compatible with the corrected scope, but its empty production catalogue and generic management gaps below should be made explicit in future UX work.

#### Implement — concrete generic gaps remaining in Stages 8–10

1. **Stage 8 — generic developer tooling:** the SDK, developer guide and Echo contract sample exist, but the standalone reusable plugin-author fake-host/test kit described in `docs/STAGE_8_DESIGN.md` §8 is not packaged. The Develop screen currently creates a declarative plugin descriptor; it is not yet a complete generic authoring/validation/test workflow. Complete this without creating a capability implementation.
2. **Stage 9 — generic catalogue/discovery:** production `PluginHostComposition` receives an empty catalogue, and `ExternalPluginCenter.snapshot()` discovers already-installed apps exposing the plugin service. The Plugins UI therefore has installed items and “Found on this device” discovery, but no browsable generic plugin catalogue/repository source. Implement the owner-approved generic catalogue/discovery experience without hard-coded capability entries.
3. **Stage 9 — plugin configuration:** the manifest, `PluginRecord`, `PluginManager`, `ExternalPluginCenter`, `OneAiStore`, and Plugins UI do not currently expose a generic per-plugin configuration contract, persistence model, or configuration screen/host hand-off. Add a safe, versioned, plugin-neutral configuration mechanism; do not add capability-specific settings to Core.
4. **Stage 9 — dependency and lifecycle UX:** dependencies are validated and enforced at enable/disable/removal boundaries, but the current installed-plugin card does not show dependency state/details or explain a blocked dependency action beyond the returned error. Compatibility is shown for discovered manifests. Add clear generic dependency/compatibility details and useful per-plugin lifecycle/diagnostic status, preserving existing enforcement.
5. **Stage 9/10 — permission and approval visibility:** capability declarations and just-in-time approvals are enforced by the host, and the import review shows declared capabilities. The current Plugin Centre does not provide a consolidated per-plugin view of declared capabilities, relevant Android permission ownership, approval policy, and recent safe status. Add visibility without turning one-use approvals into standing grants or bypassing Android permission prompts.
6. **Stage 8/9 — generic contract tests:** keep JVM regression coverage. Package the fake-host test kit and add host/plugin contract tests for configuration, dependency failure paths, lifecycle/status reporting, and generic catalogue entries as those contracts are implemented. Real AIDL binding, package discovery/signature reads, process isolation, and Bubble rendering remain Stage 11 emulator/device acceptance, not CI claims.

#### Preserve / do not remove during this audit

- The repository still contains historical or optional capability-specific files under `app/src/main/java/com/oneai/app/builtin`, several adapter files under `app/src/main/java/com/oneai/app/plugins`, reference-plugin modules, and related manifest declarations. Current production composition does not register the optional providers. Their continued presence is not proof that they are required for the empty Core shell, but neither is it permission for mass deletion. Preserve them and repository history. Any later exclusion/removal requires a focused dependency/build/manifest audit showing a concrete conflict with the approved standalone Core boundary.
- No capability APK extraction, packaging, implementation, or integration is a current deliverable. The existing App Control/Device Control reference modules are not Stage 8/9 acceptance dependencies.
- No application-source changes, speculative deletions, APK builds, merges, PR changes, or protected-branch changes were made as part of this Stage 2 reconciliation.

#### Stage 2 disposition

**Stage 2 — Existing Repository Audit: COMPLETE (audit/documentation evidence).** The repository has been classified against the corrected scope, completed work and validation evidence have been retained, obsolete instructions identified, and concrete generic Stage 8/9 gaps recorded. Continue with the next incomplete requirement in official stage order; do not repeat completed Stage 3–7 implementation or treat Stage 11 device acceptance as already performed.
