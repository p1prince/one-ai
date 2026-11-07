> **OWNER-APPROVED SCOPE CORRECTION — 2026-11-07 (AUTHORITATIVE):** Stage 8 is the generic plugin architecture, secure contract, SDK and developer tooling. Stage 9 is the generic Kodi-inspired Plugin Centre and its integration with ONE AI and the Floating Bubble. Individual capability plugins (including the six examples previously listed in this document) are future projects only, not current deliverables or acceptance dependencies. The extraction sequence and any instruction below to extract/package built-in capabilities are **superseded historical text — DO NOT EXECUTE**. Follow `docs/PROJECT_CONTINUATION_RULES.md` and the corrected roadmap in `DEVELOPMENT_TRACKER.md`.

# Stage 8 — Plugin Architecture and Developer SDK: Design and implementation review

Status: **Generic Stage 8 source/tooling implementation reviewed on 2026-11-07.** The reusable in-memory plugin-author test kit is now packaged in `:one-ai-plugin-testkit` and used by the Echo sample tests. This is not Android-runtime acceptance: real package discovery, certificate reads, binding, callbacks, process isolation and Bubble behaviour remain for Stage 11 emulator/device testing. The API 2 screen-launch callback is connected to the Floating Bubble's one-time approval surface; Android runtime launch behaviour remains unverified on a device.

This design implements D1 and the owner-approved D4 hybrid model: signed external APKs use the secure AIDL contract; built-in capabilities will become separate reference modules implementing the same contract during Stage 9. No arbitrary dex/class loading is permitted. The generic shell, SDK contract and plugin management/security remain core.

## 1. Why this model

Android does not let an app safely load arbitrary code from a folder: loading dex or native code from writable storage is blocked or discouraged on recent versions, bypasses Play and OS review, and would run plugin code with ONE AI's permissions and credentials in ONE AI's process. Separate APKs give each plugin its own process, its own Android permissions, its own signature and normal install/update/uninstall handled by Android. ONE AI never installs or executes plugin code itself.

Options considered:

| Option | Verdict |
|---|---|
| Load dex/jar from a plugin folder | Rejected: unsafe, plugin runs inside the core's process and permissions |
| Compile plugins into the app as modules | Not independently installable; kept only as the interim state |
| Plugin APK exposing a bound service (AIDL) | **Chosen.** Process isolation, push callbacks, binder caller identity |
| Content provider | Request/response only, no callbacks, awkward for long actions |
| Intents to exported activities | No background execution, poor result handling |

## 2. Overview

```
   Plugin APK (own process, own permissions)          ONE AI (host)
  ┌──────────────────────────────┐   bound service  ┌───────────────────────────────┐
  │ OneAiPluginService (SDK base) │◄────────────────►│ PluginConnector               │
  │  • describe()                 │   AIDL, bounded  │  • discover / import / pin    │
  │  • execute(command, args)     │   payloads       │  • verify signature on bind   │
  │  • view(serviceId)  (JSON)    │                  │  • PluginRuntime + Gateway    │
  │  • verifies caller is ONE AI  │                  │  • approvals, activity, errors│
  └──────────────────────────────┘                  │  • Bubble renders plugin views│
                                                     └───────────────────────────────┘
```

Responsibilities stay as they are today: the host decides, the plugin only executes. A plugin can never grant itself capabilities, skip approval or draw its own overlay.

## 3. Identity, discovery and trust

- **Plugin ID = the APK's package name.** Android guarantees uniqueness and that updates are signed by the same key, so the ID cannot be spoofed by a different publisher.
- **Discovery:** the plugin declares an exported service with intent action `com.oneai.plugin.action.PLUGIN`. ONE AI lists such services with `PackageManager.queryIntentServices`; the host manifest adds a matching `<queries><intent>` entry (Android 11+ package visibility), so it does not need `QUERY_ALL_PACKAGES`.
- **Import = explicit user trust.** Discovered plugins appear as *available, not trusted*. When the user imports one, ONE AI shows the app name, package, version, declared capabilities and the **signing-certificate SHA-256 fingerprint**, and stores a pin (package, certificate hash). Nothing is bound or run before that.
- **On every bind** the host re-checks the pin with `PackageManager.hasSigningCertificate` (API 28+, handles key rotation; a `GET_SIGNATURES` fallback for API 26–27). A mismatch disables the plugin and logs an event.
- **Updates:** a new version is re-validated. If it adds capabilities, commands or Bubble access, the plugin is disabled until the user approves the new declaration.
- **Plugin → host check:** the SDK base class rejects any caller that is not ONE AI (`Binder.getCallingUid` → package → signing-certificate hash from a list built into the SDK, with a debug override for development builds).
- ONE AI does **not** request `REQUEST_INSTALL_PACKAGES` and never installs APKs. Users install plugins like any other app.

