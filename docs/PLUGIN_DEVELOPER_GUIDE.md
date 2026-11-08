# ONE AI Plugin Developer Guide (plugin API level 3)

For people who build a plugin for ONE AI. The reference implementation is `samples/echo-plugin`; copy it as a starting point. Design background and threat model: `docs/STAGE_8_DESIGN.md`. The older `PLUGIN_SDK.md` describes manifest v1 and the in-app adapters; it stays for migration.

> **Status (read this first).** The plugin contract, SDK, host trust logic and Bubble rendering are implemented and unit-tested in CI (compile + JVM tests only). **Nothing here has been run on an emulator or device yet**: real binding, certificate reading, process isolation and the Bubble overlay are unverified (see `docs/EMULATOR_TEST_PLAN.md`). The Stage 9 Plugins screen and process-wide host wiring are now present: users can discover, fingerprint-review, import, enable, separately grant Bubble access, update-review and remove plugins. These screens and flows have not yet been visually or device-verified. The SDK is not published to a Maven repository; add it as a module, as the sample does.

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
| `minCoreApi` / `targetCoreApi` | integers; `minCoreApi` ≥ 1, `targetCoreApi` ≥ `minCoreApi`. This ONE AI provides core API level **3**; a plugin whose `minCoreApi` is higher is refused |
| Name | 1–60 characters |
| Description | up to 500 characters |
| Capabilities | 1–12, from the supported list (below) |
| Commands | up to 50; names `[a-z][a-z0-9_]{0,39}`; title 1–80; description up to 300; each names exactly one declared capability |
| Bubble service | at most one; ID `[a-z][a-z0-9_]{0,39}`; title 1–60. Without it the plugin can never appear in the Bubble |
| Dependencies | up to 16 valid plugin IDs, not itself |

Unknown elements and attributes are ignored, so a plugin built for a newer API can still be read. Anything ONE AI acts on is validated strictly.

### Capabilities and approval

Supported: `commands`, `internet`, `screen_capture`, `microphone`, `audio`, `device_actions`, `accessibility`, `automation`, `files`, `app_launch`. The list is closed and versioned: an identifier outside it makes your manifest invalid, and a new one is added only by a ONE AI release, noted against the plugin API level.

ONE AI asks the user to approve each use (one-use, for the exact action) for every capability **except `commands` and `audio`**. Declaring a capability grants no Android permission: request any Android permission your plugin needs (for example `RECORD_AUDIO`) in your own app. Keep secrets such as API keys inside your plugin app; ONE AI never sends its own credentials to plugins.

## 4. Test commands without a device

Add the reusable test kit to your plugin module's test dependencies:

```kotlin
testImplementation(project(":one-ai-plugin-testkit"))
```

Use the same command handler that your `OneAiPluginService` delegates to:

```kotlin
FakePluginHost(handler = commands::run).use { host ->
    val events = host.execute("test-1", "echo", mapOf("text" to "Hello")).await()
    assertEquals(PluginTestEvent.Completed("test-1", "Echo", "Hello"), events.last())
}
```

The in-memory fake host exercises the SDK command runner, bounds, progress, launch/result ordering, cancellation, timeout and terminal error handling. It does **not** emulate Android Binder, package-signature verification, process isolation, the host's trust/approval decisions or the actual Bubble UI. Those require host integration and Stage 11 emulator/device acceptance. See `:one-ai-plugin-testkit` for the reusable API.

## 5. Running commands

`onCommand(command, args, context)` runs on a worker thread. Return `PluginOutput(title, content)` or throw `PluginException(code, message)` for a clean failure. The SDK enforces:

| Guarantee | Detail |
|---|---|
| Only ONE AI may call | Caller package and signing certificate are checked on every call (section 7) |
| Bounded input | up to 32 arguments; names 1–80 characters; values up to 4,000 |
| Bounded output | title up to 120, content up to 20,000. Oversized output fails the call with `OUTPUT_TOO_LARGE`; it is never truncated silently |
| One request at a time | a second concurrent request gets `BUSY` |
| Deadline | 30 seconds by default (`commandTimeoutMs`); on timeout the caller gets `TIMEOUT` and the worker is interrupted |
| Exactly one reply | per request, even on cancel or timeout |
| No internal leakage | an unexpected exception becomes `PLUGIN_ERROR` / "The plugin failed" |

Make commands **cancellable**: let `InterruptedException` escape and do not swallow interrupts. After two abandoned commands that ignore interruption, the SDK refuses further requests with `UNRESPONSIVE`.

