# ONE AI — Development Tracker

Source of truth: *ONE AI Official Open-Shell Development Plan* (revised 3 November 2026). The 12-stage structure is fixed. The previous tracker is archived at `docs/LEGACY_TRACKER.md` and is **not** evidence of completion.

- Repository: `p1prince/one-ai` · Branch: `foundation/batch-03-ai-engine` · PR #1: **draft, unmerged** (do not merge without explicit authorisation).
- APK build/release: **blocked** until explicitly authorised.
- Evidence key: **CI** = compile + JVM unit tests only. **Emulator** / **Device** = not yet run for anything.

## Stage status

| # | Stage | Status | Notes |
|---:|---|---|---|
| 1 | Design recovery and requirements freeze | 🟡 Partial | Decisions recovered; exact mockup/dimensions/typography/colour codes unrecovered. Do not invent. |
| 2 | Existing repository audit | 🔵 In progress | Draft audit and correction plan in `docs/STAGE_2_AUDIT.md`; awaiting owner approval. Exit needs file-by-file audit, dependency map, approved plan. |
| 3 | Core application architecture | ⚪ Not started (code exists, unverified) | Core→plugin coupling to fix (F11). |
| 4 | Main interface and navigation | ⚪ Not started (code exists, unverified) | Six sections vs approved five (F1); decision O1 open. |
| 5 | AI engine and orchestration | ⚪ Not started (code exists, unverified) | Provider-neutral engine appears reusable. |
| 6 | Core command framework | ⚪ Not started | Existing router is a saved-prompt runner; no extension registration (F8). |
| 7 | Floating Bubble core | ⚪ Not started (code exists, unverified) | Capability UI inside the Bubble service to separate (F4). |
| 8 | Plugin architecture and developer SDK | ⚪ Not started | Model decided: signed plugin APKs (D1). Design pending. |
| 9 | Plugin management and Bubble integration | ⚪ Not started | Hard-coded catalogue to replace (F2). Verify separate enable vs Bubble-access controls. |
| 10 | Security, settings and activity | ⚪ Not started (code exists, unverified) | Approval gateway and vault appear reusable; read in full. |
| 11 | Full integration and acceptance testing | ⚪ Not started | Must separate CI / unit / integration / emulator / device. |
| 12 | First foundation release | 🔒 Blocked | No APK until explicitly authorised. |

## Development record

| Date | Commit | Scope | Verification |
|---|---|---|---|
| 2026-11-03 | `d792357` | Stage 2 audit + correction plan (docs only) | CI runs #404/#405 on that commit: check result before relying on them |
| 2026-11-03 | (this commit) | Tracker rewritten to plan; legacy tracker archived; decision D1 recorded; AutoDroid doc scope note | Docs only |

Known defects and open items: see findings F1–F11 in `docs/STAGE_2_AUDIT.md`.
