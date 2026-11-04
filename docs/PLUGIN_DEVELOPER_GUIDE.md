# ONE AI Plugin Developer Guide (plugin API level 1)

For people who build a plugin for ONE AI. The reference implementation is `samples/echo-plugin`; copy it as a starting point. Design background and threat model: `docs/STAGE_8_DESIGN.md`. The older `PLUGIN_SDK.md` describes manifest v1 and the in-app adapters; it stays for migration.

> **Status (read this first).** The plugin contract, SDK, host trust logic and Bubble rendering are implemented and unit-tested in CI (compile + JVM tests only). **Nothing here has been run on an emulator or device yet**: real binding, certificate reading, process isolation and the Bubble overlay are unverified (see `docs/EMULATOR_TEST_PLAN.md`). The import/enable screens are not built yet (Stage 9), so today a plugin cannot be imported from the app UI. The SDK is not published to a Maven repository; add it as a module, as the sample does.

## 1. What a plugin is

A plugin is a normal, separately installed Android app (its own APK, process, permissions and signature). It exposes one **bound service**. ONE AI discovers it, asks the user to trust it, then calls it over a small AIDL interface. ONE AI never loads plugin code into its own process, and never installs plugins.

The host decides; the plugin only executes. A plugin cannot grant itself a capability, skip an approval, or draw on screen.

- **Plugin ID = the app's package name.** It must be lowercase reverse-domain form (`[a-z][a-z0-9]*(.[a-z0-9]+)+`, at most 100 characters). Underscores in package segments are rejected by ONE AI's validator, so avoid them.
- **Version** = the APK's `versionName` (semantic version, `1.2.0` or `1.2.0-beta1`) and `versionCode` (positive).

## 2. Minimum plugin

1. Depend on the SDK module: `implementation(project(":one-ai-plugin-sdk"))`.
2. Subclass `OneAiPluginService` and implement `onCommand` (and optionally `onView`).
3. Declare the service in `AndroidManifest.xml`: **exported**, **no `android:permission`**, with intent action `com.oneai.plugin.action.PLUGIN` and a `<meta-data android:name="com.oneai.plugin.manifest" android:resource="@xml/one_ai_plugin" />`.
4. Write the manifest v2 XML resource (section 3).

Exactly one such service per package; ONE AI refuses a package with none or several. See `samples/echo-plugin` for a complete, working example.

## 3. Manifest v2 (`res/xml/one_ai_plugin.xml`)

ONE AI reads this without running your code, so the user can review it before trusting you.

```xml
<one-ai-plugin manifestVersion="2" minCoreApi="1" targetCoreApi="1">
    <info name="Notes" description="Save and find quick notes" />
    <capability name="files" />
    <command name="add_note" title="Add note" description="Saves a note" capability="files" />
    <bubble service="notes" title="Notes" />      <!-- optional -->
    <depends plugin="com.example.other" />        <!-- optional -->
</one-ai-plugin>
```

| Rule | Limit |
|---|---|
| `manifestVersion` | must be `2` |
| `minCoreApi` / `targetCoreApi` | integers; `minCoreApi` ≥ 1, `targetCoreApi` ≥ `minCoreApi`. This ONE AI provides API level **1**; a plugin whose `minCoreApi` is higher is refused |
| Name | 1–60 characters |
| Description | up to 500 characters |
| Capabilities | 1–12, from the supported list (below) |
| Commands | up to 50; names `[a-z][a-z0-9_]{0,39}`; title 1–80; description up to 300; each names exactly one declared capability |
| Bubble service | at most one; ID `[a-z][a-z0-9_]{0,39}`; title 1–60. Without it the plugin can never appear in the Bubble |
| Dependencies | up to 16 valid plugin IDs, not itself |

Unknown elements and attributes are ignored, so a plugin built for a newer API can still be read. Anything ONE AI acts on is validated strictly.

### Capabilities and approval

Supported: `commands`, `internet`, `screen_capture`, `microphone`, `audio`, `device_actions`, `accessibility`, `automation`, `files`, `app_launch`.

ONE AI asks the user to approve each use (one-use, for the exact action) for every capability **except `commands` and `audio`**. Declaring a capability grants no Android permission: request any Android permission your plugin needs (for example `RECORD_AUDIO`) in your own app. Keep secrets such as API keys inside your plugin app; ONE AI never sends its own credentials to plugins.

## 4. Running commands

`onCommand(command, args, context)` runs on a worker thread. Return `PluginOutput(title, content)` or throw `PluginException(code, message)` for a clean failure. The SDK enforces:

