# ONE AI — Decision Record

## D1 — Plugin extension model (Stage 8): separate signed plugin APKs
- **Date:** 2026-11-03. **Decided by:** project owner (selected option 1 of three).
- **Decision:** Independently developed plugins ship as separate, signed Android APKs that the core discovers and talks to through a documented, versioned contract (exported service / intent interface, bound-service or content-provider IPC).
- **Rejected:** loading arbitrary dex/class code from a plugin folder (unsafe on Android); compile-time-only modules as the sole model (not independently installable).
- **Required design work (not yet done):** discovery mechanism; caller authentication (signature / package verification); permission and capability mapping onto the core approval gateway; Bubble contribution protocol; manifest additions including core-compatibility; failure isolation; SDK documentation and test kit.
- **Consequence:** built-in capability code (Web Research, Voice, Screen Intelligence, App Control, Device Control, AutoDroid) must be extracted out of the core. Order and any interim in-repo reference modules to be approved in the correction plan.

## Open decisions
- **O1 — "Commands" tab:** the plan fixes five sections; the app has six. Place command management under Develop or Settings? *(awaiting owner)*
