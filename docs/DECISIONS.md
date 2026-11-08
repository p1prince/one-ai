# ONE AI — Decision Record

## D1 — Plugin extension model (Stage 8): separate signed plugin APKs
- **Date:** 2026-11-03. **Decided by:** project owner (selected option 1 of three).
- **Decision:** Independently developed plugins ship as separate, signed Android APKs that the core discovers and talks to through a documented, versioned contract (exported service / intent interface, bound-service or content-provider IPC).
- **Rejected:** loading arbitrary dex/class code from a plugin folder (unsafe on Android); compile-time-only modules as the sole model (not independently installable).
- **Required design work (not yet done):** discovery mechanism; caller authentication (signature / package verification); permission and capability mapping onto the core approval gateway; Bubble contribution protocol; manifest additions including core-compatibility; failure isolation; SDK documentation and test kit.
- **Consequence:** ONE AI Core provides generic extension infrastructure. Individual capability implementations are future plugin projects, not current foundation deliverables.

## D2 — Command Manager placement
- **Date:** 2026-11-04. **Decided by:** owner (Master Development Instructions §5). Command Manager is a **Home module**, not a sixth navigation destination.

## D3 — Autonomy
- **Date:** 2026-11-04. Owner instructs the stages to proceed in order without routine approval prompts. Still requiring explicit authorisation: PR merge, APK build, any change to the architecture or the 12 stages. One concise question only for genuinely ambiguous architecture.

## D4 — Generic plugin ecosystem; no required reference capability modules (supersedes prior D4 wording)
- **Date:** 2026-11-07. **Decided by:** project owner; explicit approval of corrected roadmap.
- **Decision:** The foundation deliverable is ONE AI's generic plugin platform: stable, versioned SDK/contracts, secure Android extension boundary, generic Plugin Centre, lifecycle, trust, compatibility, permissions, configuration, updates, removal, and generic Floating Bubble integration.
- **No capability-plugin deliverables:** Browser/Web Research, Voice, Screen Intelligence, App Control, Device Control, AutoDroid, automation, and other named capabilities are illustrative future plugin ideas only. They are not required reference modules, current milestones, acceptance criteria, or dependencies for the foundation release.
- **External extension boundary:** independently developed plugins may be separately installed, signed Android APKs using the secure documented bound-service/AIDL contract, explicit user trust, signer verification, compatibility checks, host-controlled permissions/approvals, declarative Bubble contributions, bounded requests/results, and failure isolation. Arbitrary dex/class loading from plugin folders remains rejected.
- **Consequence:** Do not extract, package, build, or integrate individual capability plugins unless the owner explicitly starts a separate plugin project. Generic infrastructure must be complete without sample capability modules. Existing sample/test fixtures may remain only where needed to test the generic contract and must not become product deliverables.

## D5 — Generic per-plugin configuration contract (Stage 9)
- **Date:** 2026-11-07. **Decided by:** project owner ("I approve all"), approving the recommendations in `docs/STAGE_9_CONFIG_DESIGN.md` §11.
- **D-A:** secret/credential settings are excluded from v1. **D-B:** the host pushes the validated snapshot to the plugin on bind and on change. **D-C:** a plugin with unmet required settings cannot be enabled. **D-D:** the plugin API level is raised to 3 for `applyConfig`.
- **Consequence:** implement in slices 9.6a–9.6e. Capability-specific settings never enter Core; configuration never grants capabilities or Bubble access.

### D5 addendum — required settings also gate execution (2026-11-07)
- **Decided by:** project owner ("use recommendation") when asked during Stage 9.6d.
- **Decision:** a plugin that is already enabled but has a required setting with no value (for example after an update added one) stays enabled but is not run and serves no Bubble view until the setting is saved. Enabling remains blocked (D-C). The plugin card shows "Needs configuration" and names the settings; nothing is auto-disabled and Bubble access is untouched.

## D6 — Generic plugin catalogue / repository source (Stage 9)
- **Date:** 2026-11-08. **Decided by:** project owner ("Use recommendations"), approving the recommendations in `docs/STAGE_9_CATALOGUE_DESIGN.md` §12.
- **D-E:** no preconfigured catalogue source; the user adds HTTPS source URLs. **D-F:** ONE AI never downloads or installs APKs; an entry's link opens in the browser/store and the platform installs. **D-G:** an installed certificate that is not among an entry's listed signers blocks importing *from the catalogue view* (the on-device import path is unchanged) and catalogue documents are unsigned in v1. **D-H:** the legacy reserved plugin IDs and Bubble service names stay reserved, held in an explicit `CoreReservedNames` that does not depend on `BuiltInCatalogue`.
- **Consequence:** implement in slices 9.7a–9.7e. A catalogue is a listing, never authority: it cannot install, trust, enable or grant Bubble access. No capability plugin or default catalogue content is created.

