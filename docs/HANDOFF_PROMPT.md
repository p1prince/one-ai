> **SUPERSEDED — DO NOT USE AS THE LIVE HANDOFF.** This prompt is retained as historical context only. It points to an old branch and an obsolete shell-first/capability sequencing snapshot. For all current work, read `docs/PROJECT_CONTINUATION_RULES.md` first and verify the live repository. The active work branch at the 2026-11-07 reconciliation is `foundation/validation-core-shell-startup-isolation`; `foundation/batch-03-ai-engine` is the protected original and must not be modified. The owner-approved 12-stage roadmap and generic-plugin scope in the continuation rules override every instruction and snapshot below.

# Prompt for continuing ONE AI with another AI model

Copy everything in the block below into a new conversation and attach the **ONE AI Official Open-Shell Development Plan** (.docx). The prompt makes the new AI work out where the project actually is (git history, tracker and CI) before it does anything, so it does not depend on this file being up to date. The "snapshot" inside it is a convenience only and is labelled as possibly stale.

Run one AI session at a time: overlapping sessions have collided before.

````
## OWNER PRIORITY — COMPLETE THE CORE SHELL BEFORE EXTENSIONS (2026-11-06)

The immediate objective is to finish and accept the standalone ONE AI application itself. The shell must work independently before any optional capability extension is developed, extracted, packaged, or integrated.

This is a sequencing instruction, not a replacement for the official 12-stage plan and not an architecture change. It overrides older snapshot text or task lists that say the next action is AutoDroid, Web Research, Voice, Screen Intelligence, App Control, Device Control, or another capability extraction. Do not begin that work until the Core Shell acceptance checklist in `docs/CORE_SHELL_BASELINE.md` is satisfied and the owner accepts the shell.

Existing generic Plugin Manager, SDK, runtime, security, command, and Bubble infrastructure belongs to the shell and should be preserved. Do not rebuild it. Existing capability code may be inspected only to isolate a concrete dependency that prevents standalone shell operation; inspection is not permission to extract or package it.

