# Prompt for continuing ONE AI with another AI model

Copy everything in the block below into the new conversation, and attach the Official Open-Shell Development Plan (.docx).

```
You are continuing the development of ONE AI, an Android AI platform, in the GitHub repository p1prince/one-ai on branch foundation/batch-03-ai-engine (draft pull request #1, base main).

SOURCES OF TRUTH, in priority order:
1. The attached "ONE AI Official Open-Shell Development Plan" and any newer instruction from me.
2. In the repository: DEVELOPMENT_TRACKER.md (stage status and commit/CI record), docs/DECISIONS.md (decisions D1-D3, open items), docs/STAGE_1_SPEC.md (approved design values and text recovered from commit b2d025a), docs/STAGE_2_AUDIT.md (findings F1-F21), docs/STAGE_8_DESIGN.md (current plugin design), PLUGIN_SDK.md.
Read these first. Inspect the actual repo, branch head, PR state and the CI result for the exact commit before relying on anything. Do not trust any status that is not backed by a commit hash and a CI run.

PRODUCT: ONE AI is an independent, extensible AI shell. Core: app and five-section navigation (Home, Plugins, Develop, Activity, Settings; Command Manager is a Home module, not a tab), AI engine, command framework, plugin management and SDK, security/approvals, settings and activity, and the Floating Bubble as a permanent core component. Capabilities (Voice, Screen Intelligence, Web Research, App Control, Device Control, AutoDroid, future automation) are separate plugins, never core.

CHOSEN ARCHITECTURE (do not change without my approval): plugins are separate, signed Android APKs reached through a bound-service AIDL interface; plugin ID = package name; user pins the signing certificate on import; the host decides permissions and approvals, the plugin only executes; Bubble content is declarative views rendered by the core. No dynamic loading of code from folders.

NON-NEGOTIABLE RULES:
- Keep the fixed 12-stage plan and work in order; verify a stage before advancing.
- The Bubble starts empty; plugin enablement and plugin Bubble access are two separate user settings.
- Do not hardcode future capabilities into the core or the Bubble shell. A unit test guards the shell.
- Do not invent design details; use only the recovered values and text. Do not reintroduce the animated AI-core dashboard.
- Never merge PR #1. Never build an APK. Both need my explicit authorisation.
- Check CI for the exact commit you are reporting. CI here means compile plus JVM unit tests only. Never call something verified on a device or emulator unless it was actually run there. Say plainly what is unverified.
- Preserve working code; make focused commits with tests; update DEVELOPMENT_TRACKER.md with commit hashes and CI run numbers; end commit messages with the attribution lines I specify.
- Before editing, run git pull and read DEVELOPMENT_TRACKER.md and the files you will touch: other sessions may have committed. Never overwrite an existing file wholesale; extend it.
- Avoid asking routine questions. For a genuinely ambiguous architecture decision, ask one concise question.

CURRENT STATE (verify against the tracker): Stages 1-3 documented/accepted at automated level; Stages 4-7 implemented with CI passing but unverified on a device; Stage 8 is 4 of 6 work-breakdown steps done (manifest v2, trust/pinning registry, SDK module with AIDL, host external-plugin adapter and registrar); Stages 9-11 not started; Stage 12 blocked. Some Stage 8 commits record local-JVM verification as well as CI, so read each tracker row's evidence column.

NEXT STEPS: finish Stage 8 per docs/STAGE_8_DESIGN.md section 11: step 5 (declarative-view JSON parser and the generic external-plugin Bubble provider) and step 6 (sample echo plugin, developer guide, emulator-test scaffolding). Then Stage 9 (discovery/import UI, per-plugin enabled and Bubble-access toggles on the Plugins screen, extraction of the six built-in capabilities). The workspace has no Android SDK, so GitHub CI is the only compile check; write code carefully and fix CI failures before moving on.

At the end of every response, give me a short list of clickable next-step options.
```
