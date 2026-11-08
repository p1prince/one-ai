# Stage 10 design note — security controls, structured Activity, access overview

- **Status:** PROPOSED. Nothing here is approved or implemented. Owner decisions D-I to D-L (section 8) are open.
- **Date:** 2026-11-08. **Basis:** `docs/STAGE_10_AUDIT.md` (gaps S2, A3, A4, S1), source head `f1413f9` (Android Validation #626 passed); this note adds docs only.
- **Scope:** generic Core and plugin-platform security, settings and activity. No capability plugin is involved and none is added (`docs/PROJECT_CONTINUATION_RULES.md`).
- **Evidence level:** source inspection. Call-site counts below come from a grep, not from a runtime trace.

## 1. What exists today (so nothing is rebuilt)

- **Approvals:** one-time tickets bound to the exact plugin, action, capability and description; 60-second life; never persisted. There are no standing grants, so there is nothing to "revoke" except pending tickets, which are already revoked on disable, removal and deactivation.
- **Trust:** the trust registry pins a signer per plugin. `forget` is reached only through plugin removal (`ExternalPluginRegistrar`) or a failed import (`ExternalPluginCenter`). A user cannot drop trust and keep the plugin.
- **Per-plugin view:** the Details dialog shows identity, pinned certificate, status, compatibility, dependencies, capabilities with the approval rule, commands, separate Bubble access and (9.9) recent activity.
- **Settings Security card:** four static sentences, no actions.
- **Activity log:** `ActivityEntry(timestamp, message)`, newest first, cap 150, one flat list, "Clear history" with confirmation. About 45 call sites write to it; the `store.log(...)` ones are spread over eight files (AppServices 5, MainActivity 2, AiOrchestrator 3, CommandRouter 6, ExternalPluginRegistrar 4, PluginExecutionServices 1, PluginLaunchApprovalController 2, PluginManager 5). The gateway and adapters write through other entry points as well.

## 2. S2 and A4 — one read-only Access overview (recommended)

**Problem.** The Security card has no controls, and the only way to see what plugins may do is to open each plugin's Details.

**Proposal.** Replace the static text in the Settings Security card with a read-only **Access overview** built from data the app already holds:

- One row per trusted plugin: name, Enabled yes/no, Bubble access yes/no, "Needs configuration" if set, and the capabilities that always ask first (from `CapabilityPolicy.requiresApproval`, the same rule the gateway applies).
- A short pinned-signer fingerprint per row, and a "Details" button that opens the existing Details dialog. No second copy of that logic.
- Plain sentences under the list: what an approval is (one use, expires in a minute), that nothing is granted permanently, and that removing a plugin also removes its trust, settings and Bubble access.
- Empty state when no plugin is trusted.

**No new powers.** The overview changes no trust, enablement, Bubble access or approval behaviour. Existing switches and Remove stay where they are.

**Option rejected for now: "Revoke trust but keep the plugin".** It would be new behaviour (a plugin that is installed, imported and untrusted) with its own states and tests. The current model has one clean exit, Remove, and the foundation release does not need a second. Revisit only if the owner wants it.

**Option rejected: a permissions table with toggles.** There are no persistent permissions to toggle; showing switches would suggest standing grants that do not exist.

**Slice 10.1.** A pure `AccessOverview` builder (input: trusted entries, enabled ids, Bubble access, needs-configuration titles; output: rows with cleaned text via `CatalogueView.clean`, bounded to a fixed row count), the Settings card, and unit tests: empty state, enabled/disabled, Bubble access shown separately from enabled, approval-first capabilities match `CapabilityPolicy`, long and hostile plugin text cleaned, row cap, and a source guard that the card has no state-changing call.

## 3. A3 — structured Activity entries (recommended, additive)

**Problem.** Entries are a timestamp plus free text. Features that need to know what an entry is about (9.9 matches the plugin id inside the text) have to guess, and the screen cannot filter.

**Proposal.** Extend the stored entry with two **optional** fields and keep the old format valid:

- `kind`: one of `ai`, `command`, `plugin`, `approval`, `error`, `system`.
- `plugin`: the plugin id when the entry is about one plugin.

Rules:

- Old entries (no `kind`) load unchanged and are shown under the label "Earlier activity", as the screen already does for entries with no timestamp. **No migration pass and no rewrite of stored data.**
- Writing stays source-compatible: `log(message)` still works and records `kind = system` unless a call site passes more. Call sites are moved to the typed form gradually, starting with the gateway (approvals), the adapter and registrar (plugin) and the AI orchestrator (ai). Moving all 45 is not required for the slice to be useful.
- 9.9's text matching is replaced by the `plugin` field when present, and kept as a fallback for older entries.
- The cap stays 150 and the lock from A1 stays the single writer.

**Screen.** A row of filter chips: All, AI, Commands, Plugins, Approvals, Errors. Selection is not persisted. Filtering is display-only and never deletes anything. "Clear history" still clears everything after confirmation.

**Risk to name.** Free-text messages can still contain plugin-supplied text, so display cleaning from A2 stays in force for every kind. The `kind` is set by Core call sites only; a plugin cannot choose its own entry's kind.

**Slice 10.2.** `ActivityEntry` fields and codec (read old, write new), typed `log` overload, gateway and adapter call sites, tests: old JSON loads with `kind` unknown, new JSON round-trips, cap and order unchanged, concurrent writers still lose nothing, a plugin id field is preferred over text matching, an id that is only a prefix of another never matches.

**Slice 10.3.** Filter chips and the Details view switch to the field; tests for the pure filter, and a source guard that the filter never calls the clear function.

## 4. S1 — unreadable credential (small, needs a device check)

**Problem.** `SecretVault.readSecret` returns null on any exception, so a saved key that can no longer be decrypted (for example after Keystore key invalidation) shows as "not saved" and "Needs an API key". The user cannot tell that something went wrong.

**Proposal.** Distinguish absent from unreadable in the read result, and show "A saved key could not be read. Save it again." in Settings. Never log or display the value. The AI orchestrator's error text for this case should be the same sentence.

**Slice 10.4.** Vault result type and Settings text. The pure part (mapping a read outcome to a message) is JVM-testable; the Keystore behaviour itself is Android-only and is added to `docs/EMULATOR_TEST_PLAN.md` as a manual step, not claimed as verified.

## 5. Deliberately not in this note

- Moving the capability-specific code (manifest permissions, adapters, the Web Research key section in Settings). That is a separate owner decision after a focused dependency audit, already recorded in the tracker.
- Extending the capability vocabulary in `CapabilityPolicy`. Whether a plugin may declare a capability outside the fixed list without a Core edit is a question for the SDK design, not for this note. It is worth a decision before the SDK is called final.
- Streaming or exporting the activity log, retention settings beyond the 150-entry cap, and remote logging. None is required by Stage 10 and none is proposed.

## 6. Test plan summary

| Slice | JVM unit | Source guard | Emulator or device (not run) |
|---|---|---|---|
| 10.1 overview | rows, states, cleaning, cap | no state-changing call in the card | layout, long names, many plugins |
| 10.2 structure | codec, compatibility, concurrency, matching | log call sites typed for gateway and adapter | none |
| 10.3 filters | pure filter | filter never clears | chip behaviour, scrolling |
| 10.4 vault | message mapping | no value in log text | real Keystore invalidation |

CI runs the JVM and lint checks only. Every device item above stays "not run" in the tracker until someone runs it.

## 7. Order

10.1 first (read-only, lowest risk, answers S2 and A4), then 10.2 and 10.3 (A3), then 10.4 (S1). Each slice is one commit plus a tracker record with its CI run. Stage 11 does not start until Stage 10 is reconciled.

## 8. Decisions needed from the owner

- **D-I (S2 and A4):** approve the read-only Access overview in the Settings Security card as the answer to both, with no "revoke trust but keep the plugin" action. *Recommended: yes.*
- **D-J (A3):** approve additive optional `kind` and `plugin` fields, no migration of old entries, and filter chips on the Activity screen. *Recommended: yes.*
- **D-K (A3 scope):** move only the gateway, adapter, registrar and AI call sites to typed entries in this stage and leave the rest as `system`. *Recommended: yes.*
- **D-L (S1):** approve separating "unreadable" from "not saved" with the wording above. *Recommended: yes.*

If the owner answers "use recommendations", the four are approved as written.
