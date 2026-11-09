# ONE AI — Active Handoff Prompt

You are continuing the existing ONE AI Android application in repository `p1prince/one-ai`. This is a continuation, not a new project. The application has reached an owner-tested V1.0 candidate baseline. Your active assignment is the V1.0 Release, Distribution & Update roadmap.

## Read these files first

1. `docs/PROJECT_CONTINUATION_RULES.md` — mandatory product scope and architecture.
2. `docs/DECISIONS.md` — owner-approved decisions; do not reopen settled decisions.
3. `DEVELOPMENT_PLAN.md` — the active V1.0 release roadmap.
4. `DEVELOPMENT_TRACKER.md` — current release batch and evidence.
5. `README.md` and relevant build/workflow/signing files.

The previous app-construction tracker and handoff are retained under `docs/archive/` for historical context only. Do not use them as the active task list.

## Starting baseline

- Repository: `p1prince/one-ai`
- Last recorded tested branch: `foundation/bubble-panel-gestures`
- Last recorded baseline SHA: `658fd987089ed9770a38fc9545c54362882573ff`
- Android Validation #693 passed on that exact SHA.
- The owner reports that the latest tested build works as expected on the phone.
- Application ID: `com.oneai.app`
- Current configured version: `0.2.0`
- Current configured version code: `2`

These are recorded starting facts, not permission to assume the repository has not moved. Fetch/inspect the live repository and current branch before doing work. Confirm CI against the exact current HEAD.

## Your immediate task

Continue **Batch 1 — Release baseline and identity audit** in `DEVELOPMENT_PLAN.md`.

Inspect:
- Current branch, HEAD, working tree, and recent commits.
- Application ID, namespace, product flavors, version name/code, SDK levels, and all relevant APK variants.
- Android Build and validation workflows.
- Existing signing configuration and whether a permanent release key exists.
- The current test-install signing identity if it can be established safely.
- The one-time debug-to-release transition and a safe data-protection approach.

Do not generate, replace, export, or upload a signing key during the audit. Do not build an APK during the audit. Report evidence and recommendations first.

## Product and architecture rules

- ONE AI is a complete standalone Android AI shell with a generic plugin ecosystem. Do not redesign it or rebuild completed features.
- The Floating Bubble is permanent Core functionality and starts empty. Plugin enablement and Bubble access remain separate.
- The generic Plugin Centre, Developer Mode/SDK, AI engine, command framework, security, settings, and activity management are Core.
- Individual capability plugins (Web Research, Voice, Screen Intelligence, App Control, Device Control, AutoDroid, automation examples, etc.) are future optional projects, not release blockers or current tasks.
- Preserve the package ID `com.oneai.app`.
- Keep the permanent release-signing identity stable across every official release. Never commit private keys or secrets.
- Never reset `versionCode`; every update must have a higher code.
- Include the in-app update manager in the first public V1.0 release so V1.0 can discover future releases.
- Use GitHub Releases as the initial distribution channel.
- The updater downloads and verifies the official APK, then invokes Android's user-confirmed installer. Do not implement silent installation or bypass Android security.
- Preserve user data and test every required database migration.
- A debug-to-release signer change may require a one-time reinstall. Do not claim debug APKs can be updated in place by a differently signed release APK.

## Authorization boundaries

The owner has authorized planning and repository inspection only unless they explicitly grant more authority in a later message.

Do not:
- Merge any branch into `main`.
- Publish a GitHub Release or create the official V1.0 tag.
- Build a release APK or trigger a new APK build.
- Generate/import/rotate the permanent release key or change signing secrets.
- Delete source, rewrite project architecture, or alter completed app functionality.
- Push commits or change branch refs without explicit authorization.

When implementation is authorized, work in small, evidence-backed batches, preserve existing content/history, and ask only when a genuinely owner-controlled decision is required.

## Verification and handoff

- Check CI for the exact commit SHA. Record run IDs and results.
- Distinguish CI, JVM tests, integration tests, emulator tests, physical-device tests, and owner-reported tests.
- Never claim device verification unless the device test actually occurred.
- Fix genuine failures at their source; do not weaken or skip tests.
- Before ending, update `DEVELOPMENT_TRACKER.md` with exact branch, SHA, changed files, evidence, approvals, and the single next task.
- End with a concise status report: completed batches, current batch, remaining batches, and the next recommended action.