Error codes must look like `[A-Z][A-Z0-9_]{0,39}`; anything else is replaced by `PLUGIN_ERROR`. Messages are cut to 300 characters and are shown to the user, so never include secrets or internals. `context.reportActivity("...")` sends a bounded (300 characters) progress line, shown in Activity as plugin-originated; never put secrets in it.

ONE AI waits slightly longer than the plugin's own deadline (35 seconds) and cancels the request if it gives up.

The `describe()` handshake reports the AIDL SDK wire version (`sdkApi`). The host currently accepts wire versions 1 through 3. This is separate from manifest `minCoreApi`/`targetCoreApi`, which describe host feature compatibility; API 2 features such as the screen-launch callback must be declared with `minCoreApi="2"`, and plugins that declare `<settings>` must declare `minCoreApi="3"`.

### Plugin settings (API 3)

A plugin may declare user-configurable settings in its manifest (`<settings>`, schema and bounds in `docs/STAGE_9_CONFIG_DESIGN.md`). ONE AI owns, validates and stores the values; the plugin never draws settings UI. A plugin that declares `<settings>` must declare `minCoreApi="3"`.

ONE AI sends the plugin its current settings with `applyConfig` right after every bind, before any command or view request. Each command opens a fresh connection, so a change the user makes reaches the plugin on its next command. Read the snapshot inside your command handler through the `config` property of `OneAiPluginService`:

```kotlin
override fun onCommand(command: String, args: Map<String, String>, context: CallContext): PluginOutput {
    val prefix = config.string("greeting_prefix")
    val repeat = config.int("repeat", fallback = 1)
    val loud = config.boolean("loud")
    ...
}
```

`config` is `PluginConfig.EMPTY` until a snapshot arrives. Values are plain text: toggles are `"true"`/`"false"`, numbers are whole numbers, choices are the option `value`. The SDK applies the same bounds as ONE AI (at most 24 settings, 500 characters per value, 8 KB in total) and rejects an oversized snapshot whole, keeping the previous one. ONE AI refuses to run a plugin that declares settings but reports a wire level below 3, and it never sends `applyConfig` to a plugin that declares no settings. Settings never grant capabilities or Bubble access, and ONE AI logs which settings changed but never their values.

In tests, use `FakePluginHost.withConfig { command, args, context, config -> ... }` and call `host.applyConfig(1, mapOf("greeting_prefix" to "Hi"))` before `execute`; it mirrors the SDK's bounds and keep-previous behaviour. Real Binder delivery is not simulated.

#### Worked example: the Echo sample

`samples/echo-plugin` declares two optional settings and reads them in every command, so you can copy the whole pattern:

```xml
<one-ai-plugin manifestVersion="2" minCoreApi="3" targetCoreApi="3">
    ...
    <settings version="1">
        <setting key="prefix" type="text" title="Prefix" description="Text added before every reply." maxLength="40" />
        <setting key="uppercase" type="toggle" title="Uppercase replies" description="Return replies in capital letters." default="false" />
    </settings>
</one-ai-plugin>
```

```kotlin
// EchoPluginService
override fun onCommand(command: String, args: Map<String, String>, context: CallContext): PluginOutput =
    commands.run(command, args, context, config)

// EchoCommands: read the snapshot passed in, and cope with a missing value
private fun styled(text: String, config: PluginConfig): String {
    val body = if (config.boolean("uppercase")) text.uppercase() else text
    return config.string("prefix") + body
}
```

What to copy from it:

- **Keep the keys in one place.** The sample exposes them as constants and a unit test checks they match the manifest, so the two cannot drift apart.
- **Make optional things optional.** A setting that is `required="true"` with no default stops the user enabling the plugin until they set it, and, if an update adds one, stops an already-enabled plugin running until it is set. Echo's settings are optional so it works as soon as it is enabled.
- **Test with the kit.** `EchoCommandsTest` uses `FakePluginHost.withConfig`, calls `applyConfig` before `execute`, checks that a change applies to the next command, and checks that an oversized snapshot is rejected while the previous one stays.
- **Check the manifest against the host.** `EchoSamplePluginContractTest` feeds the sample's real manifest through the host's parser and validator and then through the host's settings form logic, so a settings declaration ONE AI would refuse fails in CI rather than on a user's device.