## D7 - Stage 10 security, Activity and credential-status design
- **Date:** 2026-11-08. **Decided by:** project owner ("Use recommendations"), approving the recommendations in `docs/STAGE_10_DESIGN.md` section 8.
- **D-I:** the Settings Security card becomes a read-only Access overview (one row per trusted plugin: Enabled, Bubble access, capabilities that always ask first, short signer fingerprint). There is no "revoke trust but keep the plugin" action and no permission toggles, because ONE AI keeps no standing grants. **D-J:** Activity entries gain additive optional `kind` and `plugin` fields, old entries are not migrated, and the Activity screen gets display-only filter chips. **D-K:** only the gateway, adapter, registrar and AI call sites move to typed entries in this stage; the rest stay `system`. **D-L:** an unreadable saved credential is told apart from "not saved" with the wording in the design note.
- **Consequence:** implement as slices 10.1 to 10.4. Nothing here changes trust, enablement, Bubble access or approval behaviour, and no capability plugin is involved.

## D8 - Core capability-code isolation (Option B)
- **Date:** 2026-11-08. **Decided by:** project owner ("Use recommendations"), approving the recommendations in `docs/CORE_CAPABILITY_AUDIT.md` section 8.
- **D-M:** isolate (Option B), do not delete: move the unreachable capability code, its manifest entries and its tests into a Gradle module that `:app` does not depend on, in four CI-validated slices (Settings cleanup; manifest and capability classes; `BuiltInCatalogue` and the `PluginManager` default; boundary test). **D-N:** slice 1 may remove the Web Research credential section from Settings; an already-saved key stays encrypted in the vault and is not wiped. **D-O:** `imagePath` stays in the generic Bubble result handoff and is decided during the SDK review.
- **Consequence:** nothing is deleted. No capability plugin becomes a deliverable (D4 stands). Each slice is one commit plus a tracker record with its CI run, and the boundary test must prove the Core manifest declares no capability permissions or components before the work is called done. Scheduled after Stage 10 slice 10.3.

## D9 - Capability-code isolation mechanism (E1, product flavor)
- **Date:** 2026-11-08. **Decided by:** project owner ("Go with recommended"), approving E1 in `docs/D8_SLICE2_SCOPING.md` section 7.
- **D-P:** D8 slice 2 is done with a `capabilities` product flavor inside `:app` (flavor dimension `edition`: `core` and `capabilities`). The unreachable capability sources, their manifest entries and their tests live only in the `capabilities` flavor; the `core` flavor, which is the Core app, compiles and ships none of them. This replaces D-M's "separate Gradle module" wording, which cannot compile because the capability code uses Core types inside `:app`.
- **Consequence:** nothing is deleted; files are moved with `git mv`. `PluginTemplate` stays in Core. `PluginManager`'s default catalogue becomes empty. Gradle task names for `:app` change (for example `testCoreDebugUnitTest`), so `validate.yml` and `android.yml` change; `android.yml` stays manual. No capability plugin becomes a deliverable (D4 stands).

## D10 - SDK review: capability vocabulary, microphone wording, imagePath
- **Date:** 2026-11-08. **Decided by:** project owner ("Use recommendations"), approving the recommendations in `docs/SDK_REVIEW_SCOPING.md` section 5.
- **D-Q:** the capability vocabulary stays closed and versioned, with one source of truth (`CapabilityVocabulary`); a new identifier is a Core release change noted against the plugin API level. **D-R:** remove the Core microphone branch from `PluginActionButton` (it asks for `RECORD_AUDIO` for the Core app, which no longer declares it); the approval flow is unchanged and nothing is deleted. **D-S:** keep `imagePath` in the generic Bubble result handoff (inert in Core: service not exported, sole sender in the `capabilities` flavor, SDK plugins cannot produce images); this closes D-O.
- **Consequence:** implement as slices S-A (vocabulary, with the docs fix), S-B (microphone branch) and S-C (stale built-in-adapter passages marked historical). Nothing here changes trust, enablement, Bubble access or approval behaviour, and no capability plugin is involved (D4 stands).

## D11 - Bubble interaction: in-progress status and argument entry
- **Date:** 2026-11-08. **Decided by:** project owner ("Use recommendations"), approving the recommendations in `docs/BUBBLE_INTERACTION_SCOPING.md` section 5.
- **D-T:** progress P1: a host-drawn "Running <command title>..." panel with Cancel, used by every Bubble execution path that hides the panel today (a plugin view button, "Allow once", and the Command Manager "Approve and run"). It shows the command title only, never plugin progress text; Cancel cancels the call; hiding the panel does not cancel. Live relay of plugin progress lines (P2) is not done. **D-U:** arguments A1: no manifest or plugin API change; a command that needs input is exposed through the plugin's declarative Bubble view, and the Command Manager runs a command as declared. A manifest argument schema (A2, API level 4) is not done.
- **Consequence:** implement as slices B1 (pure run-state helper), B2 (wire it into the service) and B3 (docs and emulator-plan rows). Nothing here changes trust, enablement, Bubble access or approval behaviour; a cancelled run has still consumed its one-use approval. No capability plugin is involved (D4 stands).

