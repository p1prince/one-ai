# SDK review scoping - capability vocabulary, microphone wording, imagePath

- **Date:** 2026-11-08. **Method:** read-only source inspection of exact head `d8bdf2f6507e0359e424bc686ffd21f2ed4821eb` (Android Validation #645 passed on it). No source, manifest, build file or test was changed.
- **Evidence level:** source inspection only; nothing was run on an emulator or device.
- **Scope:** three open Stage 1-10 items listed in the tracker (2026-11-08 continuation state): the `CapabilityPolicy` vocabulary, the `PluginActionButton` microphone wording, and D-O (`imagePath`). All three are generic Core/SDK questions. No capability plugin is involved (D4 stands).

## 1. Capability vocabulary

**Facts.**
- The vocabulary is a fixed set of ten identifiers: `commands`, `internet`, `screen_capture`, `microphone`, `audio`, `device_actions`, `accessibility`, `automation`, `files`, `app_launch`.
- It is written out in two places: `PluginManifestValidator.supportedCapabilities` (`plugins/PluginManifest.kt`; used by `ExternalPluginManifest` validation and by `CommandFramework`) and `CapabilityPolicy.known` (`security/ActionSecurity.kt`). A third list, `approvalRequired`, is a subset kept beside it. Nothing forces the copies to stay equal.
- Rejection is enforced twice: an external manifest that declares an identifier outside the set is invalid, and the gateway answers "Capability is not recognized" for any request outside `known`.
- Consequence: a plugin cannot introduce a new capability identifier without a Core release. The documented approval rule ("every capability except `commands` and `audio`") matches the code.
- `PLUGIN_SDK.md` lists `device_actions` twice in "Supported capability identifiers" and still describes built-in adapters as current behaviour (superseded by D4); this is documentation drift, not a code defect.

**Assessment.** The closed set is a defensible security choice: the host must know what approval and what Android permission each capability implies, and an unknown capability cannot be risk-classified. It does not conflict with "no Core edits per plugin" if the set is documented as a deliberate, versioned list of capability *classes* (not plugins). The real weaknesses are the duplicated lists and the stale documentation.

**Options.**
- **V1 (recommended):** keep the vocabulary closed; make it one source of truth (`CapabilityVocabulary`) from which `supportedCapabilities`, `known` and `approvalRequired` derive; add a test that the approval set is a subset and that `PLUGIN_SDK.md` and the developer guide list exactly the same identifiers; document the set as closed and extended only by a Core release with a plugin API level note.
- **V2:** open vocabulary where an unknown identifier is accepted and always requires approval. More flexible, but changes the security model, the Details/Access wording and the import review, and needs its own design note.

## 2. `PluginActionButton` microphone wording

**Facts.**
- `security/PluginActionButton.kt` has no call site anywhere in `app/src` (main, capabilities or tests).
- Its `microphone` branch asks Android for `RECORD_AUDIO` for the **ONE AI app itself** after approval. Since D8 slice 2 (`f4477a8`) the Core manifest no longer declares `RECORD_AUDIO`, so that request can never be granted in the Core app.
- Conceptually the branch is also wrong for the SDK: an external plugin is a separate signed APK and holds its own Android permission; the host asking for its own microphone permission grants the plugin nothing. `CapabilityPolicy.androidPermissionGuidance("microphone")` is developer guidance text and is not affected.
- Result: no reachable behaviour is wrong today, but the code and its header comment promise something the Core app cannot do.

**Options.**
- **M1 (recommended):** remove the microphone branch and its launcher and state from `PluginActionButton`, keep the approval flow unchanged, update the header comment, and add a source guard that Core does not request `RECORD_AUDIO`. Nothing is deleted otherwise.
- **M2:** leave as is and document it. Leaves a misleading dead path.
- **M3:** delete `PluginActionButton` (unused). Larger than needed and not approved; the composable may be wanted by a future generic action UI.

## 3. D-O: `imagePath` in the Bubble result handoff

**Facts.**
- Core `FloatingBubbleService` accepts `ACTION_SHOW_RESULT` with title, content and an optional image path, and offers a provider `followUp(imagePath, host)` (documented in `PLUGIN_SDK.md`).
- The service is `android:exported="false"`, so only the app's own components can send the intent. In production the only sender is `ScreenCaptureConsentActivity`, which lives in the `capabilities` flavor and is not in the Core app. The image path is confined to `cacheDir/one-ai-selected`, a directory name used only by capability code.
- External (SDK) plugins cannot produce an image result: their Bubble content is a declarative JSON view and the AIDL contract carries no image.
- So `imagePath` is inert in the Core app and unreachable from the SDK.

**Options.**
- **I1 (recommended):** keep it. It is inert, not exported, path-confined and tested. Removing it would break the preserved capability code for no Core benefit. Re-decide only if the SDK ever adds an image result.
- **I2:** move the image handling into an extension seam supplied by the capabilities flavor. Cleaner Core, but needs a new seam and touches the Bubble shell for no reachable gain.

## 4. Proposed slices (if the owner approves)

1. **S-A (V1):** `CapabilityVocabulary` as the single source; derived sets; consistency tests; fix the duplicate `device_actions` line and add a "closed, versioned vocabulary" paragraph to `PLUGIN_SDK.md` and the developer guide. Behaviour unchanged.
2. **S-B (M1):** remove the Core microphone branch; add the `RECORD_AUDIO` source guard.
3. **S-C (docs):** mark the stale "built-in adapter" passages in `PLUGIN_SDK.md` as historical, in line with D4.

Each slice is one commit plus a tracker record with its CI run. No slice changes trust, enablement, Bubble access or approval behaviour.

## 5. Owner decisions needed

- **D-Q:** vocabulary V1 (recommended) or V2.
- **D-R:** microphone M1 (recommended), M2 or M3.
- **D-S:** imagePath I1 (recommended) or I2; this closes D-O.

## 6. Not verified

No runtime behaviour. `AccessOverviewTest` lists the ten identifiers (line 66) and was not changed or run here; the existing tests that read the vocabulary will need to follow S-A. Whether any external plugin author already depends on a specific identifier is unknown.
