# Core capability-code dependency audit

- **Date:** 2026-11-08. **Basis:** source head `3bcb0b3` on `foundation/validation-core-shell-startup-isolation` (CI #632 passed on the last source commit `21d649b`).
- **Type:** read-only audit. No source, manifest or test was changed. Docs only.
- **Evidence level:** source inspection with grep over `app/src/main` and `app/src/test`. Nothing was run on an emulator or device, so "unreachable" below means "no production call path in source", not "observed not to run".
- **Why:** `docs/PROJECT_CONTINUATION_RULES.md` and the tracker require a focused dependency audit before any isolation or removal of capability-specific code. No capability plugin is a current deliverable (decision D4).

## 1. Summary

1. The capability code (Web Research, Screen Intelligence, Voice, AutoDroid) is **not reachable from production startup or from any production screen**. Its only entry point is `BuiltInBubbleProviders`, which no production file calls (`CoreShellProductionBoundaryTest` guards that `AppServices` never calls `registerAll`).
2. It is still **shipped inside the Core APK** and still has **user-visible footprints**: two sensitive manifest declarations, three activities and one foreground service in the manifest, a Brave Search credential section in Settings, and a `<queries>` entry for the AutoDroid package.
3. `BuiltInCatalogue` is a default argument only. Production passes an empty list (`AppServices`), and the reserved legacy ids already live in `CoreReservedNames`, independent of the catalogue.
4. The generic Bubble shell, plugin runtime, command framework and AI engine have **no dependency on the capability code** in the direction that matters: the capability code depends on them, not the reverse. The exceptions are small and listed in section 4.

## 2. Inventory and evidence

| Item | Location | Referenced by (production source) | Reachable in production? |
|---|---|---|---|
| `BuiltInBubbleProviders` (+ 4 providers: Web Research, Screen Intelligence, Voice, AutoDroid) | `builtin/BuiltInBubbleProviders.kt` (211 lines) | nothing outside its own file | No. Boundary test forbids `registerAll` in `AppServices`. |
| `BuiltInProviderSupport` | `builtin/` (23) | the providers above | No |
| `VoiceSupport` (`VoiceCaptureBridge`, `VoiceSpeaker`) | `builtin/` (105) | `VoiceBubbleProvider`, `VoiceCaptureActivity` | No |
| `WebResearchAdapter`, `AutoDroidAdapter`, `VoiceInterfaceAdapter`, `VoiceTextPolicy`, `ScreenIntelligenceAdapter`, `ScreenIntelligenceImagePolicy` | `plugins/` (about 240 lines) | the built-in providers only | No |
| `BraveSearchProvider` | `ai/` | `WebResearchBubbleProvider` only | No |
| `AiVisionProvider`, `OpenAiCompatibleVisionProvider` | `ai/` | `ScreenIntelligenceAdapter`, the built-in providers | No |
| `ScreenCaptureConsentActivity`, `ScreenProjectionCaptureService`, `ScreenRegionSelectionActivity`, `ScreenCaptureArtifactStore/Policy`, `ScreenRegionPolicy` | `bubble/` (about 550) | launched only from `ScreenIntelligenceBubbleProvider` (`ScreenCaptureConsentActivity.launch`) and each other | No |
| `VoiceCaptureActivity` | `bubble/` (74) | launched only from `VoiceCaptureBridge`, itself used only by `VoiceBubbleProvider` | No |
| `BuiltInCatalogue` (5 templates incl. 3 capability templates) | `plugins/` (30) | `PluginManager` default argument only | Default never used: `AppServices` passes `emptyList()` |

Neighbouring Gradle modules: `:reference-plugins:app-control` and `:reference-plugins:device-control` depend only on `:one-ai-plugin-sdk`; `:app` does not depend on them. They are separate APK modules built by CI. Not examined further (see section 6).

## 3. User-visible footprint in the Core APK

| Footprint | Where | Used by Core? |
|---|---|---|
| `RECORD_AUDIO` permission | manifest line 15 | Only `VoiceCaptureActivity` (unreachable). No Core feature records audio. |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission and a `mediaProjection` foreground service | manifest lines 19, 37-40 | Only `ScreenProjectionCaptureService` (unreachable). |
| Three exported=false activities (`ScreenCaptureConsentActivity`, `VoiceCaptureActivity`, `ScreenRegionSelectionActivity`) | manifest lines 28-30 | Unreachable |
| `<queries>` for `com.aditsyal.autodroid` | manifest line 4 | Only `AutoDroidAdapter` (unreachable) |
| "Web Research" Settings row, Brave Search key field, save/remove buttons and dialog | `MainActivity.kt` about lines 617-707 | **Reachable UI.** A saved key is stored encrypted but has no production consumer: the only reader is `BraveSearchProvider`, used only by the unreachable provider. The row can mislead a user into thinking a search feature exists. |
| AI provider help text says "Screen Intelligence additionally requires a vision-capable model" | `MainActivity.kt` about line 666 | Reachable text about an absent feature |

Core permissions that are legitimately Core and must stay: `INTERNET` (AI provider), `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` and the `FloatingBubbleService` declaration (Bubble), `POST_NOTIFICATIONS` (Bubble foreground notification).

## 4. Where Core touches capability-flavoured code (the real coupling)

- `FloatingBubbleService` uses `BubbleResultHandoff` (title, content, optional `imagePath`). It is generic (a plugin may show a result), but `imagePath` and the `ScreenCaptureArtifact*` helpers were designed for screen capture. Whether `imagePath` stays in the generic Bubble contract is an SDK question, not a deletion question.
- `AppServices` and `PluginHostComposition` take a catalogue parameter; production composition is empty.
- The `ai/` package holds `AiVisionProvider` and `OpenAiCompatibleVisionProvider`. They are provider-neutral contracts for image understanding but have no Core consumer.
- Nothing in `core/`, `security/`, `commands/`, `bubble/BubbleContract`, `bubble/BubbleUiPolicy` or `bubble/ExternalBubbleProvider` references the capability classes.

## 5. Tests that depend on the capability code

Deleting or moving code needs these moved or rewritten with it: `BuiltInProviderSupportTest`, `BuiltInCatalogueTest`, `VoiceInterfaceAdapterTest`, `VoiceTextPolicyTest`, `ScreenIntelligenceImagePolicyTest`, `AutoDroidAdapterTest`, `ScreenCaptureArtifactPolicyTest`, `ScreenRegionPolicyTest`, `AppControlReferencePluginContractTest`, `DeviceControlReferencePluginContractTest`. These also mention capability names and need a line-level check: `BubbleContractTest`, `BubbleUiPolicyTest`, `CoreShellProductionBoundaryTest`, `CoreReservedNamesTest`. `PluginManagerTest` and others construct `PluginManager` with an explicit catalogue and do not need `BuiltInCatalogue`.

## 6. Not examined

- How the two `AppControl`/`DeviceControl` contract tests reach their reference modules, and whether the CI workflow builds those modules.
- Whether `docs/` and `README.md` describe these capabilities as shipped (a documentation pass is needed after any change).
- Runtime behaviour. No claim is made that the code never runs on a device.

## 7. Options (no change has been made)

- **Option A, leave as is.** Zero risk. Keeps `RECORD_AUDIO`, media-projection and the dead Brave Search row in the Core APK, which does not match "Core works independently of capability plugins".
- **Option B, isolate, recommended.** Move the capability code, its manifest entries and its tests into a new Gradle module (for example `:legacy-capabilities`) that `:app` does not depend on and that is not part of the Core APK. Nothing is deleted and git history is kept; the move is reversible. Do it in slices, each with its own CI run:
  1. Settings: remove the Web Research credential section and the Screen Intelligence help sentence (the only reachable capability UI). Stored keys are left alone, not wiped.
  2. Manifest and `bubble/` capability activities and service, `builtin/`, the `plugins/` adapters, `BraveSearchProvider` and the vision classes, with their tests, into the new module. Remove the capability permissions and `<queries>` from the Core manifest.
  3. `BuiltInCatalogue` and the `PluginManager` default argument: default to an empty list and move the catalogue with the capability module. `CoreReservedNames` stays in Core.
  4. Update the boundary test to forbid capability permissions and components in the Core manifest.
- **Option C, delete.** Smallest APK and repository, but irreversible in the working tree and discards code the owner may want for future separate plugin projects. Not recommended without a separate decision.

## 8. Decisions needed from the owner

- **D-M:** choose A, B or C. *Recommended: B.*
- **D-N (only for B):** may slice 1 remove the Web Research credential UI from Settings? A key a user already saved stays encrypted in the vault and is not deleted. *Recommended: yes.*
- **D-O (only for B):** keep `imagePath` in the generic Bubble result handoff for now and decide it with the SDK review. *Recommended: yes.*

Nothing in this audit authorises an APK build, a merge or a release.