## D12 - In-app plugin use
- **Date:** 2026-11-08. **Decided by:** project owner ("Use recommendations"), approving the recommendations in `docs/IN_APP_PLUGIN_USE_SCOPING.md` section 6.
- **D-AA:** E1 plus E2: an in-app plugin screen opened from an **Open** button on the Plugins card, and a Home strip listing enabled plugins that have a screen. **D-AB:** a plugin-requested screen (API 2) started from the app gets an in-app presenter instead of being queued for the Bubble. **D-AC:** extension commands stay out of the in-app Command Manager until commands can declare arguments. **D-AD:** in-app use depends on Enabled (and trusted, compatible, configured) only; "Show in Floating Bubble" stays Bubble-only and the in-app screen never reads it.
- **Consequence:** implement as slices C1 (pure call-flow state machine), C2 (in-app plugin screen), C3 (Open button and Home strip), C4 (in-app launch presenter), C5 (docs, emulator rows and source guards). The Bubble's working code is not refactored. No SDK or plugin API change, and nothing here changes trust, enablement, Bubble access or approval policy. No capability plugin is involved (D4 stands).

## D13 - AI provider profiles (slice S1)
- **Date:** 2026-11-08. **Decided by:** project owner ("Ai provider profiles"), taken as approval of **D-V option V1** in `docs/AI_PROVIDER_AND_TOOLS_SCOPING.md` section 6: a saved list of provider profiles with an adapter registry, one profile active.
- **Scope of this approval:** slice S1 only. It does **not** approve V2 (plugin-supplied providers), command arguments (D-W, plugin API level 4), AI-to-plugin tool use (D-X), the "AI may use this plugin" setting (D-Y) or per-call approval of AI-started calls (D-Z). Those stay open and S2 to S5 do not start. V3 (driving a logged-in chat website) stays rejected.
- **Consequence:** the existing endpoint, model and name migrate into a `default` profile that keeps using the primary key, so nothing is re-encrypted; added profiles get their own named vault secret; `kind` picks an adapter from a registry (`openai_compatible` is the only one registered); the active profile is looked up on every request. No SDK, manifest or plugin API change, and nothing here changes trust, enablement, Bubble access or approval policy. No capability plugin is involved (D4 stands).

## D14 - Stage 11 evidence route: third test APK
- **Date:** 2026-11-08. **Decided by:** project owner, who chose "Third test APK" when asked which evidence route to use for Stage 11 (option: a third manual build, checked on the tablet; the alternative, CI-only acceptance, was not chosen).
- **Scope of this authorisation:** one manual run of `android.yml` (Core debug APK plus the Echo sample plugin APK) on the validation branch. It does **not** authorise a merge, a release, a release-signed build, a PR update, a change to `foundation/batch-03-ai-engine`, or any further build.
- **Consequence:** the owner runs the manual rows listed in `docs/STAGE_11_ACCEPTANCE_MATRIX.md` on the tablet and reports results; Stage 11 stays pending until they are recorded. Rows that need test variants or an HTTPS server cannot be run with this build.

## D15 - Style conformance: leave the current styling as it is
- **Date:** 2026-11-08. **Decided by:** project owner ("I'll leave it like that and deal with other things"), in answer to the options in `docs/STYLE_CONFORMANCE_REVIEW.md` section 4.
- **Decision:** no style correction slice. The card corner radius (16dp) and the inconsistent section-label letter-spacing stay as they are, and the unrecorded values (page spacing, body sizes, error red, warning amber, chip shape) are accepted as the current standard. This is closer to option O-3 (judge on the tablet) than to O-1; the owner has not asked for the two deviations to be fixed.
- **Consequence:** Stage 1 item "Plugins, Develop, Activity and Settings conformance" is closed as decided by the owner, not as verified against the original design. The Bubble visual design remains unrecovered. Reopen only if the owner reports something on the device.

## Open decisions
- **O3 — Stage 8 design defaults** (`docs/STAGE_8_DESIGN.md` §12): trust-on-first-use with fingerprint confirmation; declarative plugin UI only; minSdk 26. Proceeding with these unless the owner objects.
- **O2 — RESOLVED 2026-11-07: Command card subtitle retired.** The Command ONE card now reads "Ask ONE AI to help with your next task." (verified in `MainActivity.kt` 2026-11-08). Original question:  keep "Your AI command interface is being prepared." or retire it now the AI interface works? *(low impact; currently kept verbatim because the owner listed it as approved Home text. It reads inaccurately now that the interface works; one-line change to remove)*
- **O4 — RESOLVED 2026-11-04: generic host-launched plugin screens via PendingIntent.** The API 2 contract, identity/approval broker and Bubble confirmation/send path are implemented in source; runtime success/failure remains a Stage 11 acceptance item. This is a capability-neutral SDK primitive. Any former references to it as a blocker for extracting individual example capabilities are historical and superseded by the 2026-11-07 owner-approved scope correction. No individual capability extraction is authorized or required.

