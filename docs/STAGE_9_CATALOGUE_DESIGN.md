# Stage 9 — Generic plugin catalogue / repository source: design proposal

**Status: PROPOSED — awaiting owner decisions D-E to D-H (section 11). No code has been written.**
Date: 2026-11-07. Scope: Stage 9 remaining gap "browsable catalogue/repository source (production catalogue is empty)" in `DEVELOPMENT_TRACKER.md` and `docs/STAGE_2_AUDIT.md` §12.10. Governed by `docs/PROJECT_CONTINUATION_RULES.md` and decisions D1, D4 and D5: generic and capability-neutral; no capability plugin is created, named as a deliverable or bundled.

## 1. Problem

The Plugins screen can only show plugins that are **already installed on the device** (`PluginDiscovery.discover()` queries for services answering `PluginApi.SERVICE_ACTION`). There is no way to see what plugins exist, and `AppServices` passes `emptyList()` to `PluginHostComposition`, so a fresh install shows nothing to browse. The official plan (Stage 9) requires the Plugin Centre to "discover and display available plugin sources/catalogue entries where configured". This note designs that source without changing how trust, import, enablement or Bubble access work.

## 2. Principles

- **A catalogue is a listing, never authority.** An entry can describe a plugin; it cannot make it installed, trusted, enabled or Bubble-visible. Installed / Trusted / Enabled / Bubble access remain four separate states.
- **Trust is still decided on the device.** Import keeps the existing flow: the user confirms the fingerprint read from the *installed* package (`PluginTrustRegistry.fingerprintForImport` / `import`). A catalogue fingerprint is shown as a claim and used as an extra check, never substituted for the user's confirmation.
- **Empty and failing sources are normal.** No source configured, an unreachable source, or a malformed document leaves the Core fully working and the existing installed-plugin flows untouched.
- **No hidden network traffic.** The catalogue is fetched only on an explicit user action (Refresh), consumed through a one-shot gate like `PluginUpdateCheckGate`. Opening or redrawing the Plugins screen never fetches.
- **Bounded and fail-closed.** Oversized, malformed or non-HTTPS input is rejected whole, never truncated or partially accepted.
- **Additive.** Nothing about discovery of on-device plugins changes; the catalogue adds a second, separate list.

## 3. Sources

A *source* is a user-added HTTPS URL pointing at one catalogue document (Kodi "repository" analogue).

- **None are preconfigured** (see D-E). The Core ships with zero sources, matching "no capability preselected".
- Maximum 8 sources. Each has a user-visible name (taken from the document, shown as untrusted text) and its URL. The user can add, refresh and remove a source. Removing a source deletes its cached listing; it does not affect installed plugins.
- URL rules: `https` only; no credentials in the URL; host must be non-empty; length ≤ 500. Redirects are followed only to `https`. No cleartext fallback.
- Persisted by a small persistence-only `CatalogueSourceStore` interface (same pattern as `PluginConfigStore`), implemented in `OneAiStore` (SharedPreferences JSON). Unreadable stored data is discarded to "no sources" and logged. The app already sets `allowBackup="false"` and already holds the `INTERNET` permission; no new permission is needed.

## 4. Catalogue document format (v1)

JSON, UTF-8, at most 256 KB.

```json
{
  "catalogueVersion": 1,
  "name": "Example source",
  "entries": [
    {
      "id": "com.example.plugin",
      "name": "Example plugin",
      "summary": "One-line description",
      "versionName": "1.2.0",
      "versionCode": 12,
      "minCoreApi": 2,
      "targetCoreApi": 3,
      "capabilities": ["commands"],
      "dependencies": [],
      "signerSha256": ["AB:CD:..."],
      "getUrl": "https://example.com/plugins/example",
      "homepage": "https://example.com"
    }
  ]
}
```

Bounds (new constants in a pure `CatalogueValidator`, beside the existing manifest bounds and reusing `ExternalManifestValidator` rules where the field is the same): ≤ 200 entries; `id` is an Android package name (same validation as `ExternalPluginManifest.packageName`) and unique within the document; `name` 1–60; `summary` ≤ 200; `versionName` ≤ 40; `versionCode` ≥ 1; `minCoreApi`/`targetCoreApi` as in the manifest; `capabilities` ≤ 12, `dependencies` ≤ 16, same identifier rules as the manifest; `signerSha256` 1–4 values, each normalised with `CertificateFingerprint.normalize`; `getUrl` and `homepage` are `https` URLs ≤ 500. One invalid entry is skipped and counted; an invalid *document* (wrong version, over size, not JSON, duplicate ids) is rejected whole. Unknown fields are ignored, as in the manifest, so older hosts read newer documents.

Entries are **listing data**. The installed package's own manifest (read from the APK without running plugin code) remains the only source for what the host actually trusts and runs. If the two disagree, the installed manifest wins and the entry is marked stale.

## 5. Entry states shown in the Plugin Centre

A pure `CatalogueMatcher` classifies each entry against `PluginDiscovery` results and `PluginTrustRegistry.peek`, using no Android types:

