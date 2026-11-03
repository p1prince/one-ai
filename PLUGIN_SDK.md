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

Adapters are trusted integration code, not arbitrary third-party code loaded from a manifest. Registration must be performed by reviewed app integration code. The runtime is exposed through the shared core store. Plugin disable/removal unregisters the adapter and revokes pending approval tickets. The registry is a contract foundation: the current Plugin Centre and Bubble do not yet register feature adapters, and no catalogue entry should be interpreted as a working integration.
