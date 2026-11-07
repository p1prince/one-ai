# ONE AI Plugin SDK — Manifest v1

## Purpose

The Plugin SDK defines a safe, versioned contract for declaring optional ONE AI capabilities. A manifest is metadata, not executable code. The Android shell does not download, load, reflectively instantiate, or execute arbitrary plugin classes.

## Manifest shape

A plugin manifest contains:

- `manifestVersion`: contract version; currently `1`.
- `id`: unique reverse-domain identifier, such as `dev.example.notes`.
- `name`: display name (1–60 characters).
- `version`: semantic version in `MAJOR.MINOR.PATCH` form, optionally followed by a prerelease suffix.
- `description`: up to 500 characters.
- `capabilities`: a list of supported capability identifiers.
- `dependencies`: optional list of other plugin IDs required by this plugin.

Example:

```json
{
  "manifestVersion": 1,
  "id": "dev.example.notes",
  "name": "Notes Connector",
  "version": "1.0.0",
  "description": "A declarative connector descriptor.",
  "capabilities": ["commands", "internet"],
  "dependencies": []
}
```

The example is a schema illustration only. A descriptor does not itself implement network access or command execution.

## Supported capability identifiers

- `commands`
- `internet`
- `screen_capture`
- `microphone`
- `audio`
- `device_actions`
- `accessibility`
- `automation`
- `files`
- `app_launch`
- `device_actions`

Declaring a capability does not grant Android permission or make the integration operational. A future integration must supply a reviewed adapter, explicit runtime permission flow, and capability-specific policy before the capability can perform actions.

## Validation and lifecycle

The current validator normalizes IDs and names, checks the manifest version, validates semantic version syntax, enforces description and capability limits, requires at least one supported capability, rejects unknown capabilities, removes duplicate capability declarations, limits dependencies to 16, rejects duplicate/malformed/self dependencies, and returns a normalized manifest.

Installation creates a disabled plugin record. Enabling a plugin requires all declared dependencies to be installed and enabled. Disabling a dependency is blocked while an enabled dependent exists; removing a dependency is blocked while any dependent remains installed. Errors are returned to the Plugin Centre rather than silently ignored.

Existing plugin records remain readable: missing manifest version defaults to v1 and missing dependencies default to an empty list.

## Execution boundary

Manifest v1 intentionally has no executable entry point, arbitrary code field, script field, downloaded DEX/JAR loader, or dynamic class-loading mechanism. Real integrations will be implemented as trusted in-app adapters or explicitly permissioned external integrations. Plugin descriptors remain inert until such an adapter is registered and passes the platform's security checks.


## Trusted adapter contract

The core now defines a generic `PluginAdapter` contract and `PluginRuntime` registry. A trusted adapter exposes the same validated manifest as its installed plugin record and implements a suspendable action handler. Registration is rejected when the plugin is missing, the manifest version differs, declared capabilities do not match, or another adapter is already registered for that plugin.

Every invocation must include an action ID, declared capability, and human-readable action description. The runtime verifies that the plugin is still installed and enabled, bounds the input map, then calls the central `PluginActionGateway` immediately before adapter execution. Low-risk actions may proceed; sensitive actions return an approval-required result until the UI obtains an affirmative user decision and supplies a short-lived, one-use ticket bound to that exact action. Rejected actions never reach the adapter. Cancellation is propagated; unexpected adapter failures are returned as a generic failure result rather than exposing exception details.

Adapters are trusted integration code, not arbitrary third-party code loaded from a manifest. Registration must be performed by reviewed app integration code. The runtime is exposed through the shared core store. Plugin disable/removal unregisters the adapter and revokes pending approval tickets. The registry remains restricted to reviewed in-app adapters; the Screen Intelligence catalogue entry now has a trusted adapter path under Stage 8 verification. Other catalogue entries remain inert until their own adapters are implemented.

## Returning results to the Floating Bubble

Trusted in-app feature modules can return a completed text result to the running Bubble service using the internal `BubbleResultHandoff` contract. Send an explicit intent to `FloatingBubbleService` with action `ACTION_SHOW_RESULT` and the `EXTRA_TITLE` / `EXTRA_CONTENT` fields. The service validates and bounds both fields, then renders the result inside its independent overlay; it does not redirect the user to `MainActivity`. Invalid, blank or oversized results are ignored. The service is not exported, so this is an in-app integration boundary, not an external-app API.

