# ONE AI — Development Tracker

Source of truth: *ONE AI Official Open-Shell Development Plan* (revised 3 November 2026). The 12-stage structure is fixed. The previous tracker is archived at `docs/LEGACY_TRACKER.md` and is **not** evidence of completion.

- Repository: `p1prince/one-ai` · Branch: `foundation/batch-03-ai-engine` · PR #1: **draft, unmerged** (do not merge without explicit authorisation).
- APK build/release: **blocked** until explicitly authorised.
- Evidence key: **CI** = compile + JVM unit tests only. **Emulator** / **Device** = not yet run for anything.

## Stage status

| # | Stage | Status | Notes |
|---:|---|---|---|
| 1 | Design recovery and requirements freeze | 🟢 Spec written; owner confirmation of D2 pending | Visual values recovered from `b2d025a` and confirmed by owner (`docs/STAGE_1_SPEC.md`). Bubble visual design not recovered. |
| 2 | Existing repository audit | 🔵 In progress | Draft audit and correction plan in `docs/STAGE_2_AUDIT.md`; awaiting owner approval. Exit needs file-by-file audit, dependency map, approved plan. |
| 3 | Core application architecture | 🟢 Accepted at automated-test level (CI) with carried-forward items | Evidence: `PluginManager`, Bubble policy, core services, error model and background tasks all tested with zero plugins (CI #416/#417, #420/#421). **Not verified on emulator/device** (fresh install, launch, restart). Carried forward: (1) `CoreServiceManager`/`BackgroundTasks` are tested but not yet consumed by the app; Bubble service migrates in Stage 7. (2) `security→plugins` (`PluginActionButton`) and `plugins→bubble` (Screen Intelligence adapter) imports end with capability extraction, Stages 8–9. (3) Device verification in Stage 11. |
| 4 | Main interface and navigation | 🟡 Implemented; CI passed; visual/emulator verification pending | Five destinations only (Home, Plugins, Develop, Activity, Settings); Command Manager is a Home module with back navigation; Home restored to the `b2d025a` structure (Command ONE card, module cards, status cards incl. live EXTENSIONS), 18dp spacing, 10sp nav labels, approved radii. Unit tests guard the five-destination invariant. **Not yet seen on a screen**; layout, spacing and navigation behaviour need emulator/device review (Stage 11). Plugins, Develop, Activity and Settings screens keep their functional layouts; their conformance to the approved style is not yet reviewed. Open: O2 (Command card subtitle kept as approved text for now). |
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
| 2026-11-03 | `d792357` | Stage 2 audit + correction plan (docs only) | CI #404, #405 passed (compile + unit tests) |
| 2026-11-03 | `7dc6be0` | Tracker rewritten to plan; legacy tracker archived; decision D1 recorded; AutoDroid doc scope note | CI #406 passed, #407 in progress at last check |
| 2026-11-03 | `354a519` | Audit update 2: dependency map, findings F12–F21 | CI #408, #409 passed |
| 2026-11-04 | `9712d21` | Stage 1 spec from `b2d025a`; decisions D2/D3 (docs only) | CI #410, #411 passed |
| 2026-11-04 | `53844df` | Stage 3 slice: `BuiltInCatalogue` as single source of plugin→Bubble bindings; `BubbleUiPolicy.availableServices` now takes bindings; Settings now lists App/Device Control Bubble toggles (defect: previously missing); 8 new unit tests | CI #412, #413 passed (compile + unit tests only; no emulator/device) |
| 2026-11-04 | `7d30d47` | Stage 3: `PluginStore` interface; `PluginManager` depends on it; 7 `PluginManagerTest` cases (empty catalogue, disabled-by-default, no implicit Bubble access, Bubble service removal, dependency order, developer mode) | CI #416, #417 passed (compile + unit tests only) |
| 2026-11-04 | `01de23c` | Stage 3: `CoreError`/`CoreErrors` (safe error model), `CoreService`/`CoreServiceManager` (lifecycle, failure isolation), `BackgroundTasks` (supervised tasks, reported failures, shutdown); 9 unit tests | CI #420, #421 passed (compile + unit tests only) |
| 2026-11-04 | `bf56dd3` | Stage 4: remove Commands tab; Command Manager as Home module with back handling; restore approved Home layout; navigation unit tests | CI #424, #425 passed (compile + unit tests only; UI not visually verified) |

Fixed defects: Sixth "Commands" navigation tab removed (`bf56dd3`). Settings omitted Bubble toggles for App Control and Device Control (`53844df`).

Known defects and open items: see findings F1–F21 in `docs/STAGE_2_AUDIT.md`.