## 4. Manifest v2 (declarative, read without running plugin code)

The service carries `<meta-data android:name="com.oneai.plugin.manifest" android:resource="@xml/one_ai_plugin" />`. The host reads the XML resource from the plugin package, so a manifest can be inspected before trust is granted.

```xml
<one-ai-plugin manifestVersion="2" minCoreApi="1" targetCoreApi="1">
  <info name="Notes" description="Save and find quick notes" />
  <capability name="files" />
  <command name="add_note" title="Add note" description="Saves a note" capability="files" />
  <bubble service="notes" title="Notes" />        <!-- optional; absent = no Bubble access possible -->
  <depends plugin="com.example.other" />          <!-- optional -->
</one-ai-plugin>
```

- `id` is not a field: it is the package name. `version` is the APK `versionName`/`versionCode`.
- **Compatibility:** `minCoreApi`/`targetCoreApi` are integers against the host's *plugin API level*. The host refuses a plugin whose minimum exceeds its level and warns when the target is older than a supported floor.
- **Capabilities** reuse the existing allow-list (`PluginManifestValidator.supportedCapabilities`). Every command declares exactly one capability, so the existing gateway and approval policy apply unchanged.
- The same limits as today apply (names, counts, 50 commands, descriptions).

## 5. Runtime interface (AIDL, shipped in the SDK)

The implemented AIDL contract is IOneAiPlugin plus IPluginCallback (see one-ai-plugin-sdk/src/main/aidl). Requests use parallel key/value string arrays rather than a custom parcelable or Bundle:

- describe() returns bounded JSON, currently {"sdkApi":2}.
- execute(requestId, commandName, argKeys, argValues, callback) is asynchronous and returns exactly one result/error; progress is a bounded callback.
- view(serviceId) returns declarative Bubble JSON; cancel(requestId) asks the plugin to stop.
- API 2 adds `onLaunch(requestId, label, PendingIntent)`. The host captures only a creator-package/UID-matched immutable PendingIntent, queues one request, and surfaces a Bubble confirmation showing the package plus an explicitly untrusted plugin label. The user must tap Allow once; the host consumes the request, rechecks the signing-certificate trust and enabled state, then sends it. Reject, cancellation, plugin removal, stale requests and failed trust checks clear the request. The host uses Android's explicit sender BAL opt-in on API 34+ and `MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE` on API 36+. Device-specific launch acceptance remains pending.

Versioning has two distinct checks:
1. sdkApi is the AIDL/SDK wire-contract version returned by describe(). The current SDK reports 2; the host accepts compatible wire versions 1 through 2.
2. Manifest minCoreApi/targetCoreApi describe the host feature API. API 2 features such as plugin-requested screen launch require minCoreApi="2". A plugin may use the v1 wire contract on a newer host when it does not need newer protocol features.

All strings and maps use hard bounds (keys up to 80, values up to 4,000, output up to 20,000, titles up to 120). Oversized results fail the call. No Android View or Window crosses the boundary. The host parses declarative JSON and renders it itself; button actions go through the normal command registry, gateway and approval path. Calls have deadlines (SDK default 30 s), one in-flight request per plugin, cancellation and exactly-once replies.

## 6. Permissions and approvals

- **Host policy:** unchanged. `PluginActionGateway` + `CapabilityPolicy` decide per command; sensitive capabilities require a one-use approval ticket for the exact action. Enabling a plugin never grants Bubble access; those remain two settings. A plugin-requested screen is a separate generic host action: Bubble confirmation, one-use PendingIntent, trust/enabled recheck and Android BAL opt-in are mandatory.
- **Android permissions belong to the plugin.** A Voice plugin declares and requests `RECORD_AUDIO` itself; a Screen plugin owns its MediaProjection consent and foreground service. This removes `RECORD_AUDIO` and `FOREGROUND_SERVICE_MEDIA_PROJECTION` from the core manifest (F5).
- **Plugin secrets** (for example an API key for web search) live inside the plugin app, which fixes the single-slot credential limitation in the core vault (F19). Plugins may offer their own settings Activity; the host can launch it from the Plugins screen with a user tap.

## 7. Failure isolation and lifecycle

- A plugin crash, hang or `DeadObjectException` yields a *failed* result and a logged event; the core and other plugins are unaffected.
- The host binds on demand (when enabled and invoked, or when its Bubble service is opened) and unbinds when idle, disabled or removed.
- Disable: unbind, cancel in-flight work, revoke pending approvals, hide its commands and Bubble service. Remove from ONE AI: delete the pin, commands and settings (the APK is uninstalled by the user through Android). Uninstalled packages are detected on next scan.
- Plugin activity is reported through the host: every invocation is already audited by the gateway; `reportActivity` adds bounded plugin-supplied lines, shown as plugin-originated.

