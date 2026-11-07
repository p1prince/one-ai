# Stage 9 — Generic per-plugin configuration contract: design proposal

**Status: APPROVED by the owner on 2026-11-07 (decision D5 in `docs/DECISIONS.md`): D-A to D-D all resolved as recommended. Implementation proceeds in slices 9.6a–9.6e.**
Date: 2026-11-07. Scope: Stage 9 gap 3 in `docs/STAGE_2_AUDIT.md` §12.10 ("no generic per-plugin configuration contract, persistence model, or configuration screen"). Governed by `docs/PROJECT_CONTINUATION_RULES.md`: generic and capability-neutral; no capability-specific settings in Core.

## 1. Problem

A plugin author has no supported way to let the user configure a plugin. Today the manifest (`ExternalPluginManifest`) declares identity, capabilities, commands, Bubble and dependencies only; `PluginRecord` and `PluginStore` store no settings; the AIDL contract (`IOneAiPlugin`) has no channel to pass settings to the plugin; the Plugins screen has no configuration view.

## 2. Principles

- **Declarative, like the manifest.** The plugin declares settings; the host renders, validates and stores them. Plugin code does not run, and plugin UI is never drawn, in ONE AI.
- **The host owns the values.** Stored per plugin, validated against the declared schema on every write and read, removed with the plugin.
- **Configuration is not authority.** Settings never grant capabilities, bypass approvals, or enable Bubble access. Those stay separate (Installed / Trusted / Enabled / Bubble access).
- **Bounded and fail-closed.** Oversized or invalid values are rejected, never truncated.
- **Additive.** Plugins that declare no settings behave exactly as today.

## 3. Manifest: optional `<settings>` element

```xml
<settings version="1">
  <setting key="greeting_prefix" type="text"   title="Prefix" description="Added before each reply" default="" maxLength="40" />
  <setting key="loud"            type="toggle" title="Uppercase replies" default="false" />
  <setting key="repeat"          type="number" title="Repeat count" default="1" min="1" max="5" />
  <setting key="mode"            type="choice" title="Mode" default="plain" required="true">
    <option value="plain" label="Plain" />
    <option value="fancy" label="Fancy" />
  </setting>
</settings>
```

Types in v1: `toggle`, `text`, `number` (integer), `choice`. `required="true"` means a value (or a default) must exist. Unknown attributes/elements are ignored (same rule as the rest of the manifest), so an older host still reads the manifest; it simply shows no settings.

Bounds (new constants beside `ExternalManifestValidator`): max 24 settings; `key` matches `[a-z][a-z0-9_]{0,39}` and is unique; `title` 1–60; `description` ≤ 200; `text` value ≤ 500 (declared `maxLength` may only lower it); `number` within declared `min`/`max` and a signed 32-bit range; `choice` 2–16 options, option value `[a-z0-9_]{1,40}`, label 1–40; every default must itself pass validation. A full stored snapshot is capped at 8 KB; to guarantee a full snapshot always fits, the total declared `text` capacity across a schema is limited to 2,000 characters and total options to 96.

Not in v1: secrets/credentials, free-form JSON, lists, file pickers, plugin-drawn screens (see open decision D-A).

## 4. Storage

- New persistence-only interface `PluginConfigStore` (kept out of `PluginStore` so that interface does not grow): `values(pluginId)`, `save(pluginId, schemaVersion, values)`, `remove(pluginId)`.
- Production implementation in `OneAiStore` (SharedPreferences JSON, same pattern as `plugins`), keyed by plugin ID.
- Writes go through a pure validator `PluginConfigValidator.validate(schema, values)`; the store never accepts unvalidated data. Corrupt stored data is discarded to defaults and logged.
- `PluginManager.remove` also removes the plugin's configuration.
- Activity logging records **which keys changed, never the values** (values may be personal).

## 5. Updates and schema change

On update, `ExternalPluginCenter` reconciles stored values with the new schema: keep a value only if its key and type still exist and it still validates; otherwise reset to the default; drop keys no longer declared. Each reset is logged by key name.