The user sees these settings on the plugin's **Configure** screen in the Plugins section: ONE AI draws the form, validates every value, and shows your titles and descriptions as plain text. Saving settings never enables the plugin or gives it Bubble access.

### Plugin-requested screens (API 2)

A plugin that needs to show a screen must return a **plugin-created immutable** `PendingIntent` through the API 2 `onLaunch` callback. The creator package and UID must match the imported plugin APK; mutable PendingIntents are rejected on Android 12+. The callback does not launch anything. ONE AI places the request in its own Floating Bubble and shows the package name plus the plugin-supplied label (which users should treat as untrusted text). The user must choose **Allow once** or **Reject**. Approval is consumed once, and ONE AI rechecks both the signing-certificate trust state and the plugin's enabled state immediately before sending. A canceled, stale, disabled or no-longer-trusted request is discarded.

Create the PendingIntent with `FLAG_IMMUTABLE`. For plugins targeting Android 15/API 35 or later, creator background-activity-start privileges are no longer contributed by default; only opt in with `ActivityOptions.setPendingIntentCreatorBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED)` when your plugin genuinely needs to delegate its own BAL privilege. This does not bypass the host's user confirmation or Android's launch restrictions.

On Android 14/API 34+, the host explicitly opts in when it sends the PendingIntent after the user's tap. On Android 16/API 36+, the host uses `MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE`, the least-privilege visible-sender mode. A Floating Bubble overlay is not equivalent to a visible Activity on every Android release/device, so the request can still be blocked by Android; handle that outcome and test on the supported device matrix. Never rely on a plugin's own background activity launch.

## 6. Bubble views (declarative)

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

## 7. Who may call you (host verification)

`hostVerifier()` decides which caller counts as ONE AI. The default trusts only ONE AI's **release** certificates, and that list is **empty until a release signing key exists** (Stage 12), so a release plugin built with the default currently rejects every caller (it fails closed). Options:

- Development: `HostVerifier(allowAnyCertificate = true)` accepts the ONE AI package under any certificate. Use it only in debuggable builds (the sample enables it only when the app is debuggable). **Never ship it in a release.**
- A plugin may also list ONE AI's certificate digests itself: `HostVerifier(trustedCertificates = setOf("<sha256 hex>"))`.

## 8. Trust, updates and removal (user's view)

1. The user installs your APK like any app. ONE AI lists it as *available, not trusted*; nothing is bound or run.
2. On import, ONE AI shows your name, package, version, declared capabilities and your **signing certificate SHA-256**, and the user must confirm it. ONE AI stores that pin. Plugins signed with more than one certificate are not supported.
3. On **every** call ONE AI re-checks the pin. If your certificate changed, the plugin is blocked and an event is logged.
4. **Updates:** an update that adds capabilities, commands, a different capability for a command, Bubble access or dependencies is held: the plugin is taken out of service until the user approves the new declaration, and it then stays disabled until they enable it again. An update signed with a different certificate, or with a lower `versionCode`, is refused.
5. Removing the plugin from ONE AI deletes its pin, commands and Bubble service. Uninstalling the APK is done through Android.
6. **Details:** each trusted plugin's card has a read-only **Details** view built from the declaration the user approved: package and version, the pinned certificate, status (enabled, blocked, update waiting, needs configuration), API compatibility, dependencies and whether they are enabled, each declared capability and whether it always asks the user first, the commands you registered, and whether you offer a Bubble view and whether the user allowed it, then the ten most recent Activity entries that name your package (timeouts, failure codes, refused or approved actions, lines you report), so failures are visible next to the plugin. Names, titles and descriptions in your manifest are shown to users as untrusted text, so keep them short and plain.

## 9. Testing your plugin

