> **Scope note (plan reconciliation):** AutoDroid is a **future plugin**, not core. The built-in `one.automation.engine` adapter described here is slated for extraction (see `docs/STAGE_2_AUDIT.md`). AutoDroid itself stays an independent project.

# AutoDroid integration boundary

## Status

Stage 9 introduces a deliberately narrow hand-off from ONE AI to the separately installed AutoDroid application. The AutoDroid repository remains independent and is not copied, merged, or included as a Gradle module.

## Repository review

The reviewed AutoDroid project is an Android/Kotlin application with a Clean Architecture/MVVM structure, a Room-backed macro repository, trigger providers, and a macro execution use case. Its actions include sensitive operations such as communications, device settings, app control, and accessibility-based UI automation.

The current manifest exposes com.aditsyal.autodroid.MainActivity as the launcher activity. Automation services and internal receivers are not a documented external IPC contract. No supported, authenticated API for listing or executing macros from another app was found.

## Implemented ONE AI boundary

- Plugin: one.automation.engine.
- Capability: automation, treated as sensitive by ONE AI's central capability policy.
- Bubble action: Open AutoDroid.
- Exact action ID: open_autodroid.
- Target: fixed package com.aditsyal.autodroid; launcher intent must resolve to that package.
- Approval: fresh exact-action, one-use approval through PluginRuntime.
- Package visibility: one explicit package query; no QUERY_ALL_PACKAGES.
- Failure behavior: missing package or launcher activity returns an unavailable result rather than claiming success.

The user chooses and starts macros inside AutoDroid. AutoDroid continues to own its macro database, execution engine, Android runtime permissions, accessibility enablement, and system confirmation flows. ONE AI does not execute macros remotely or pass macro IDs, action data, or caller-provided intents.

## Security requirements for any future IPC

A future macro bridge must not be inferred from the launcher hand-off. Before adding remote execution, both projects need a versioned contract that:

1. Authenticates the calling application and rejects untrusted callers.
2. Uses a narrow, validated request schema and fixed operations; never forwards arbitrary intents or serialized action lists.
3. Requires a user-visible confirmation in AutoDroid for each requested macro execution.
4. Preserves AutoDroid's own permission checks and Android system consent.
5. Returns bounded, non-sensitive result codes without exposing private macro configuration or logs.
6. Supports cancellation, timeouts, replay protection, and revocation.
7. Has focused unit and integration tests for unauthorized callers, malformed requests, disabled permissions, and execution failures.

Do not access AutoDroid's Room database from ONE AI, export an unprotected service, silently run a macro, or bypass either application's consent UI.

## Validation

The adapter has focused unit tests for the only supported action, wrong capabilities, unexpected macro data, and missing AutoDroid. Physical installation/launch behavior remains a Stage 11 device test. This stage does not authorize an APK build or merge.