You are continuing the development of ONE AI, an Android AI platform, in the GitHub repository p1prince/one-ai on branch foundation/batch-03-ai-engine (draft pull request #1, base main). Other AI sessions have worked on this project before you and may be working on it now. Do not assume you know where the project is. Find out first.

## STEP 0 — LOCATE YOURSELF BEFORE DOING ANYTHING
1. Run `git fetch origin foundation/batch-03-ai-engine` and `git pull --ff-only`. If the clone is shallow and a push or fetch fails, use `git fetch --depth=1000 origin <branch>`. GitHub sometimes returns a transient 500, so retry once after a pause.
2. Run `git log --format='%h %ad %s' --date=format:'%m-%d %H:%M' -30`. Note the head commit and everything committed since the last commit that DEVELOPMENT_TRACKER.md records.
3. Read DEVELOPMENT_TRACKER.md, docs/DECISIONS.md, docs/STAGE_8_DESIGN.md, docs/PLUGIN_DEVELOPER_GUIDE.md, docs/EMULATOR_TEST_PLAN.md, docs/STAGE_1_SPEC.md and docs/STAGE_2_AUDIT.md. Also read the attached "ONE AI Official Open-Shell Development Plan".
4. Check CI for the EXACT head SHA and for the last few commits: `https://api.github.com/repos/p1prince/one-ai/actions/runs?head_sha=<full sha>`. A push run and a pull-request run can both exist. A commit with no PR run is not a failure. If a run failed, read the job log and find the real error before deciding what to do.
5. Decide the current stage. Evidence ranks as: commits with CI results, then the tracker, then everything else. THE TRACKER CAN LAG BEHIND THE CODE. If commits exist that the tracker does not record, your first change is a small docs-only commit that reconciles the tracker. Record the commit hashes and CI run numbers you verified, and say plainly what stays unverified.
6. Keep the official 12-stage plan and its architecture. However, apply the owner-approved shell-first sequence: finish and obtain acceptance of the standalone Core Shell before doing any further capability-extension development, extraction, packaging, or integration. Use `docs/CORE_SHELL_BASELINE.md` as the shell acceptance baseline. Do not treat an older Stage 9 extraction list or the stale snapshot below as permission to start another extension. After shell acceptance, resume the remaining work within the existing 12-stage plan.
7. Report in a few sentences: head commit, CI status, which stages are done and at what evidence level, what you will do next. Then begin.

## SNAPSHOT (verified at commit 64d43be; it will go stale, so re-check it against Step 0)
- Head 64d43be "Stage 9.2: ExternalPluginCenter and separate Bubble access". CI #477 (push) passed. Later commits had no PR run.
- Stages 1-3: documented or accepted at automated-test level. Stage 2 audit and correction plan still await owner approval. Decision D2 confirmation is pending.
- Stages 4-7: implemented, CI green, NOT seen on a device or emulator.
- Stage 8 (plugin architecture and SDK): implemented in all 6 steps. Manifest v2, trust and certificate-pin registry, SDK module with AIDL, host adapter and registrar, declarative Bubble views with a generic `ExternalBubbleProvider`, sample `samples/echo-plugin`, developer guide, and emulator-test scaffolding. CI green. NOTHING has run on an emulator or device. The 9 instrumented tests have never been run, and whether instrumentation reaches the plugin as the ONE AI package is an unconfirmed assumption.
- Stage 9 (plugin management and Bubble integration): IN PROGRESS.
  - 9.1 (ca16031): one shared plugin runtime and approval gateway per process.
  - 9.2 (64d43be): `ExternalPluginCenter` (scan, fingerprint, import, enable/disable, update approval, removal), separate Bubble access in `PluginManager`, reserved or duplicate Bubble service names given to nobody.
  - NOT done at the time of the snapshot: wiring `ExternalPluginRegistrar`, `ExternalPluginCenter` and `PluginDiscovery` into `OneAiApplication`/`MainActivity` (no references existed); the Plugins-screen UI (discovery list, import with fingerprint confirmation, per-plugin Enabled toggle and separate Bubble-access toggle, update approval, removal); replacing the hard-coded `BuiltInCatalogue` (finding F2); extracting the six built-in capabilities into separate plugin APKs, smallest first (App Control, Device Control, AutoDroid, Web Research, Voice, Screen Intelligence); verifying that enable and Bubble access are independent.
- Stages 10-11 not started. Stage 12 is blocked.
- Open items for the owner: ONE AI has no release signing key, so `OneAiHost.RELEASE_CERTIFICATES` is empty and plugins fail closed unless built with the development override. The Stage 9 UI may not hard-code any specific plugin.

## PRODUCT
ONE AI is an independent, extensible AI shell. Core: the app with five-section navigation (Home, Plugins, Develop, Activity, Settings; Command Manager is a Home module, not a tab), AI engine, command framework, plugin management and SDK, security and approvals, settings and activity, and the Floating Bubble as a permanent core component. Capabilities (Voice, Screen Intelligence, Web Research, App Control, Device Control, AutoDroid, future automation) are separate plugins, never core.

## CHOSEN ARCHITECTURE (do not change without my approval)
Plugins are separate, signed Android APKs reached through a bound-service AIDL interface. Plugin ID = package name. The user confirms and pins the signing certificate on import, and it is re-checked on every bind. The host decides permissions and approvals; the plugin only executes. Bubble content is declarative views rendered by the core. No dynamic loading of code from folders.

## NON-NEGOTIABLE RULES
- Keep the official 12-stage plan; do not add, remove, or redesign stages without explicit authorisation. The current sequencing priority is Core Shell first: no further capability extension development, extraction, packaging, or integration until the standalone shell is complete and accepted.
- The Bubble starts empty. Plugin enablement and plugin Bubble access are two separate user settings; enabling must never grant Bubble access.
- Do not hardcode future capabilities into the core or the Bubble shell. A unit test guards the shell.
- Do not invent design details. Use only the recovered values and text in docs/STAGE_1_SPEC.md. Do not reintroduce the animated AI-core dashboard.
- NEVER merge PR #1. NEVER build an APK. Both need my explicit authorisation.
- Never claim something is verified on a device or emulator unless it was actually run there. "CI" here means compile, JVM unit tests, lint, and a compile-only check of the emulator tests. It builds no APK and runs nothing on a device. Keep local-JVM, CI, emulator and device evidence separate in the tracker.
- Fix genuine failures at their real cause. Do not weaken, skip or hide tests. (Several past CI failures were flaws in the new tests themselves. Say which it was, with evidence.)
- Preserve working code. Make focused commits with tests. Never overwrite an existing file wholesale; read it and extend it.
- COLLISION RULE: other sessions may commit while you work (a duplicate Stage 8.1 once overwrote files and broke the build). Before editing any file, and again before you push, run `git fetch` and check whether the branch moved. If it did, re-read the files you are touching and rebase on the new head. If you cannot push cleanly, stop and tell me.
- Commit messages end with the attribution lines I specify for the session. Update DEVELOPMENT_TRACKER.md with commit hashes and CI run numbers in a separate small commit after CI results are known.
- Avoid routine questions. For a genuinely ambiguous architecture decision, ask ONE concise question.

## WORKING METHOD THAT HAS WORKED
- The workspace may have no Android SDK and no Maven access, so GitHub CI is the authoritative compile check. Keep logic in pure Kotlin classes where possible. Those can be compiled and tested locally with the Kotlin 2.0.21 compiler jar and JUnit jars inside the Gradle install (`/opt/gradle-*/lib/`). Code that uses `PluginRecord` (defined in `OneAiStore.kt`, which needs Android) needs a small local stub. Local results supplement CI; they never replace it.
- After pushing, poll CI for the exact SHA and report the run numbers. Write careful code and fix CI failures before moving on.
- Android classes (binder, certificate reading, overlay) are only compile- and lint-checked until Stage 11. Say so every time you report them.

## FINISH EVERY RESPONSE WITH
A short list of clickable next-step options for me.
````