| State | Meaning |
|---|---|
| Not installed | No package with that id on the device. |
| Installed, not imported | Package present, not trusted yet. The existing fingerprint-confirmed import applies. |
| Imported | Trusted by the user; lifecycle status comes from the existing `ExternalStatus`. |
| Update available | Catalogue `versionCode` is higher than the installed one. Informational only; updates still arrive through the platform and the existing update review/consent. |
| Incompatible | `minCoreApi` is above `PluginApi.LEVEL`, via the existing `PluginCompatibility` rules (a small overload taking the entry's `minCoreApi`/`targetCoreApi`, since an entry is not a manifest). Shown with the reason. |
| Dependency missing | A declared dependency is not installed/enabled, via `PluginCompatibility.missingDependencies`. Shown by id. |
| Signer differs | The installed package's certificate is not among the entry's `signerSha256`. Shown prominently; see D-G. |

Nothing here enables, imports or grants anything. All text from a catalogue is rendered as untrusted text.

## 6. Getting a plugin onto the device

ONE AI does **not** download or install APKs (see D-F). An entry's `getUrl` is opened in the user's browser or store through a normal view intent after the user taps "Get". Once the user has installed the app by the platform's normal means, it appears in the existing "found on device" list and goes through the existing fingerprint-confirmed import. This avoids adding `REQUEST_INSTALL_PACKAGES`, download code and APK handling to the Core.

## 7. Catalogue integrity and trust

- Transport: HTTPS with the platform trust store only. No certificate pinning in v1.
- The document is not signed in v1 (see D-G); its fingerprint claims are advisory. This is deliberate: the real trust anchor is the user comparing the fingerprint of the installed package, exactly as today. A malicious or compromised source can mislead what is listed, not what is trusted or run.
- Therefore catalogue text is never used for identity decisions, a Bubble service name, a command, or a permission. Those still come only from the trusted installed manifest.
- Activity logging records source additions/removals/refresh outcomes by source host and counts, never document contents beyond the validated entry count.

## 8. The `BuiltInCatalogue` reservation question

`AppServices` still reads `BuiltInCatalogue.templates`/`bubbleBindings` to build `reservedIds` (registrar) and `reservedBubbleServices` (centre). Production already passes an empty catalogue to the host, so these reservations are the only live use of the old table. The reserved values are the legacy ids `one.command.tools`, `one.web.research`, `one.screen.intelligence`, `one.voice.interface`, `one.automation.engine` and the Bubble service names `web_research`, `screen_intelligence`, `voice_interface`, `automation`.

Recommendation (D-H): keep reserving exactly those names, because persisted `PluginRecord`s and Bubble selections from earlier installs may still use them and a colliding external plugin could inherit that state, but move them into a small explicit `CoreReservedNames` object in core that does not import `BuiltInCatalogue`. Behaviour is unchanged; the production composition no longer depends on the capability catalogue as a source of truth, and the names are documented as retained legacy identifiers rather than implied built-in capabilities. `BuiltInCatalogue.kt` and the legacy adapters are **not** deleted by this work.

## 9. Implementation slices (each validated by CI on the exact SHA)

1. **9.7a** — Pure model: `CatalogueDocument`/`CatalogueEntry`, `CatalogueParser`, `CatalogueValidator` with bounds, `CatalogueMatcher` with the section 5 states. JVM tests for every rejection path, skip-and-count behaviour, id collisions, API/dependency/signer classification, and "no entry ever yields an imported/enabled state".
2. **9.7b** — `CoreReservedNames` extracted and wired into `AppServices`; tests that the same names are still refused and that `AppServices` no longer references `BuiltInCatalogue` (a source guard like the existing production-boundary test).
3. **9.7c** — `CatalogueSourceStore` + `OneAiStore` implementation, `CatalogueFetcher` over an injectable transport (`HttpURLConnection` in production, fake in tests) with timeout, size cap, HTTPS-only redirects, one-shot refresh gate, per-source cached last result and error. Tests use the fake transport only.
4. **9.7d** — Plugin Centre UI: Sources dialog (add/remove/refresh), "Available" list with the section 5 states, detail view, "Get" action. Policy/formatting logic is pure and unit-tested; the screen itself is not rendered by CI.
5. **9.7e** — Docs: `docs/PLUGIN_DEVELOPER_GUIDE.md` section on publishing a catalogue document, a sample document used as a test fixture, `PLUGIN_SDK.md` pointer, emulator test plan additions.

## 10. Test plan and evidence limits

CI/JVM can show: parsing and bounds, matcher states, source persistence logic, fetcher behaviour against a fake transport (timeouts, oversize, non-HTTPS redirect, invalid JSON, empty source), the one-shot refresh semantics, that the catalogue never changes enabled/trust/Bubble state, and the reservation guard.

CI/JVM **cannot** show: real network behaviour and TLS, rendering and input on a device, the browser/store hand-off, or interaction with a real installed plugin. Those belong to Stage 11 and must be reported as not run until they are.

## 11. Out of scope

Individual capability plugins and any default catalogue content; in-app APK download or install; catalogue document signing (unless D-G chooses it); automatic or background refresh; accounts, ratings or payment; changes to trust, approval, configuration or Bubble-access rules; rebuilding existing Plugin Centre flows.

## 12. Decisions needed from the owner

- **D-E — Sources:** ship with no preconfigured source, user adds HTTPS URLs (recommended), or ship one project-hosted default source?
- **D-F — Install hand-off:** open the entry's link in the browser/store and let the platform install (recommended), or add in-app download plus system-installer hand-off (needs `REQUEST_INSTALL_PACKAGES` and more attack surface)?
- **D-G — Signer mismatch and document signing:** treat "installed certificate not in the entry's `signerSha256`" as a prominent warning that blocks *importing from the catalogue view* while the on-device import path stays unchanged (recommended), and leave the document unsigned in v1, or additionally require signed catalogue documents verified against a pinned source key?
- **D-H — Reserved names:** keep reserving the legacy ids/service names via an explicit `CoreReservedNames` (recommended), or drop the reservation entirely (risks collision with persisted legacy records)?
