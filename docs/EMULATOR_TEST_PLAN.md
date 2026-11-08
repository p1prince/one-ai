# Emulator / device test plan for the plugin architecture (Stage 11 scaffolding)

**Nothing in this document has been run.** CI proves only that the code compiles, that JVM unit tests pass and that lint is clean. Every item below is *unverified* until someone runs it on an emulator or a physical device and records the result (section 4).

Building any APK, including the debug builds and the instrumentation test APK that these steps need, requires the project owner's explicit authorisation. This plan describes what to run; it does not authorise running it.

## 1. What can be tested, and where

| Evidence | What it proves | Status |
|---|---|---|
| CI (compile + JVM unit tests + lint + androidTest compile) | Logic is right against fakes; files are acceptable to the host's parsers; the instrumented test compiles | Passing (see `DEVELOPMENT_TRACKER.md` for commit and run numbers) |
| Instrumented test `EchoPluginInstrumentedTest` (section 2) | Real discovery, XML resource reading, certificate reading, bound service, SDK caller check, deadline, progress | **Never run** |
| Manual checks (section 3) | Behaviour using the existing Stage 9 screens, the overlay and real user interaction | **Never run; Stage 11 acceptance pending** |

## 2. Instrumented test (available now)

File: `app/src/androidTest/java/com/oneai/app/plugins/EchoPluginInstrumentedTest.kt`. It runs inside the ONE AI debug process and talks to the installed echo plugin.

Prerequisites on the target (emulator or device, API 26 or newer):

1. ONE AI debug build installed (`com.oneai.app`).
2. Echo plugin debug build installed (`com.oneai.sample.echo`, from `samples/echo-plugin`). A debuggable plugin accepts the ONE AI package under any certificate; a release plugin currently rejects every caller until a release signing key exists (`OneAiHost.RELEASE_CERTIFICATES` is empty).
3. If the echo plugin is missing the tests fail on purpose, so a missing prerequisite cannot look like a pass.

Run (each needs owner authorisation because it builds APKs): `gradle :samples:echo-plugin:assembleDebug`, install it, then `gradle :app:connectedCoreDebugAndroidTest`.

What the nine tests check:

- discovery reads the plugin's manifest without running its code;
- import needs the right certificate fingerprint; a wrong one is refused;
- binding is refused if trust fails at bind time;
- handshake and `echo` over the real binder;
- the Bubble view is served and accepted by the host parser; an undeclared service has none;
- invalid input returns the clean `INVALID_ARGUMENT` code;
- progress lines arrive while a command runs;
- a command that outlives the SDK's 30 s deadline is stopped with `TIMEOUT` (this test takes about 30 s);
- the host adapter runs a command through the real channel.

**Assumption to confirm on first run:** instrumentation code executes in the ONE AI process, so the plugin sees the ONE AI package as the caller. If every call fails with `PLUGIN_UNAVAILABLE`, suspect this first (the test setup, not the plugin).

## 3. Manual checks (Stage 9 UI is implemented; run during Stage 11 acceptance)

Record pass/fail per row. "Expected" is what the design requires, not an observed result.

