# Bubble interaction scoping - argument entry and in-progress status

- **Date:** 2026-11-08. **Method:** read-only source inspection of exact head `9d5b069cf65dc7cb847e72c0e4bb464e42abbb12` (Android Validation #650 passed on `c76324b`; `9d5b069` is docs only). No source, manifest, build file or test was changed.
- **Evidence level:** source inspection only. Nothing was run on an emulator or device, and the Bubble overlay has never been rendered.
- **Scope:** the open Stage 6/7 item "the Bubble does not yet provide general argument-entry forms or live in-progress status". Generic Core only; no capability plugin is involved (D4 stands).
- **Files read:** `bubble/FloatingBubbleService.kt`, `bubble/BubbleContract.kt`, `bubble/ExternalBubbleProvider.kt`, `plugins/ExternalViewParser.kt`, `plugins/ExternalPluginAdapter.kt`, `plugins/ExternalPluginManifest.kt` (command declaration), `commands/CommandFramework.kt`, the `IPluginCallback` AIDL, `docs/PLUGIN_DEVELOPER_GUIDE.md`.

## 1. What already works (the tracker wording overstated the gap)

- **Argument entry exists for plugins that offer a Bubble view.** A plugin's declarative view can contain text inputs and buttons; a button's arguments can come from literals or from the typed inputs (`"@key"`), all bounded and validated, and fail-closed (`ExternalViewParser`). The Bubble renders them (`BubbleElement.TextInput` and `Button`), and the approval panel shows the exact values that will be sent (`ExternalBubbleProvider.reviewText`). The interaction stays inside the Bubble.
- **Plugin calls are bounded and cancellable.** The host waits at most 35 s (`DEFAULT_TIMEOUT_MS`), and cancelling the calling coroutine cancels the request in the plugin (`channel.cancel`).
- **The Command Manager shows "Running <title>..."** while a command it started is running.

## 2. Genuine gaps

**G1 - No feedback while a plugin runs, in the main paths (defect-level UX, source evidence only).**
- `runPluginCall` (a button in a plugin's Bubble view) invokes the plugin and shows nothing until the result arrives.
- `showApproval` and `showCommandApproval` call `hidePanel()` after "Allow once" / "Approve and run" and then wait for the result, so the Bubble shows only the icon for up to 35 s with no sign that anything is happening and no way to stop it.
- Only the Command Manager list path shows a "Running..." label.

**G2 - The Command Manager runs extension commands with no arguments.**
- `renderCommandManager` calls `runRegisteredCommand(box, command.id, command.title)` with empty arguments.
- A command declaration is `name`, `title`, `description`, `capability` only: it declares **no parameters**, so the host cannot know what a command needs and cannot draw a generic form. Arguments therefore reach a plugin only through its own declarative view (section 1).
- The Command Manager approval panel says "Allow <title> from <plugin id> to run?" and shows no argument review. That is harmless today because the arguments are always empty, but it would hide values if arguments were ever added there.

**G3 (not a gap in this scope) - plugin progress lines do not reach the Bubble.** `onActivity` lines go to Activity (and are cleaned there). Relaying them live would need a change to the core `PluginActionExecutor` contract and shows plugin-controlled text in an overlay.

## 3. Options

**Progress (G1)**
- **P1 (recommended):** one host-drawn "Running <command title>..." panel with a **Cancel** button, used by all three execution paths. It uses only the command title (cleaned), never plugin text. Cancel cancels the call (the adapter already cancels the plugin request); the result replaces the panel; closing the panel does not cancel (unchanged). A small pure helper holds the run token and job so a late result cannot redraw a panel the user has left, and it is JVM-testable.
- **P2:** P1 plus live relay of plugin progress lines. Needs a core contract change and shows untrusted text in the overlay. Not recommended now.

**Arguments (G2)**
- **A1 (recommended):** no manifest or API change. Document that a command that needs input is exposed through the plugin's declarative Bubble view, and that the Command Manager runs a command as declared. Add the argument review line to the Command Manager approval panel only if arguments are ever passed there (not needed now).
- **A2:** add optional `<arg name label required>` declarations to a command (plugin API level 4) and draw a host form in the Bubble and Command Manager. Touches the manifest parser and validator, update-review diff, test kit, Echo sample, developer guide and approval review. Large; only worth it if you want generic argument forms outside a plugin's own view.

## 4. Proposed slices (P1 + A1, if approved)

1. **B1 - run state helper:** pure `BubbleRunState` (begin, finish, cancel, token check) with JVM tests (stale result ignored, cancel idempotent, a second run replaces the first).
2. **B2 - wire it in:** the "Running..." panel with Cancel in `runPluginCall`, `showApproval` and `showCommandApproval`; tests including source guards that the panel text comes from the command title only and that no execution path hides the panel without showing it.
3. **B3 - docs:** `PLUGIN_SDK.md` Bubble contract section and developer guide (what the user sees while a command runs, the 35 s limit, Cancel, where arguments come from); emulator plan rows for the running panel, Cancel, a 35 s timeout and approval-then-run.

Each slice is one commit plus a tracker record with its CI run. Nothing changes trust, enablement, Bubble access or approval behaviour; the approval still happens before the run and a cancelled run still has consumed its one-use approval (the user must approve again).

## 5. Owner decisions needed

- **D-T:** progress P1 (recommended) or P2.
- **D-U:** arguments A1 (recommended) or A2.

## 6. Not verified

No runtime behaviour: overlay rendering, whether the panel survives a configuration change or a service restart, and whether Cancel reaches a real plugin over a Binder (the test kit's fake host covers cancellation, a real device has not). The 35 s figure is read from the source, not measured.
