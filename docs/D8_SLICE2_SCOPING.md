# D8 slice 2 scoping check (read-only)

- **Date:** 2026-11-08. **Basis:** source head `cced51f` on `foundation/validation-core-shell-startup-isolation` (CI #639 passed on the last source commit `affd53f`).
- **Type:** read-only scoping. No source, manifest, build file or test was changed. Docs only.
- **Evidence level:** grep over `app/src/main`, `app/src/test`, `app/src/androidTest`, the manifest, `app/build.gradle.kts` and both workflows. Source inspection, not runtime evidence.
- **Purpose:** decide whether slice 2 of D8 (move capability code, manifest entries and tests out of the Core APK) can be done as written in `docs/DECISIONS.md` D8/D-M, and what it needs.

## 1. Finding that changes the plan

D-M says to move the capability code into "a Gradle module that `:app` does not depend on". **That cannot work as a plain library module**, because the capability code imports about 20 Core types that live inside `:app` (`PluginAdapter`, `PluginAdapterRegistrar`, `PluginActionInput`, `BubbleHost`, `BubbleElement`, `BubbleServiceProvider`, `BubbleProviderRegistry`, `OneAiStore`, `SecretVault`, `AiOrchestrator`, `AiProviderClient`, `RetryingAiProvider`, ...). An Android library module cannot depend on an application module, so a new module would have no way to compile against them. This was not visible in the earlier audit, which only checked the reverse direction.

## 2. What is safe: the reverse direction is clean

Outside the capability files themselves, Core production code references capability code in exactly one place: the default argument `PluginManager(catalogue = BuiltInCatalogue.templates)`. Production passes an empty list. `FloatingBubbleService` does not reference `ScreenCapture*`, and `imagePath` stays in the generic handoff (D-O).

## 3. Files in scope for slice 2 (all unreachable in production per the earlier audit)

| Area | Files (lines) |
|---|---|
| `builtin/` | `BuiltInBubbleProviders` (211), `BuiltInProviderSupport` (23), `VoiceSupport` (105) |
| `plugins/` | `WebResearchAdapter` (30), `AutoDroidAdapter` (57), `VoiceInterfaceAdapter` (60), `VoiceTextPolicy` (17), `ScreenIntelligenceAdapter` (54), `ScreenIntelligenceImagePolicy` (20), `BuiltInCatalogue` (30) |
| `ai/` | `BraveSearchProvider` (67), `AiVisionProvider` (6), `OpenAiCompatibleVisionProvider` (103), and `WebSearchProvider` (7) and `WebSearchPolicy` (36), which only the capability code and `WebSearchPolicyTest` use |
| `bubble/` | `ScreenCaptureConsentActivity` (62), `ScreenProjectionCaptureService` (175), `ScreenRegionSelectionActivity` (191), `VoiceCaptureActivity` (74), `ScreenCaptureArtifactStore` (51), `ScreenCaptureArtifactPolicy` (20), `ScreenRegionPolicy` (48) |
| Manifest | `RECORD_AUDIO`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, three activities, `ScreenProjectionCaptureService`, `<queries>` for `com.aditsyal.autodroid` |

## 4. Tests

- **Move with the code (pure capability):** `BuiltInProviderSupportTest`, `VoiceInterfaceAdapterTest`, `VoiceTextPolicyTest`, `ScreenIntelligenceImagePolicyTest`, `AutoDroidAdapterTest`, `ScreenCaptureArtifactPolicyTest`, `ScreenRegionPolicyTest`, `WebSearchPolicyTest`, `BuiltInCatalogueTest`.
- **Must be rewritten, not moved (Core tests that read the catalogue):** `BubbleUiPolicyTest` (7 uses of `BuiltInCatalogue.bubbleBindings`), `CoreReservedNamesTest` (asserts equality with `BuiltInCatalogue`, which would make Core depend on the moved code; it needs literal expected ids), `AppControlReferencePluginContractTest` and `DeviceControlReferencePluginContractTest` (assert the catalogue does not contain their ids; the Device one also calls `BuiltInBubbleProviders.bindings`).
- **Line check only:** `BubbleContractTest` (2 mentions, in a forbidden-names regex), `CoreShellProductionBoundaryTest` (1 mention).
- `app/src/androidTest` has no capability references.

## 5. Build and CI facts

- `app/build.gradle.kts` depends only on `:one-ai-plugin-sdk`. There is no `:core` library to compile against.
- `validate.yml` runs `testDebugUnitTest lintDebug :app:compileDebugAndroidTestKotlin` plus the reference-plugin modules. Any structure that renames or adds `:app` variants (for example product flavors) changes these task names and needs a workflow edit. `android.yml` is manual (`assembleDebug`) and must stay manual.

## 6. Ways to do slice 2 (none started)

- **E1, source-set flavor inside `:app`.** Put the capability sources, manifest fragment and tests in a `capabilities` product flavor; the default Core flavor compiles and ships none of it. Compile-checked, reversible, no new Core API. Cost: variant names change (`testCoreDebugUnitTest`, etc.), so `validate.yml` and the tracker's task names change, and `assembleDebug` would build both flavors unless restricted.
- **E2, extract a `:core-api` library first.** Move the Core types the capability code needs into a library, then create the capability module on top. Matches D-M literally and gives the SDK a cleaner boundary, but it is a large refactor of Core, against the rule to avoid broad rewrites, and needs its own design and approval.
- **E3, move the sources out of every build (kept in git, not compiled).** Smallest and safest change to Core; the manifest and Core tests become clean immediately. Cost: the moved code is no longer compile-checked and will drift. Easily revived by moving files back.
- **E4, delete.** Rejected by D8 (Option C not chosen).

## 7. Recommendation and decision needed

**D-P (new):** choose E1, E2 or E3 for slice 2. *Recommended: E1*, because it keeps the code compile-checked and reversible and keeps the Core APK free of the permissions and components, without a Core refactor. E3 is the lowest-risk fallback if the owner prefers the smallest change. Whichever is chosen, slice 2 would also: set the `PluginManager` default to `emptyList()`, rewrite the four Core tests in section 4, and add a manifest test that the Core manifest declares no capability permissions or components (this is slice 4's guard, so it can land with slice 2).

No APK build, merge or release is involved.