Adding or changing settings is **not** a power increase, so `PluginUpdateReview` does not require consent for it. If an update adds a `required` setting with no default and no value exists, the plugin shows **"Needs configuration"** (see D-C).

## 6. Delivery to the plugin (SDK/AIDL, plugin API level 3)

Add one method to `IOneAiPlugin`:

```
/** Replaces the plugin's configuration snapshot. Values were validated by the host. */
oneway void applyConfig(int schemaVersion, in String[] keys, in String[] values);
```

- The host calls it after every successful bind and after every change, before any `execute`. A plugin process that dies is re-sent the snapshot on the next bind, so the host stays the source of truth.
- `PluginApi.LEVEL` and `PluginBounds.SDK_API` become 3. A plugin that declares `<settings>` must declare `minCoreApi="3"`; the host never calls `applyConfig` on a plugin whose declared level is below 3 (an old plugin has no such method). Plugins with level ≤ 2 are unaffected.
- The SDK base class stores the latest snapshot and exposes `config` to command handlers; it applies the same bounds as the host (defence in depth).
- `:one-ai-plugin-testkit`'s `FakePluginHost` gains `applyConfig` capture and a way to run commands with a given snapshot.

## 7. Plugin Centre UI

On the installed-plugin card, a **Configure** action opens a host-rendered form generated from the schema (switch, text field, number field, option list), with inline validation messages and Save/Reset-to-default. All plugin-supplied text is shown as untrusted text. Settings are available only for trusted plugins; changing them does not change enabled state or Bubble access. A "Needs configuration" state is shown on the card (this also feeds gap 4, lifecycle/diagnostic status). Settings are not shown in the Bubble in v1.

## 8. Implementation slices (each its own commit set, each validated by CI on the exact SHA)

1. **9.6a** — Pure schema types, parser additions, `PluginConfigValidator`, bounds, unit tests (no Android).
2. **9.6b** — `PluginConfigStore`, `OneAiStore` implementation, `PluginManager` removal hook, update reconciliation, tests.
3. **9.6c** — API level 3: AIDL method, SDK base-class support, host delivery on bind/change, test-kit support, tests.
4. **9.6d** — Plugin Centre Configure screen and "Needs configuration" status, policy tests (UI itself is not verified until Stage 11).
5. **9.6e** — Extend the Echo contract sample with one or two generic settings, update `docs/PLUGIN_DEVELOPER_GUIDE.md` and `PLUGIN_SDK.md`, add host/plugin contract tests.

## 9. Test plan (JVM/CI level)

Schema parsing and bounds; every rejection path (duplicate key, bad default, out-of-range number, unknown choice, oversize); persistence round-trip; isolation between plugins; removal clears config; update reconciliation (kept / reset / dropped); `applyConfig` ordering before `execute`; no call to plugins below level 3; no values in Activity entries; enable-time block for missing required settings.

**Not claimed by CI:** real Binder delivery, behaviour after process death, rendering and input on a device, and whether SharedPreferences backups expose values (to be checked against the app's backup configuration during implementation, then recorded). These belong to Stage 11.

## 10. Out of scope

Capability-specific settings; secrets handling; the generic catalogue (separate Stage 9 item); changes to trust, approvals or Bubble access rules; any rebuild of existing Plugin Centre flows.

## 11. Decisions needed from the owner

- **D-A — Secrets:** exclude secret/credential settings from v1 (recommended), or include a `secret` type backed by the existing Keystore-encrypted vault?
- **D-B — Delivery:** host pushes the snapshot on bind and on change (recommended), or the plugin pulls via a new method?
- **D-C — Required settings:** block enabling a plugin until required settings have values (recommended), or allow enabling and let commands fail with a clear error?
- **D-D — API level:** bump the plugin API to 3 for `applyConfig` (recommended), or deliver settings as reserved arguments on `execute` without bumping?
