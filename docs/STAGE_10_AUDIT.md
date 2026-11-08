# Stage 10 requirements audit — security, settings and activity

- **Date:** 2026-11-08. **Method:** read-only source inspection of exact head `96ecea2f0434b9cf2a23feade1ce120161ec24d7` (Android Validation #623 passed on it). No source or tests were changed.
- **Evidence level:** source inspection only. It shows what code is present, not that it works at runtime. Nothing here was run on an emulator or device. Test files were listed, not executed or read in full.
- **Scope:** generic Core and plugin-platform security, settings and activity (Stage 10 of the fixed 12-stage plan). Capability plugins are out of scope (`docs/PROJECT_CONTINUATION_RULES.md`).
- **Files read:** `security/ActionSecurity.kt`, `security/PluginActionGateway.kt`, `security/SecretVault.kt`, the activity code in `core/OneAiStore.kt`, `ActivityPage` and `SettingsPage` in `MainActivity.kt`, `AndroidManifest.xml`, and the logging call sites in `ExternalPluginRegistrar.kt`.

## 1. Requirement-by-requirement status

| Stage 10 requirement | Status | Evidence |
|---|---|---|
| Permission declarations | Present | Plugins declare capabilities in the manifest; `CapabilityPolicy.evaluate` rejects an undeclared or unknown capability. |
| Permission enforcement | Present | `PluginActionGateway.check` is the single boundary: plugin missing, disabled, malformed request, unknown or undeclared capability are all rejected and audited. |
| Consent and action approvals | Present | Sensitive capabilities need a one-time ticket from a visible consent UI. Tickets are bound to the exact plugin, action, capability and description, expire in 60 s (max 5 min), and are consumed on use. |
| Revocation | Present for what exists | There are no standing grants by design. Pending tickets are revoked on disable, removal and deactivation (`revokePendingApprovals` call sites in `PluginManager` and `ExternalPluginRegistrar`). Trust can be forgotten. |
| Isolation and secure communication | Present (tracker CI evidence; not re-audited here) | Separately installed signed APKs, bound-service/AIDL, signer pinning, bounded requests. Real binder behaviour is not verified (Stage 11). |
| Credential protection | Present with one weakness | AES-GCM with non-exportable Keystore keys. See S1. |
| Settings: AI provider | Present | Provider label, HTTPS endpoint validation, model, key. |
| Settings: Bubble behaviour and per-plugin Bubble access | Present | Enable switch, per-service switches, ordering, gated on startup restoration. |
| Settings: individual plugin settings | Present | Configure flow in the Plugins screen (Stage 9.6). |
| Settings: security controls | **Gap** | See S2. |
| Activity: requests, commands, plugin activity, approvals, errors | Partial | Events are written to one log. See A1-A4. |
| "Understandable what extensions are doing and what access they have" | Partial | Details view shows capabilities with the approval rule and (9.9) recent plugin activity. There is no cross-plugin access overview. |

## 2. Genuine gaps (ordered by risk)

> **Update 2026-11-08:** A1 and A2 were corrected in `f1413f9` (Android Validation #626 passed; JVM and CI evidence only). The other items below remain open.

**A1 — Activity log writes are not thread-safe (defect, source evidence only).**
`OneAiStore.log` reads the whole JSON array from SharedPreferences, inserts one entry and writes it back. `OneAiStore` has no synchronisation (`@Synchronized` count is 0). Callers include the UI, the Bubble service, plugin-channel callbacks and IO-dispatcher code, so concurrent events can overwrite each other and be lost. This is a source-level inference; no test or device run reproduced it. Smallest correction: serialise `log`/`clearActivity` (one lock or a single writer) and add a concurrent-writer JVM test.

**A2 — Activity shows plugin-controlled text without cleaning.**
Log lines include `manifest.name` (`ExternalPluginRegistrar.kt` lines 45 and 54) and `[$pluginId] $message` from the adapter report (line 75). `ActivityPage` renders `it.message` as is. The catalogue and Details views already strip control, bidirectional and zero-width characters with `CatalogueView.clean`; Activity does not. Whether adapter `message` ever carries plugin-supplied text was not traced. Smallest correction: clean on display (or on write) with the existing helper, plus a test.

**S1 — A credential that cannot be decrypted looks the same as "not saved".**
`SecretVault.readSecret` returns null on any exception. After Keystore key invalidation, Settings reports "not saved" and "Needs an API key" with no hint that a saved value is unreadable. This is also a silent failure path for Stage 5 error recovery. Smallest correction: distinguish "absent" from "unreadable" in the read result and show a specific message. This needs a device or instrumentation check to reproduce.

**S2 — The Settings "SECURITY" card is static text, not controls.**
It lists four statements and offers no action. Stage 10 asks for security controls in Settings. Which controls belong there (for example a view of trusted signers, forget trust, a list of what each enabled plugin can do) is a product decision that has not been taken. Needs a short design note and owner decision before code.

**A3 — Activity has no structure.**
Entries are a timestamp plus free text, capped at 150, with no category, severity or plugin field. Stage 9.9 works around this by matching the plugin id inside the text, and says so in its own header comment. There is no filter or search. Changing the stored format is a migration decision for the owner (old entries have only text).

**A4 — No cross-plugin access overview.**
Capabilities and approval rules are visible one plugin at a time in Details. There is no single place showing what every enabled plugin may do and which have Bubble access. This may be sufficient for the foundation release; flag for the owner rather than build by default.

## 3. Items that are not Stage 10 gaps but affect it

- **Capability code in the Core app (owner decision pending, see tracker).** The manifest declares `RECORD_AUDIO`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` and `ScreenProjectionCaptureService`; Settings has a Brave Search credential section and Screen Intelligence wording. These widen the Core app's declared access beyond what a standalone shell without capability plugins needs. They are not invoked at startup. Any isolation needs a focused dependency audit and owner approval first.
- **`CapabilityPolicy` lists fixed capability names** (`screen_capture`, `microphone`, and so on). That is acceptable as a generic vocabulary, but a plugin declaring a capability outside the list is rejected, so extending the vocabulary is a Core change. Worth confirming this is intended for the SDK's "no Core edits per plugin" goal.

## 4. Recommended order

1. A1 (defect; small, testable on the JVM).
2. A2 (small, reuses an existing helper).
3. Owner decisions for S2, A3 and A4 (design note first).
4. S1 (needs an instrumentation or device check to confirm).

## 5. Not verified

- No runtime behaviour: approval dialog rendering, ticket expiry in a real session, Keystore behaviour, or concurrent logging.
- The existing `ActionSecurityTest` and `PluginActionGatewayTest` were not read; their coverage is assumed from the tracker, not checked.
- `SensitiveActionApprovalDialog` and `PluginActionButton` were not read.
