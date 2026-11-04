# Stage 8 — Plugin Architecture and Developer SDK: Design (for owner review)

Status: **DRAFT. No code has been written for this design.** It implements decision D1 (`docs/DECISIONS.md`): plugins are separate, signed Android APKs. It addresses audit findings F2, F3, F5, F6, F7, F12 and F19 (`docs/STAGE_2_AUDIT.md`).

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

```
interface IOneAiPlugin {
    String describe();                                  // JSON, must match the manifest
    void execute(in ExecuteRequest request, IPluginCallback callback);
    String view(String serviceId);                      // declarative Bubble view as JSON
    void cancel(String requestId);
}
parcelable ExecuteRequest { String requestId; String commandName; Bundle-free map<String,String> args; }
interface IPluginCallback {
    void onResult(String requestId, String title, String content);
    void onError(String requestId, String code, String message);
}
interface IHostCallback {                               // given to the plugin
    void reportActivity(String message);                // bounded, no secrets
}
```

- All strings and maps use the existing bounds (keys ≤ 80, values ≤ 4,000, output ≤ 20,000, titles ≤ 120). Oversized results fail the call. The binder transaction limit (about 1 MB) is respected by those bounds.
- **No Android views or windows cross the boundary.** `view()` returns JSON for the same `BubbleView` model used today (`Label`, `TextInput`, `Button`). A button names a command and which inputs fill which arguments; the host renders it and runs it through the normal path: gateway → approval → `execute`. This keeps the overlay under the core's control and removes the need for plugins to hold overlay permission.
- Calls have deadlines (default 30 s), one in-flight request per plugin by default, and `cancel` on timeout or user dismiss.

## 6. Permissions and approvals

- **Host policy:** unchanged. `PluginActionGateway` + `CapabilityPolicy` decide per command; sensitive capabilities require a one-use approval ticket for the exact action. Enabling a plugin never grants Bubble access; those remain two settings.
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
3. A **test kit** with an in-memory fake host so plugin authors can unit-test without a device.
4. Developer guide replacing the current `PLUGIN_SDK.md` (manifest v1 stays readable for migration).

## 9. Host work

- `PluginConnector`: discovery, import with pin, bind with signature re-check, deadlines, unbind, dead-binder handling. Pure logic (pin check, manifest parse/validate, bounds) is separated from Android calls so it is JVM-unit-testable.
- Manifest v2 parser/validator, plugin API level constant, compatibility and dependency checks.
- Adapter bridging: an `ExternalPluginAdapter` implementing the existing `PluginAdapter`, so `PluginRuntime`, the gateway, the command registry (`registerExtension`) and the Bubble contract work without changes to their callers.
- Bubble: a generic provider that fetches `view()` JSON and maps it to `BubbleView`.
- Replace the hard-coded `BuiltInCatalogue` and built-in providers progressively (Stage 9).

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

## 11. Proposed Stage 8 work breakdown (each a CI-checked commit)

1. Manifest v2 model, validator, API level and compatibility checks, with tests.
2. Pin/trust logic and signature check abstraction, with tests (fake package manager).
3. AIDL, SDK module, base service, bounds/timeout enforcement, tests with a fake host.
4. Host `PluginConnector` + `ExternalPluginAdapter`, wired to `PluginRuntime` and the command registry, tests with a fake binder.
5. Declarative-view JSON parser and the generic external-plugin Bubble provider, tests.
6. Sample echo plugin and developer guide. Instrumentation/emulator test scaffolding for Stage 11.

Stage 9 then adds the discovery/import UI, the per-plugin enabled and Bubble-access toggles on the Plugins screen, and extracts the six built-in capabilities, smallest first (App Control, Device Control, AutoDroid, Web Research, Voice, Screen Intelligence).

## 12. Decisions for the owner

Defaults will be used unless you say otherwise.

1. **Trust model.** Default: any developer's signature is acceptable **after the user confirms the fingerprint** (trust on first use, then pinned). Alternative: only an official ONE AI signing key, with an advanced override.
2. **Plugin UI.** Default: declarative views only; plugins cannot draw overlays. Alternative: also allow a plugin to open its own Activity from the Bubble (more capable, weaker control).
3. **Minimum Android.** Default: keep `minSdk 26` with the API 28+ signature check and a fallback.
4. **Verification gap.** Real binding, signature checks and process isolation can only be proven on an emulator or device; CI can prove the pure logic and a fake-binder host. Stage 8 will be reported with that distinction.
