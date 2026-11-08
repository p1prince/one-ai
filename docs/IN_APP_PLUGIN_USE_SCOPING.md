# Using plugins inside the app - scoping

- **Date:** 2026-11-08. **Method:** read-only source inspection of exact head `af9883a` (CI-built APKs from `b503572` and `941734d` were installed by the owner). No source, manifest, build file or test was changed.
- **Evidence level:** source inspection plus one owner report from a physical tablet (screenshots, 2026-11-08): the echo sample plugin installs, imports, enables, and works from the Floating Bubble, but "you can only use the plugin on the Bubble but not the app". Nothing else was exercised on the device.
- **Scope:** generic Core and the generic plugin contract only. No capability plugin is involved (D4 stands). This is the first of two steps toward plugins feeling part of ONE AI; the second (the AI itself calling plugin commands) is `docs/AI_PROVIDER_AND_TOOLS_SCOPING.md` and is not part of this scope.
- **Files read:** `security/PluginActionButton.kt`, `bubble/BubbleContract.kt`, `bubble/ExternalBubbleProvider.kt`, `bubble/FloatingBubbleService.kt` (run flow), `plugins/ExternalPluginRegistrar.kt`, `AppServices.kt` (registry and provider registration), `MainActivity.kt` (Plugins page, Command Manager page, Home), `commands/CommandFramework.kt`, `plugins/PluginLaunchApprovalController.kt` (attach point).

## 1. What exists

- **Enabled is enough for the host to know the plugin.** When a trusted plugin is enabled, the registrar registers its adapter with the runtime and its declared commands in the shared `CommandRegistry`, and `AppServices` registers its `ExternalBubbleProvider` in `BubbleProviders.registry`. `CommandRegistry` treats a command as available when its plugin is **enabled**; Bubble access is not part of that rule. Bubble access only decides whether the Bubble panel lists the service.
- **The plugin's screen is already UI-neutral data.** `ExternalBubbleProvider.loadView` returns a `BubbleView` of `Label`, `TextInput` and `Button` elements, and a button returns a `BubbleAction.PluginCall` carrying the exact action, approval text and outcome titles. Nothing in those types is Android-View specific; only `FloatingBubbleService` draws them.
- **The Bubble flow is the only complete run surface:** invoke, approval panel for sensitive capabilities, one-use ticket, Running panel with Cancel (slices B1-B3), result.
- `PluginActionButton` (Compose) can invoke an action and show an approval dialog, but it has **no call site**, has no Running state or Cancel, and cannot carry arguments from text inputs.

## 2. Gaps

- **A1 - no in-app entry point.** The Plugins card offers Enabled, Show in Floating Bubble, Details, Configure and Remove; nothing opens or runs the plugin. The in-app Command Manager lists only saved prompt commands. Home sends text only to the AI model.
- **A2 - no in-app renderer for a plugin's declarative screen.** Only the Bubble draws it.
- **A3 - no in-app run flow** with approval, Running and Cancel, and result display.
- **A4 - plugin-requested screens (API 2).** The launch-approval presenter is attached only by the Bubble service. A launch requested by a command started from the app would, per tracker 9.4c, be queued until the Bubble next starts, which would surprise the user. (Not re-read in this pass; check before building.)
- **A5 - the Command Manager cannot be the entry point yet.** A command declares no parameters (D11 / D-U), so a no-argument list would run `echo` or `reverse` with no text. The plugin's own screen is where input is collected.
- **A6 - wording and setting separation.** "Show in Floating Bubble" must keep meaning Bubble only. In-app use must follow Enabled, never Bubble access.

## 3. Options

- **E1 (recommended): an in-app plugin screen.** An **Open** button on each enabled, trusted plugin card that has a screen opens a Compose page rendering the same `BubbleView` through the same provider, with the same approval, a Running state with Cancel, and the result shown on the page. No manifest, SDK or API-level change; a plugin works in the app and the Bubble from one declaration. Reuses `BubbleServiceProvider`, `BubbleRunState`, `BubbleRunning` and the existing approval dialog.
- **E2: E1 plus a Home entry.** A "Plugins" strip on Home listing enabled plugins that have a screen, each opening its in-app screen. This is what makes plugins feel part of the platform rather than something buried in Plugins. Small once E1 exists.
- **E3: list extension commands in the Command Manager.** Not now (A5); revisit after command arguments exist (D-W in the AI scoping).
- **Not in this scope:** the AI choosing plugin commands, provider profiles.

## 4. Proposed slices (if approved)

1. **C1 - pure call flow.** A small JVM-tested state machine for one plugin call (idle, running, awaiting approval, done, cancelled) shared by the in-app screen; the Bubble keeps its current, working code (no refactor of working infrastructure).
2. **C2 - in-app plugin screen.** Compose renderer for `BubbleView`, loading, error state, Running with Cancel, approval dialog, result.
3. **C3 - entry points.** **Open** on the Plugins card (shown only when enabled, trusted, has a screen, and nothing required is unconfigured), plus the Home strip (E2) if chosen.
4. **C4 - plugin-requested screens.** Attach an in-app presenter, or refuse such launches with a clear message when started from the app (D-AB).
5. **C5 - docs and tests.** Developer guide and SDK note that a plugin's screen is shown in the app and the Bubble; emulator rows; source guards (the in-app screen never reads Bubble access, never hides a run in silence, shows plugin text only as untrusted text).

Each slice is one commit plus a tracker record with its CI run.

## 5. What this does not do

- It does not change trust, enablement, approval policy or the Bubble's behaviour.
- It does not give plugins any new capability or let them draw their own UI; the host still draws every element.
- It does not make the AI call plugins.

## 6. Owner decisions needed

- **D-AA:** E1 only, or E1 plus the Home strip (E2). Recommended: E1 and E2.
- **D-AB:** plugin-requested screens started from the app: attach an in-app presenter (recommended) or refuse them in the app with a clear message.
- **D-AC:** keep extension commands out of the Command Manager until arguments exist (recommended).
- **D-AD:** confirm that in-app use depends on Enabled only and Bubble access stays Bubble-only (recommended).

## 7. Not verified

Nothing here has run. Whether the in-app page renders the echo screen the same as the Bubble, how an approval dialog reads inside the app, keyboard behaviour with text inputs on the tablet, and the API 2 launch path from the app are unknown. The A4 statement relies on the tracker's description of the launch controller.
