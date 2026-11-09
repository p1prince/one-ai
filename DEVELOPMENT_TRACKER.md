# ONE AI — Release Tracker

> **Active roadmap:** `DEVELOPMENT_PLAN.md`  
> **Mandatory product scope and architecture:** `docs/PROJECT_CONTINUATION_RULES.md` and `docs/DECISIONS.md`  
> **Historical development record:** `docs/archive/DEVELOPMENT_TRACKER_PRE_RELEASE.md`  
> **Historical handoff:** `docs/archive/HANDOFF_PROMPT_PRE_RELEASE.md`

This tracker is the active record for the V1.0 release and distribution phase. It replaces the old stage tracker as the active work queue; it does not erase the prior implementation history.

## Current baseline

- Repository: `p1prince/one-ai`
- Active tested branch: `foundation/bubble-panel-gestures`
- Baseline commit: `658fd987089ed9770a38fc9545c54362882573ff`
- Android Validation run #693 passed on the exact baseline SHA.
- Owner reports that the latest tested build works as expected on the phone.
- Application ID: `com.oneai.app`
- Configured app version: `0.2.0`
- Configured Android version code: `2`

## Release status

| Batch | Work | Status | Evidence / next action |
|---:|---|---|---|
| 1 | Release baseline and identity audit | 🟡 In progress | Repository baseline and package/version values confirmed. Complete audit of variants, signing configuration, workflow and debug-to-release transition. |
| 2 | Permanent release signing and build pipeline | ⚪ Not started | Requires owner authorization for release-key creation/import and protected secret setup. |
| 3 | Core in-app update manager | ⚪ Not started | Must be included before first public V1.0 so V1.0 can discover later releases. |
| 4 | Upgrade continuity and data preservation | ⚪ Not started | Test same-package, same-release-signer in-place upgrades and database migrations. |
| 5 | V1.0 release candidate review and owner acceptance | ⚪ Not started | Requires exact signed artifact and device test evidence. |
| 6 | Merge, tag and publish ONE AI 1.0.0 | 🔒 Owner authorization required | No merge, tag, release publication or release APK is authorized yet. |

## Important constraints

- Do not rebuild completed app features or restart the product roadmap.
- Keep `applicationId = com.oneai.app`.
- Do not reset Android `versionCode`; current configured value is 2. The first official release must use a higher value, confirmed across variants.
- Keep the permanent release keystore private, backed up, and out of Git.
- A debug-signed test install may need a one-time uninstall/reinstall to move to a different release certificate. Protect/export user data before that transition.
- Future official updates must use the same package ID and release-signing identity, and a strictly higher version code.
- The updater is Core functionality and must ship in V1.0 if V1.0 is expected to detect future releases.
- Android installation confirmation remains user-controlled. No silent install.
- No merge, release build, release-key operation, or publication without explicit owner authorization.

## Work log

| Date | Commit / reference | Work | Verification |
|---|---|---|---|
| 2026-11-09 | `658fd987089ed9770a38fc9545c54362882573ff` | Owner-tested current app baseline, including Floating Bubble interaction and Settings section navigation | Android Validation #693 passed on exact SHA; owner reports device test passed |

Update this file after each batch with exact commit SHA, CI run ID, device/emulator evidence, owner approvals, and the next single task. Never mark a batch complete based on a plan or a code review alone.
