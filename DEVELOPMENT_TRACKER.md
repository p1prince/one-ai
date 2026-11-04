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
| 5 | AI engine and orchestration | 🟡 Implemented; CI passed; provider/device verification pending | Provider-neutral `AiProvider`, OpenAI-compatible adapter, Keystore-encrypted credentials and bounded responses already existed. Added: in-memory conversation + context windowing, transient-failure retry (network/429/5xx), conversation wired into Home with a New conversation control. **Not verified:** no live provider call has been made by me or in CI; Keystore/credential behaviour and real HTTP error mapping need device/provider testing (Stage 11). Gaps: no second provider adapter exists (neutrality is by interface only); conversations are not persisted by design; no streaming; retry can duplicate a billed request after a timeout. |
| 6 | Core command framework | 🟡 Implemented; CI passed; app/device verification pending | `CommandRegistry` + `CommandExecutor`: `owner/name` IDs, core vs extension commands, validation and bounds, statuses (completed/failed/rejected/awaiting approval/not found), failure containment, extension commands routed through `PluginRuntime` (so permissions, approval tickets and isolation apply), availability follows plugin enabled state, removal unregisters. `core/run_saved` registered and used for saved-command runs from Home. Documented in `PLUGIN_SDK.md`. **Gaps:** no external plugin can register commands yet (needs Stage 8 extension model); Bubble command list still uses the old router until the Stage 7 rework; no UI lists registry commands yet; execution status is the result status only (no live in-progress tracking). |
| 7 | Floating Bubble core | 🟡 Implemented; CI passed; **device verification required before acceptance** | `FloatingBubbleService` is now a generic shell (843→~420 lines): overlay, drag, size/position prefs, core command service, one shared approval/result flow. All other services come from `BubbleServiceProvider` (declarative views, `PluginCall`/`GatedHostAction`/`Message` actions) registered by `OneAiApplication`. An architecture unit test fails the build if the shell references any specific capability. Starts empty: no providers, no services, nothing enabled by default (policy unit-tested). The six capability UIs were moved, behaviour preserved, into interim `com.oneai.app.builtin` providers. **Not verified on a device/emulator:** overlay, dragging, persistence, every capability flow through the new generic path, voice capture via the new in-process bridge, screen-capture hand-off, and Application-level registration after a service restart. Minor wording changes in some result titles. **Carry-forward:** capability code still ships inside the app (extraction in Stages 8-9); Bubble command list still uses the old saved-command router rather than the Stage 6 executor; Settings still reads bindings from `BuiltInCatalogue` (kept in step by a test). |
| 8 | Plugin architecture and developer SDK | 🔵 Design drafted; awaiting owner review before code | `docs/STAGE_8_DESIGN.md`: signed plugin APKs, bound-service AIDL, ID = package name, pinned signing certificate, manifest v2, declarative Bubble views, host-decides/plugin-executes, SDK + sample + test kit, 6-commit work plan. No Stage 8 code exists yet. |
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
| 2026-11-04 | `ab3e6ea` | Stage 5: `AiConversation`, `ContextPolicy`, `RetryingAiProvider`; orchestrator accepts optional conversation; Home keeps a conversation; 14 unit tests | CI #428, #429 passed (compile + unit tests only; no live provider call) |
| 2026-11-04 | `0613a05` | Stage 6: command registry/executor, `core/run_saved`, `PluginManager` removal hook, SDK docs; 16 unit tests | CI #432, #433 passed (compile + unit tests only) |
| 2026-11-04 | `09bd87b` | Stage 7: generic Bubble contract and provider registry; service refactor; interim built-in providers; `OneAiApplication`; voice bridge; architecture guard test; SDK docs | CI #436, #437 passed (compile + unit tests only; **no emulator/device run of the refactored Bubble**) |
| 2026-11-04 | (this commit) | Stage 8 design document (docs only) | Docs only |

Fixed defects: Sixth "Commands" navigation tab removed (`bf56dd3`). Settings omitted Bubble toggles for App Control and Device Control (`53844df`).

Known defects and open items: see findings F1–F21 in `docs/STAGE_2_AUDIT.md`.