- Put behaviour in plain Kotlin and unit-test it on the JVM (see `EchoCommandsTest`).
- `PluginCallRunner` and `ReplySink` are plain classes: run your handler through the real runner with a fake sink to verify bounds and the exactly-one-reply rule.
- Check that your manifest XML and view JSON are acceptable to ONE AI. `EchoSamplePluginContractTest` (in the app's tests) shows how: it feeds both through the host's real parsers.
- Real binding, signature checks and the Bubble need an emulator or device: `docs/EMULATOR_TEST_PLAN.md`.

## 10. Publishing a catalogue document

ONE AI can show a browsable list of plugins from **sources the user adds**: each source is one HTTPS URL that serves one JSON catalogue document. None are preconfigured, and nothing is fetched until the user presses Refresh. A catalogue is a *listing, never authority*: an entry cannot make a plugin installed, trusted, enabled or visible in the Bubble. ONE AI never downloads or installs your APK; "Get" opens your entry's HTTPS page in the user's browser, the user installs the app the normal Android way, and it then goes through the same fingerprint-confirmed import as any plugin (section 8).

A complete, valid example is `app/src/test/resources/catalogue/sample_catalogue.json`. It is parsed by the real `CatalogueParser` in `SampleCatalogueFixtureTest`, so it cannot drift from the host. Copy it and replace the values.

```json
{
  "catalogueVersion": 1,
  "name": "Example plugin source",
  "entries": [
    {
      "id": "com.example.notes",
      "name": "Example Notes",
      "summary": "A sample plugin that stores short notes.",
      "versionName": "1.2.0",
      "versionCode": 12,
      "minCoreApi": 2,
      "targetCoreApi": 3,
      "capabilities": ["commands"],
      "dependencies": [],
      "signerSha256": ["AB:CD:..."],
      "getUrl": "https://plugins.example.com/notes",
      "homepage": "https://example.com/notes"
    }
  ]
}
```

| Field | Rule |
|---|---|
| `catalogueVersion` | Integer `1`. Any other version rejects the whole document. |
| `name` | Document name, 1-60 characters. |
| `entries` | At most 200. |
| `id` | Your package name, the same rules as the manifest. Unique in the document; a repeated id rejects the whole document. |
| `name` / `summary` | 1-60 / at most 200 characters. `summary` is optional. |
| `versionName` / `versionCode` | Semantic version (at most 40 characters) / integer of at least 1. |
| `minCoreApi` / `targetCoreApi` | Integers, `targetCoreApi` not lower than `minCoreApi`. |
| `capabilities` | Optional, at most 12 lowercase identifiers. |
| `dependencies` | Optional, at most 16 plugin ids, no duplicates, not yourself. |
| `signerSha256` | Required, 1-4 SHA-256 certificate digests (hex, with or without colons). |
| `getUrl` / `homepage` | `https` only, no credentials, at most 500 characters. `homepage` is optional. |

Behaviour to design for:

- The whole document is at most **256 KB**. Too large, not JSON, wrong version or duplicate ids: the document is rejected whole and the user keeps the last good listing.
- One invalid entry is **skipped and counted**; the others still load. Nothing is truncated.
- Unknown fields are ignored, so a newer document stays readable by an older ONE AI. Types are strict: a number written as a string makes that entry invalid.
- Redirects are followed only to `https`. There is no cleartext fallback and no certificate pinning in v1.
- The document is **not signed** in v1, and `signerSha256` is advisory. If the installed app's certificate is not among your listed digests, ONE AI shows a warning and does not offer import from the catalogue view. The real trust anchor stays the user comparing the fingerprint of the installed package.
- All catalogue text is shown as untrusted text, with control and bidirectional characters removed. It is never used for identity, commands, permissions or Bubble service names; those come only from the installed, trusted manifest. If the two disagree, the installed manifest wins.
- A listed `versionCode` above the installed one only shows "newer version listed". Updates still arrive through the platform and the existing update review.
- An entry whose `minCoreApi` is above the host's plugin API level is shown as incompatible and cannot be imported from the catalogue view. The sample's third entry demonstrates this and needs updating if the host's API level reaches 4.

Not verified yet: `HttpsCatalogueTransport` has never made a real request, and the catalogue screens have never been rendered on an emulator or device (`docs/EMULATOR_TEST_PLAN.md`, M20-M28).


## 11. Known limits (API level 3)

- No streaming results; one result per request.
- Arguments and results are text only.
- A plugin cannot call back into ONE AI beyond progress lines.
- The AIDL SDK handshake reports wire SDK API **3**. The manifest's `minCoreApi`/`targetCoreApi` independently describe required host features; a plugin that requests the API 2 screen-launch feature must declare `minCoreApi="2"`.
- API 2 adds a plugin-requested `PendingIntent` callback. The host path (identity checks, Bubble confirmation, one-time send) is implemented and unit-tested, but Android's behaviour when ONE AI sends the plugin's `PendingIntent` has **not been verified on a device**. Do not rely on screen requests until it has been.
- API 3 adds host-delivered settings. A setting has no secret type (credentials are not supported), values are text/toggle/whole-number/choice only, and a change reaches the plugin on its next command rather than being pushed to an open connection. Real Binder delivery and behaviour after the plugin process dies are unverified on a device.
