# ONE AI — Decision Record

## D1 — Plugin extension model (Stage 8): separate signed plugin APKs
- **Date:** 2026-11-03. **Decided by:** project owner (selected option 1 of three).
- **Decision:** Independently developed plugins ship as separate, signed Android APKs that the core discovers and talks to through a documented, versioned contract (exported service / intent interface, bound-service or content-provider IPC).
- **Rejected:** loading arbitrary dex/class code from a plugin folder (unsafe on Android); compile-time-only modules as the sole model (not independently installable).
- **Required design work (not yet done):** discovery mechanism; caller authentication (signature / package verification); permission and capability mapping onto the core approval gateway; Bubble contribution protocol; manifest additions including core-compatibility; failure isolation; SDK documentation and test kit.
- **Consequence:** built-in capability code (Web Research, Voice, Screen Intelligence, App Control, Device Control, AutoDroid) must be extracted out of the core. Order and any interim in-repo reference modules to be approved in the correction plan.

## D2 — Command Manager placement
- **Date:** 2026-11-04. **Decided by:** owner (Master Development Instructions §5). Command Manager is a **Home module**, not a sixth navigation destination.

## D3 — Autonomy
- **Date:** 2026-11-04. Owner instructs the stages to proceed in order without routine approval prompts. Still requiring explicit authorisation: PR merge, APK build, any change to the architecture or the 12 stages. One concise question only for genuinely ambiguous architecture.

## Open decisions
- **O3 — Stage 8 design defaults** (`docs/STAGE_8_DESIGN.md` §12): trust-on-first-use with fingerprint confirmation; declarative plugin UI only; minSdk 26. Proceeding with these unless the owner objects.
- **O2 — Command card subtitle:** keep "Your AI command interface is being prepared." or retire it now the AI interface works? *(low impact; currently kept verbatim because the owner listed it as approved Home text. It reads inaccurately now that the interface works; one-line change to remove)*
- **O4 — RESOLVED 2026-11-04 by owner: option 1 (generic host-launched screen via PendingIntent). Implementation in progress (Stage 9.4a contract done, 9.4b host logic and 9.4c Android/Bubble pending); behaviour unverified on any device.** Original question: How can a separate plugin APK open a screen? (blocks extraction of App Control, Device Control, AutoDroid, Voice, Screen Intelligence)** Raised 2026-11-04 while starting the App Control extraction. Built-in capabilities start activities from inside ONE AI (overlay permission, visible Bubble). A plugin runs in its own process with no visible window, so Android's background-activity-start rules (Android 10+, stricter from 14/15) are expected to block it from launching another app or its own consent/permission screen. **Unverified on any device.** Options: (1) add a generic, capability-neutral contract primitive: the plugin hands the host a `PendingIntent` for a screen, the host asks the user, then sends it with background-start allowed (needs an AIDL change, owner approval and emulator proof; API 34+ opt-in); (2) plugins request overlay permission themselves (no contract change; fragile on newer Android); (3) keep launcher-type capabilities in the core (changes D1's consequence; owner decision); (4) extract capabilities that need no screen first (for example Web Research, whose key lives in the plugin's own settings screen opened by the user) and defer the rest. No code written for this yet; the built-in App Control stays until a replacement is verified on a device.