| Guarantee | Detail |
|---|---|
| Only ONE AI may call | Caller package and signing certificate are checked on every call (section 6) |
| Bounded input | up to 32 arguments; names 1–80 characters; values up to 4,000 |
| Bounded output | title up to 120, content up to 20,000. Oversized output fails the call with `OUTPUT_TOO_LARGE`; it is never truncated silently |
| One request at a time | a second concurrent request gets `BUSY` |
| Deadline | 30 seconds by default (`commandTimeoutMs`); on timeout the caller gets `TIMEOUT` and the worker is interrupted |
| Exactly one reply | per request, even on cancel or timeout |
| No internal leakage | an unexpected exception becomes `PLUGIN_ERROR` / "The plugin failed" |

Make commands **cancellable**: let `InterruptedException` escape and do not swallow interrupts. After two abandoned commands that ignore interruption, the SDK refuses further requests with `UNRESPONSIVE`.

Error codes must look like `[A-Z][A-Z0-9_]{0,39}`; anything else is replaced by `PLUGIN_ERROR`. Messages are cut to 300 characters and are shown to the user, so never include secrets or internals. `context.reportActivity("...")` sends a bounded (300 characters) progress line, shown in Activity as plugin-originated; never put secrets in it.

ONE AI waits slightly longer than the plugin's own deadline (35 seconds) and cancels the request if it gives up.

## 5. Bubble views (declarative)

If you declare a `<bubble>`, return its view from `onView(serviceId)` as JSON. It must be fast (ONE AI waits 5 seconds) and at most 20,000 characters. The core draws it; plugins never draw overlays.

```json
{"v":1,"elements":[
  {"t":"label","text":"Hello","size":14},
  {"t":"input","key":"text","hint":"Type here"},
  {"t":"button","label":"Save","command":"add_note","args":{"text":"@text","mode":"quick"}}
]}
```

- Types: `label`, `input`, `button`. At most 40 elements; label text up to 2,000; hint up to 100; button label up to 60; `size` 10–24 (else 14).
- Input keys: `[a-z][a-z0-9_]{0,31}`, unique.
- A button names a **declared** command. Its `args` are strings: `"@key"` takes the text of the input with that key; `"@@x"` is the literal `@x`; anything else is a literal. Up to 32 arguments.
- Parsing is strict and fails closed: any unknown type, undeclared command, missing input or out-of-bounds value rejects the whole view.
- Pressing a button runs the command through the normal path: the user's approval (drawn by ONE AI, showing the exact arguments) when the capability needs it, then your `onCommand`.
- Two separate user settings apply: enabling the plugin, and giving it Bubble access. The Bubble starts empty; nothing appears until the user turns both on.

## 6. Who may call you (host verification)

`hostVerifier()` decides which caller counts as ONE AI. The default trusts only ONE AI's **release** certificates, and that list is **empty until a release signing key exists** (Stage 12), so a release plugin built with the default currently rejects every caller (it fails closed). Options:

- Development: `HostVerifier(allowAnyCertificate = true)` accepts the ONE AI package under any certificate. Use it only in debuggable builds (the sample enables it only when the app is debuggable). **Never ship it in a release.**
- A plugin may also list ONE AI's certificate digests itself: `HostVerifier(trustedCertificates = setOf("<sha256 hex>"))`.

## 7. Trust, updates and removal (user's view)

1. The user installs your APK like any app. ONE AI lists it as *available, not trusted*; nothing is bound or run.
2. On import, ONE AI shows your name, package, version, declared capabilities and your **signing certificate SHA-256**, and the user must confirm it. ONE AI stores that pin. Plugins signed with more than one certificate are not supported.
3. On **every** call ONE AI re-checks the pin. If your certificate changed, the plugin is blocked and an event is logged.
4. **Updates:** an update that adds capabilities, commands, a different capability for a command, Bubble access or dependencies is held: the plugin is taken out of service until the user approves the new declaration, and it then stays disabled until they enable it again. An update signed with a different certificate, or with a lower `versionCode`, is refused.
5. Removing the plugin from ONE AI deletes its pin, commands and Bubble service. Uninstalling the APK is done through Android.

## 8. Testing your plugin

- Put behaviour in plain Kotlin and unit-test it on the JVM (see `EchoCommandsTest`).
- `PluginCallRunner` and `ReplySink` are plain classes: run your handler through the real runner with a fake sink to verify bounds and the exactly-one-reply rule.
- Check that your manifest XML and view JSON are acceptable to ONE AI. `EchoSamplePluginContractTest` (in the app's tests) shows how: it feeds both through the host's real parsers.
- Real binding, signature checks and the Bubble need an emulator or device: `docs/EMULATOR_TEST_PLAN.md`.

## 9. Known limits (API level 1)

- No streaming results; one result per request.
- Arguments and results are text only.
- A plugin cannot call back into ONE AI beyond progress lines.
- Plugin API stability: level 1 may change before the first foundation release.