## Screen capture and selected-image hand-off

The Stage 7 capture framework now provides a user-initiated path from the Bubble:

1. The Bubble displays an explicit ONE AI “Allow once” confirmation for the exact screen-capture action. The core action gateway issues and immediately consumes a short-lived, one-use approval ticket.
2. A dedicated Activity launches Android's native MediaProjection consent prompt. Denial cancels capture.
3. A non-exported foreground service starts a fresh MediaProjection session, captures one frame, then releases the virtual display, ImageReader and projection.
4. A separate full-screen region selector displays the captured frame outside the Bubble. The user drags a rectangle; selection coordinates are mapped back to source pixels and cropped.
5. The cropped image is stored in private app cache and published through ScreenCaptureArtifactStore. The artifact expires after ten minutes and consumeLatest(context) returns it once; callers should delete the returned file after processing.
6. A bounded result message and private artifact reference are returned to the Bubble. The Bubble does not display the screenshot itself.

Stage 8 adds a trusted Screen Intelligence adapter and an optional OpenAI-compatible vision provider. The adapter consumes the selected image artifact once, validates its private path and format, and deletes it after processing. The Bubble requires a second explicit one-time approval before sending the image to the configured provider over HTTPS; the screen-capture approval does not authorize upload. The configured model must support image input. The end-to-end flow remains subject to CI and real-device verification.


## Web Research integration (Stage 8)

The Web Research catalogue entry now has a trusted in-app adapter backed by a provider-neutral `WebSearchProvider` contract and a Brave Search API connector. The connector uses HTTPS, sends its API token in the `X-Subscription-Token` header, bounds response bodies to 1 MiB, accepts only HTTP(S) result links without embedded credentials or fragments, and limits returned results. Its credential is stored in a separate Android Keystore-encrypted slot (`brave_search`) and does not replace the primary AI provider key.

The Floating Bubble provides a search field. Each search is routed through `PluginRuntime` as the `internet` capability and requires a separate, exact-action, one-use approval before the query is sent to Brave Search. The provider credential must first be configured in Settings, and the Web Research plugin must be installed and enabled. CI and end-to-end device validation are still pending; do not treat the integration as release-verified yet.


## Voice Interface integration (Stage 8)

The optional Voice Interface plugin uses a trusted in-app adapter registered against the existing `one.voice.interface` manifest. One Bubble action requires an exact one-use `microphone` approval. Only after approval does the app open its non-exported capture Activity, request Android `RECORD_AUDIO` permission at runtime, and launch Android's speech-recognition UI. One recognized transcript is passed through the shared AI orchestrator; the response is spoken using Android TextToSpeech and returned to the Bubble. The capture UI remains outside the Bubble. Voice recognition availability and text-to-speech behavior depend on the Android device's installed services and must be tested on a physical device.


## App Control integration (Stage 8)

The optional App Control plugin uses the `app_launch` capability to enumerate launcher-visible applications and open a user-selected app through its exported Android launcher activity. The app catalog uses a launcher-intent `<queries>` declaration rather than broad `QUERY_ALL_PACKAGES` visibility. Every launch is routed through `PluginRuntime` and requires an exact-action, one-use approval naming the selected app. This integration does not sign in, inspect private app data, automate screens, or bypass another app's authentication. In-app automation remains a separate, permission-sensitive integration.


## App Control integration (Stage 8)

The optional App Control adapter uses the `app_launch` capability to list launcher-visible applications and open a user-selected app through its exported Android launcher activity. Package visibility is limited to launcher activities; the app does not request broad `QUERY_ALL_PACKAGES` access. Each launch requires an exact-action, one-use approval. This does not sign in, inspect private app data, automate in-app screens or bypass another app's security.

## Device Control integration (Stage 8)

The optional Device Actions adapter uses the `device_actions` capability to open a fixed allowlist of Android Settings destinations: Wi-Fi, Bluetooth, Sound & vibration, Display and Location. Each action requires an exact-action, one-use approval before an Android Settings intent is launched. Android owns the actual setting controls and any confirmation. The adapter does not silently toggle restricted settings, change secure settings or accept arbitrary intent actions. Availability and exact Settings screens can vary by Android version and device manufacturer.

## Command framework (Stage 6)

Commands are registered with a `CommandRegistry` and run by a `CommandExecutor`. Every command has an ID of the form `owner/name`:

