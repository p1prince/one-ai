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

## Open decisions
- **O3 — Stage 8 design defaults** (`docs/STAGE_8_DESIGN.md` §12): trust-on-first-use with fingerprint confirmation; declarative plugin UI only; minSdk 26. Proceeding with these unless the owner objects.
- **O2 — Command card subtitle:** keep "Your AI command interface is being prepared." or retire it now the AI interface works? *(low impact; currently kept verbatim because the owner listed it as approved Home text. It reads inaccurately now that the interface works; one-line change to remove)*
- **O4 — RESOLVED 2026-11-04: generic host-launched plugin screens via PendingIntent.** The API 2 contract, identity/approval broker and Bubble confirmation/send path are implemented in source; runtime success/failure remains a Stage 11 acceptance item. This is a capability-neutral SDK primitive. Any former references to it as a blocker for extracting individual example capabilities are historical and superseded by the 2026-11-07 owner-approved scope correction. No individual capability extraction is authorized or required.

