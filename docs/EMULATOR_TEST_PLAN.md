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

Run (each needs owner authorisation because it builds APKs): `gradle :samples:echo-plugin:assembleDebug`, install it, then `gradle :app:connectedDebugAndroidTest`.

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

M13 and M14 need a small extra test app or plugin variant that does not exist yet.

## 4. Recording results

For every run record: date, device or emulator model and Android API level, ONE AI commit and echo-plugin commit, which section and test, pass/fail, and the log or screenshot. Keep the four evidence levels apart in `DEVELOPMENT_TRACKER.md` (CI / unit / emulator / device) and never describe an item as verified on a device unless that exact run happened.