- Core commands: `core/<name>` (for example `core/run_saved`), registered by the app with a handler.
- Extension commands: `<pluginId>/<name>` (for example `dev.example.notes/add_note`). The owner prefix is the plugin's reverse-domain ID, so an extension can never replace a core command or another plugin's command.

`name` uses lowercase letters, digits and underscores (max 40). Titles are 1–80 characters, descriptions up to 300. An extension may register at most 50 commands.

### Registering an extension command

`registerExtension(pluginId, name, title, description, capability)` registers a command on behalf of a plugin. `capability` is required and must be one of the supported capability identifiers; it must also be declared in the plugin's manifest, because execution goes through the plugin runtime and its gateway, which rejects undeclared capabilities.

### Execution and permissions

`CommandExecutor.execute(commandId, args, approvalTicket)` returns a `CommandResult` with a status: `COMPLETED`, `FAILED`, `REJECTED`, `AWAITING_APPROVAL` or `NOT_FOUND`. Core commands run their handler directly. Extension commands are never run by the executor itself: they are delegated to `PluginRuntime.invoke`, so the plugin must be installed, enabled and registered, the capability policy applies, and sensitive capabilities return `AWAITING_APPROVAL` until the UI obtains a one-use ticket for the exact action (`CommandExecutor.actionFor` returns that action) and calls `execute` again with the ticket.

Arguments are limited to 32 entries (keys up to 80 characters, values up to 4,000). Output titles are limited to 120 characters and content to 20,000. Exceptions from handlers or plugins are contained and returned as a `FAILED` result with a safe message; cancellation propagates. The activity log records only the command ID and outcome, never arguments or output.

### Availability and lifecycle

A command of a disabled or uninstalled plugin is hidden from `list()` and cannot run. Disabling a plugin keeps its registration so re-enabling restores its commands. Removing a plugin calls `unregisterOwner`, which deletes its commands; `core` commands cannot be unregistered.

Status: the registry and executor are implemented and unit-tested. Plugin-side registration from an externally installed plugin depends on the extension model in `docs/DECISIONS.md` (D1) and is part of Stage 8.

## Floating Bubble contract (Stage 7)

The Bubble is a core shell. It owns the overlay, dragging, size and position preferences, the core command service and one shared approval/result flow. It contains no capability-specific code (a unit test enforces this). Everything else is contributed through `BubbleServiceProvider`:

- `serviceId` and `pluginId`: the Bubble service and the plugin that owns it. A plugin can have at most one Bubble service.
- `title`: shown in the service list and panel header.
- `view(host)`: returns a declarative `BubbleView` of `Label`, `TextInput` and `Button` elements. A button's handler receives the current text-input values and returns a `BubbleAction` (or null).
- `ensureReady(runtime, host)`: prepares the plugin before an action, for example registering its adapter.
- `followUp(imagePath, host)`: optional action offered when a result carries a private image.
- `onHostStopped()`: release pending work when the Bubble stops.

Actions:

- `PluginCall`: runs a plugin action through the plugin runtime. The Bubble first invokes it without approval; if approval is required it shows the approval panel (`BubbleApproval`: title, message, optional review text, confirm label). Only the user's confirmation produces the one-use ticket for that exact action. Outcomes are titled by `BubbleOutcomes`.
- `GatedHostAction`: an action the gateway gates but the contributor performs itself after approval.
- `Message`: shows a message immediately, for example for input validation.

Availability follows two independent user settings. A service appears in the Bubble only when its plugin is installed and enabled **and** the user has enabled that service's Bubble access in Settings. The Bubble starts empty and never enables either setting itself. Disabling a plugin removes its Bubble service; disabling only Bubble access leaves the plugin enabled.

Contributors are registered in `BubbleProviders.registry` from the application composition root (`OneAiApplication`), which runs on every process start, including when Android restarts the Bubble service. The six capability UIs that ship today are expressed through this contract in `com.oneai.app.builtin` as an interim step; they are slated to become separately installed plugins (Stages 8–9).

## Independently installed plugins (manifest v2)

Plugins built as separate signed APKs (decision D1) are documented in `docs/PLUGIN_DEVELOPER_GUIDE.md`, with a working reference plugin in `samples/echo-plugin`. Everything above describes manifest v1 and the in-app adapters, which stay readable for migration. A trusted external plugin is wrapped by `ExternalPluginAdapter` so it uses the same `PluginRuntime`, gateway, command registry and Bubble contract described in this file.