## 8. SDK deliverables

1. `one-ai-plugin-sdk` Android library module: AIDL, `OneAiPluginService` base class (host verification, threading, size/timeout enforcement, safe error mapping), manifest XML schema, helper builders for views and results.
2. A **sample plugin** module (`samples/echo-plugin`) used as the reference and as the integration test target.
3. A **test kit** with an in-memory fake host so plugin authors can unit-test without a device. Implemented in `:one-ai-plugin-testkit`: `FakePluginHost` runs the same SDK request runner, captures progress/launch/result/error events, and exposes timeout/cancellation behaviour. It does not simulate Android Binder, package signatures, process isolation or host trust/approval UI.
4. Developer guide replacing the current `PLUGIN_SDK.md` (manifest v1 stays readable for migration).

## 9. Host work

- `PluginConnector`: discovery, import with pin, bind with signature re-check, deadlines, unbind, dead-binder handling. Pure logic (pin check, manifest parse/validate, bounds) is separated from Android calls so it is JVM-unit-testable.
- Manifest v2 parser/validator, plugin API level constant, compatibility and dependency checks.
- Adapter bridging: an `ExternalPluginAdapter` implementing the existing `PluginAdapter`, so `PluginRuntime`, the gateway, the command registry (`registerExtension`) and the Bubble contract work without changes to their callers.
- Bubble: a generic provider that fetches `view()` JSON and maps it to `BubbleView`.
- Provide the generic Kodi-inspired Plugin Centre, configuration, dependency/lifecycle/status and permission/approval visibility. The former instruction to replace/extract built-in capability modules is superseded by the owner-approved 2026-11-07 scope correction; no individual capability plugins are Stage 9 deliverables.

## 10. Threat model (summary)

| Threat | Mitigation |
|---|---|
| Fake plugin impersonates a trusted one | ID = package name; signature pin checked on every bind |
| Malicious app calls the plugin pretending to be ONE AI | SDK base class verifies the caller's package and certificate |
| Plugin escalates capability or skips approval | Host decides per command from the declared capability; plugin cannot invoke the gateway |
| Update adds powers silently | New capabilities/commands/Bubble access need fresh consent |
| Oversized or malformed payloads, DoS | Hard bounds, deadlines, single in-flight request, per-plugin failure isolation |
| Plugin leaks core secrets | Core credentials are never sent to plugins |
| Plugin spoofs core UI (phishing) | Plugins supply declarative views only; approval panels are drawn by the core and show the exact action |
| Plugin crashes the core | Separate process; binder errors contained |

Not covered: a plugin the user chooses to trust that misuses the permissions it legitimately holds. ONE AI shows what it declares and what it is approved to do; it cannot police the plugin app's own behaviour.

## 11. Stage 8 work breakdown and source disposition (each a CI-checked commit)

1. Manifest v2 model, validator, API level and compatibility checks, with tests.
2. Pin/trust logic and signature check abstraction, with tests (fake package manager).
3. AIDL, SDK module, base service, bounds/timeout enforcement, tests with a fake host.
4. Host `PluginConnector` + `ExternalPluginAdapter`, wired to `PluginRuntime` and the command registry, tests with a fake binder.
5. Declarative-view JSON parser and the generic external-plugin Bubble provider, tests.
6. Sample echo plugin and developer guide. Instrumentation/emulator test scaffolding for Stage 11.

Stage 9 has already delivered the discovery/import UI, independent plugin-enabled and Bubble-access toggles, process-wide host wiring and Settings listing. Its remaining architecture work is to replace the hard-coded built-in catalogue and extract the six built-in capabilities into separate reference modules implementing this same contract, in order: App Control → Device Control → AutoDroid → Web Research → Voice → Screen Intelligence. The UI and Android flows still require Stage 11 visual/device acceptance.

## 12. Recorded decisions and acceptance boundaries

The owner-approved defaults are in force: trust-on-first-use with fingerprint confirmation, declarative plugin views only, and minSdk 26. The host remains in control of approvals, Bubble rendering and requested screen launches.

1. Trust is on first use with explicit fingerprint confirmation, then pinned and rechecked on every bind.
2. Plugin UI is declarative only; plugins cannot draw overlays or approval UI.
3. Minimum Android remains API 26; Android-specific signature and binding behaviour needs emulator/device evidence.
4. CI proves compilation, JVM tests and instrumentation-source compilation only. It does not prove real binding, package discovery, certificate reads, callbacks or process isolation. Those remain Stage 11 acceptance work.
5. The API 2 PendingIntent host path is a Stage 9.4c dependency and must not be described as functional before approval/identity handling and device verification.
