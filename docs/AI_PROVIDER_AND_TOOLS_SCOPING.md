# AI providers and AI-to-plugin tool use - scoping

- **Date:** 2026-11-08. **Method:** read-only source inspection of exact head `8dbdfc70fb14e6eb105eefdd3d8b877dcf59c497` (CI #657 passed on `33ba79a`; `8dbdfc7` is a tracker-only commit). No source, manifest, build file or test was changed.
- **Evidence level:** source inspection only. Nothing was run on an emulator or device and no provider was called.
- **Why now:** the owner wants ONE AI to be a complete platform that stays useful without plugins, to use more than one AI service (not only a single key), and to make plugins feel like part of the AI instead of a separate toolbox.
- **Scope:** generic Core only (Stage 5 provider-neutral AI, Stage 6 command framework, Stage 8/9 plugin contract and management). No capability plugin is involved (D4 stands). Because this changes the plugin API and adds a new permission state, **nothing here starts without owner decisions** (section 6).
- **Files read:** `ai/AiProvider.kt`, `ai/AiProviderClient.kt`, `ai/AiOrchestrator.kt`, `ai/AiConversation.kt`, `ai/ProviderSettingsPolicy.kt`, `commands/CommandFramework.kt`, `commands/CommandRouter.kt`, `AppServices.kt` (command registration and composition), `MainActivity.kt` (Home chat and command calls), `core/OneAiStore.kt` (provider settings), `security/SecretVault.kt`, `core/CapabilityVocabulary.kt`, `docs/BUBBLE_INTERACTION_SCOPING.md`, `docs/DECISIONS.md` (D10, D11).

## 1. What exists

- `AiProvider` is a one-method interface (`complete(messages)` returning text, model and provider name). `AiOrchestrator`, `RetryingAiProvider` and Home depend on the interface, so another adapter is a small change.
- `AppServices` builds exactly one adapter, `AiProviderClient`: an OpenAI-compatible chat-completions client over HTTPS with one key in the vault, and three strings in `OneAiStore` (endpoint, model, display name; defaults are OpenAI's). Settings has one card for it. Switching service means overwriting these values.
- The vault already supports named secrets (`saveNamed` / `readNamed`), so more than one key can be stored without a vault redesign.
- The orchestrator's own comment says Android actions "will be dispatched only through separately registered, permissioned tools"; its system prompt tells the model it has no tools. **No code connects the AI to the command registry.** Home runs only `core/run_saved` (a saved prompt sent to the AI). Plugin commands are reachable only from the Command Manager and from a plugin's own Bubble view.
- Commands carry `title`, `description` and `capability`, and **no parameter schema** (decision D11 / D-U chose A1: no manifest change, arguments only through a plugin's own Bubble view).
- Extension commands already run through `CommandExecutor` with the host approval ticket flow. The capabilities `commands` and `audio` need no approval prompt (`CapabilityVocabulary`).
- Message roles accepted by the client are `system`, `user` and `assistant`; the response parser reads only `message.content`.

## 2. Gaps

**Providers**
- **P1 - one configuration only.** One endpoint, model and key; no list, no picker, no per-service key. Many services advertise an OpenAI-compatible endpoint, so a user can point the single configuration at one of them today (not tested here), but cannot keep several or switch quickly.
- **P2 - no adapter for services with their own API.** Anything not OpenAI-compatible needs a new adapter, and the app has no registry for adapters; each would be an edit to `AppServices`.
- **P3 - no streaming and no tool-calling in the contract.** `complete` returns only text.
- **P4 - "use the browser AI I already pay for".** An official API or OAuth login can fit an adapter. Driving a logged-in chat website through the user's session is scraping: it normally breaks the service's terms, breaks whenever the page changes, and risks the user's account. Not recommended for a release foundation.

**AI and plugins**
- **T1 - the model cannot see or call any plugin command.** This is why plugins do not feel like part of the AI.
- **T2 - no arguments.** With no parameter schema the model could call only zero-argument commands, and could not pass text to, say, a "reverse this text" command. Tool use is therefore weak until commands can declare arguments (this reopens D-U's "A2 is not done").
- **T3 - no approval surface for AI-started calls on Home.** Only the Bubble has an approval panel for extension commands.
- **T4 - security.** A model's choice of tool can be steered by untrusted text (a web page, a file, another plugin's result): prompt injection. An AI-started call must be treated as an untrusted request: per-call user approval even for `commands`, the exact arguments shown, a small cap on calls per turn, bounded and cleaned plugin titles/descriptions before they are sent to a third-party model, bounded plugin output before it goes back, and a plain statement of what leaves the device.
- **T5 - states must stay separate.** Your guide keeps installed, trusted, enabled and Bubble access apart. "The AI may use this plugin" is a fifth, separate user-controlled setting (default off), not implied by enabling.

## 3. Options

**Providers**
- **V1 (recommended): provider profiles plus an adapter registry.** A saved list of named profiles (name, kind, endpoint, model, its own named vault secret), one active; `kind` selects a factory from a small registry (`openai_compatible` first), so a future adapter is a registered factory, not an `AppServices` edit. Settings gets a picker and "add profile". Migration: the current endpoint/model/key become the first profile. No SDK change.
- **V2 (later): plugin-supplied providers.** A new capability (for example `ai_provider`, plugin API level 4) lets a plugin supply completions over the existing AIDL channel and keep its own credentials (the guide already says secrets stay in the plugin). This is how a user or developer adds an OAuth or subscription backend without a Core release for each one. Larger; touches the closed capability vocabulary (D10: a new identifier is a Core release change).
- **V3: logged-in website session.** Not recommended (P4).

**AI and plugins**
- **U1 (recommended): native tool calling with per-call approval.** Order: (a) argument schema for commands (manifest `<arg name label required>`, API level 4, validator and update-review diff, a host form in the Command Manager); (b) a per-plugin "AI may use" setting; (c) an optional tools field in the provider contract and OpenAI-compatible `tools` / `tool_calls` mapping, with a bounded loop (for example at most 3 tool steps per request); (d) an approval card for each AI-started call in Home and the Bubble showing the exact arguments, with Activity entries (kind `ai`, plugin id).
- **U2: provider-neutral proposals.** The model is asked to emit a structured "propose command" block, the host validates it against the declared schema and shows a "Run" card; the user confirms. Works with any provider, no provider tool-calling needed, safe by construction, but clumsier and less reliable. Reasonable as a fallback for providers without tool support; not the first choice.

## 4. Proposed slices (if approved)

1. **S1 - provider profiles and registry (V1).** Independent of the rest. Tests: migration of the existing settings, one active profile, per-profile secret, unknown kind refused, no key text in logs or Activity.
2. **S2 - command argument schema (U1a).** Manifest, validator, registry definition, update-review diff, test kit, Echo sample, developer guide, Command Manager form. Plugin API level 4; hosts and plugins at lower levels keep working.
3. **S3 - "AI may use this plugin" (U1b).** Separate setting, default off, shown in the Plugins screen, Details and the Access overview; never changed by enabling or by Bubble access.
4. **S4 - tool contract and loop (U1c).** Provider contract extension, OpenAI-compatible mapping, bounded loop, untrusted-text cleaning, fake-provider JVM tests (tool request, refusal, step cap, malformed tool call, injection text in a plugin title).
5. **S5 - approval surface and records (U1d).** Home and Bubble approval cards, Activity entries, docs, emulator rows.

Each slice is one commit plus a tracker record with its CI run. S1 can go first on its own; S2 to S5 are an ordered chain.

## 5. What this does not do

- It does not add voice, screen, web research or any capability plugin.
- It does not change trust, enablement, Bubble access or the approval gateway for existing flows.
- It does not make plugin code run inside the AI or give plugins model credentials: plugins never receive ONE AI's key.
- It does not claim any provider supports tool calling in the form assumed; that must be checked per service.

## 6. Owner decisions needed

- **D-V:** providers V1 now (recommended), V2 later, V3 never.
- **D-W:** reopen D-U and add command arguments (A2, plugin API level 4), which U1 needs.
- **D-X:** AI-to-plugin tool use U1 (recommended) or U2.
- **D-Y:** add the separate "AI may use this plugin" setting, default off (recommended).
- **D-Z:** every AI-started call needs per-call user approval, including capability `commands` (recommended).

## 7. Not verified

No provider was called. Whether specific services accept the `tools` field through an OpenAI-compatible endpoint, how models behave with many tools, the cost and latency of a multi-step loop, and how the approval card reads on a phone are all unknown. The claim that a single configuration can already be pointed at another OpenAI-compatible service is from source, not a run.