| # | Step | Expected |
|---:|---|---|
| M1 | Install echo plugin; open Plugins | Listed as available, **not trusted**; nothing bound or run (UI exists but is not visually/device verified) |
| M2 | Import; compare the shown certificate fingerprint with `apksigner verify --print-certs` on the APK | They match; user must confirm before it is stored |
| M3 | Enable the plugin; open the Bubble | Bubble still **empty**: plugin enablement does not grant Bubble access |
| M4 | Turn on the plugin's Bubble access | "Echo" service appears; turn it off and it disappears while the plugin stays enabled |
| M5 | Use the Echo view: type text, press Echo, Reverse | Core-drawn result; for `commands` no approval prompt |
| M6 | Press "Wait 3 s" | Progress lines appear in Activity as plugin-originated; result after about 3 s |
| M7 | Force-stop the plugin during a wait (`adb shell am force-stop com.oneai.sample.echo`) | A failed result and an event; ONE AI and the Bubble keep working |
| M8 | Disable the plugin | Its Bubble service and commands disappear; pending approvals are revoked |
| M9 | Reinstall the plugin signed with a different key | Blocked with a signature-mismatch event; not usable until the user removes and re-imports it |
| M10 | Install an update that adds a command | Held for consent showing the new command; stays disabled after approval until enabled again |
| M11 | Install an older `versionCode` over a newer one | Refused |
| M12 | Uninstall the APK; restart ONE AI | Detected as not installed; plugin out of service |
| M13 | Declare a sensitive capability (for example `files`) in a test plugin and press its button | Core-drawn approval showing the exact arguments; "Allow once" is single-use |
| M14 | A third-party app tries to bind the echo plugin's service | Rejected by the SDK caller check |
| M15 | Restart the device, then open the Bubble | Trusted plugins are restored; untrusted ones stay out of service |
| M16 | Open the echo plugin's **Configure** screen; set Prefix to `>> ` and turn Uppercase on; Save; run Echo with `hi` | Reply is `>> HI`; enabled state and Bubble access did not change |
| M17 | In Configure, type a 41-character prefix and press Save | Inline error naming Prefix, nothing saved; the dialog stays open |
| M18 | In Configure, press Reset to defaults, then run Echo with `hi` | Reply is `hi`; fields show the defaults |
| M19 | With a test plugin variant that declares a required setting with no default, try to enable it, then (after setting it) enable it and update to a variant adding a second required setting | Enabling is refused naming the setting; after the update the plugin stays enabled but does not run and shows "Needs configuration" until saved |
| M20 | Fresh install; open Plugins and look at the CATALOGUE section | A "No catalogue sources" card is shown, Refresh is disabled, and no network request is made (check with a proxy or `adb logcat`) |
| M21 | Open Sources; try `http://example.com/c.json`, then a valid `https://` URL | The http URL is refused with a message; the https source is added; still no request until Refresh |
| M22 | Host `app/src/test/resources/catalogue/sample_catalogue.json` over HTTPS; add it and press Refresh; then rotate the screen and reopen Plugins | Three rows, all "Not installed"; the third shows an incompatible warning; rotating or reopening does not fetch again |
| M23 | Point a source at an unreachable host, at invalid JSON, at a document over 256 KB, and at a URL that redirects to `http://` | Each refresh fails with a safe message, "The last good listings are kept." shows, and Core and installed plugins keep working |
| M24 | Open an entry and press **Get** | The entry's validated https page opens in the browser; nothing is downloaded or installed by ONE AI |
| M25 | Install the matching plugin APK the normal way, press Refresh, open the entry | "Installed, not imported"; **Review and import** opens the same fingerprint-confirmed dialog as M2 and the fingerprint matches `apksigner` |
| M26 | Serve a document whose `signerSha256` does not list the installed plugin's certificate | A prominent signer warning and no import offered from the catalogue view; the on-device import path (M1-M2) is unchanged |
| M27 | Serve an entry with a `versionCode` above the installed one, and an entry with a `minCoreApi` above the host's API level | "A newer version is listed" with no change to enabled state; the second entry is incompatible and cannot be imported |
| M28 | Remove the source in Sources | Its listing disappears; installed and imported plugins, their enablement and Bubble access are untouched |
| M29 | Import and enable the echo plugin; on its card press **Details** | Identity and version, the pinned SHA-256 certificate (equals the M2 `apksigner` value), "Trusted and ready.", "Compatible with this ONE AI.", no dependencies, `commands: no extra prompt`, the three commands, and a Bubble line saying it is not allowed yet |
| M30 | With Details closed, switch Enabled and Show in Floating Bubble off and on; reopen Details each time | The Status and Floating Bubble lines follow the switches; opening Details never changes either switch |
| M31 | With the M13 test plugin variant (declares `files`), open Details | `files: asks you each time, and each approval is single-use`, matching the approval prompt in M13 |
| M32 | With a held update (M10), open Details and then Review update | Details shows the declaration you approved plus "off until you review"; only Review update shows the new access |
| M33 | Run the echo plugin's `wait` command with a short host timeout (or stop the plugin app mid-call), then open Details; also open Details of a second plugin | Recent activity lists the timeout or failure line (`[package] Timed out ...` or `Failed (code) ...`) with its time, newest first, at most 10; the second plugin shows none of those lines, and a plugin whose id extends another's is not mixed in |
| M34 | Open Activity after some AI requests, a plugin enable/disable and a rejected action; tap each filter chip (All, AI, Commands, Plugins, Approvals, Errors), then All again, then leave the screen and return | Each chip shows only its own events and the count line reads "N of M events shown"; All shows everything (older entries without a kind appear only under All); a chip with no events shows "No ... events"; nothing is deleted; the chip resets to All on return; chips scroll sideways on a narrow phone |
| M35 | Save an AI key, then make it unreadable (for example restore app data from a backup onto a device without the Keystore key, or clear the app's Keystore entry with a debug tool); open Settings and send a message from Home | Settings shows "A saved key could not be read. Save it again." in the AI provider row and the credential status line (not "not saved"); Home reports the same sentence; the key text never appears; "Remove AI provider key" is still enabled and removes it; saving a new key restores READY |
| M36 | In the Bubble, open the echo plugin's view and press a button whose command is `wait` (approve it if asked) | A panel reads "Running <the command's title>..." with Cancel and Close panel; it contains no text from the plugin; when the command finishes the result replaces it |
| M37 | Repeat M36 and press **Cancel** while it runs, then wait 40 seconds | A "Cancelled" note says approving again is needed; no result appears later; the plugin's request is stopped (echo `wait` no longer finishes); pressing the button again asks for approval again |
| M38 | Repeat M36 and press **Close panel** while it runs, then wait for it to finish | The command is not cancelled; its result appears in the Bubble when it finishes |
| M39 | Run `wait` with a plugin that exceeds the 35-second host deadline (or stop the plugin app mid-call) | The Running panel is replaced by a failure or timeout message, never left on screen, and the Bubble stays usable |
| M40 | From the Command Manager, run an extension command that needs approval and press **Approve and run** | The same Running panel with the command title and Cancel appears instead of an empty screen, then the result |
| M41 | With the echo plugin imported and **Enabled** but Bubble access **off**, open Plugins and Home | The plugin's card shows **Open**; Home shows a "YOUR PLUGINS" list with the plugin; Floating Bubble still does not offer it |
| M42 | Turn the plugin off, look at Plugins and Home, then turn it on again (Bubble access still off) | **Open** and the Home entry disappear while it is off and come back when it is on; turning Bubble access on or off never changes either |
| M43 | Tap **Open** | A plugin screen opens as a sub-page with the plugin's view, a note that the screen is supplied by the plugin, and Back; Back or any bottom-navigation item closes it and the five tabs are unchanged |
| M44 | In the opened screen press a button whose command needs approval | A host dialog shows the exact values; **Allow once** runs it and shows Running with Cancel, then the result; **Cancel** in the dialog runs nothing |
| M45 | Press the echo `wait` button, then press **Cancel**; repeat and instead leave the screen while it runs | Cancel shows a "Cancelled" note and no later result; leaving the screen cancels the command and nothing appears afterwards |
| M46 | Use a plugin with a required setting that is not filled in (a variant of the echo sample) | It cannot be enabled, so no **Open** and no Home entry appear until the setting is saved and the plugin is enabled |
| M47 | Open the plugin in the app while the Floating Bubble is also showing the same plugin; run a command in each | Both work independently; neither blocks, cancels or shows the other's result |
| M48 | With a plugin variant that requests a screen (API 2) from a command run in the opened app screen | An in-app "Plugin screen request" dialog shows the package, the requested screen and a warning; **Allow once** sends the launch (Android may still block it: record what happens); **Reject** sends nothing |
| M49 | Repeat M48 but leave the screen without answering; then request a screen from a different plugin while a plugin screen is open | The unanswered request is rejected and does not appear later in the Bubble; the other plugin's request still goes to the Bubble, not over the open screen |

M13, M14, M19, M31, M46, M48 and M49 need a small extra test app or plugin variant that does not exist yet. M20-M28 need an HTTPS server you control to host catalogue documents (the sample and variants of it) and a second device-installed plugin APK for M25-M27; none of these has been run.

## 4. Recording results

For every run record: date, device or emulator model and Android API level, ONE AI commit and echo-plugin commit, which section and test, pass/fail, and the log or screenshot. Keep the four evidence levels apart in `DEVELOPMENT_TRACKER.md` (CI / unit / emulator / device) and never describe an item as verified on a device unless that exact run happened.
