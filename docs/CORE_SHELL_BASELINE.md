# ONE AI — Consolidated Core Shell Baseline

**Purpose:** establish the acceptance baseline for the standalone ONE AI application before any further capability-extension work.

**Owner priority (2026-11-06): finish and accept the ONE AI shell first.** Do not create, extract, package, or integrate optional capability extensions until the Core Shell is complete and accepted. This sequencing instruction does not replace the official 12-stage plan or change the approved architecture; it determines what work is allowed to proceed now.

## 1. Product definition

ONE AI is the main product: a standalone Android AI open-shell platform. It must start and provide its core experience without requiring any optional capability extension to be installed, enabled, or available.

The product model is like an empty host platform: the shell is useful and complete as an application in its own right; optional extensions add capabilities later. The shell must not be treated merely as a launcher or container that becomes usable only after extensions are installed.

## 2. Permanent Core responsibilities

The permanent Core includes:

- Main Android application, app lifecycle, startup composition, and the five-section navigation.
- Home / Control Centre and the general AI interaction experience.
- Core AI engine, provider-neutral orchestration, conversation handling, and core settings needed for general AI operation.
- Generic command registry, validation, execution, result handling, and failure containment.
- Generic Plugin Centre / Plugin Manager infrastructure: discovery, import/trust workflow, enable/disable lifecycle, removal, and extension status. The shell may be empty when no extensions are installed.
- Generic Plugin SDK/contracts and the runtime boundary needed to host compatible extensions. Keep the existing implementation; do not rebuild it without evidence of a concrete defect.
- Core security, permission policy, approvals, trust enforcement, and safe execution boundaries.
- Core Settings and Activity/history surfaces.
- Floating Bubble as a permanent Core component.
- Developer Mode as a generic shell capability for plugin authors, without requiring a particular capability extension to exist.

These are platform facilities, not implementations of individual optional capabilities.

## 3. Plugin scope — generic infrastructure is Core; individual plugins are future projects

The generic plugin ecosystem is a permanent part of ONE AI Core and a foundation-release requirement. The Plugins section is a full, usable management centre inspired by the add-on management experience in Kodi.

Current Core work includes generic discovery/catalogue, import or installation, installed-plugin details, trust, signer verification, compatibility, dependencies, enable/disable, configuration, update handling, removal, lifecycle/status, permissions and approvals, plus generic integration with ONE AI and the Floating Bubble.

The Developer section and versioned SDK are generic infrastructure for independent plugin authors.

Individual capability implementations—including Browser/Web Research, Voice, Screen Intelligence, App Control, Device Control, AutoDroid, automation, and any other named examples—are future plugin projects only. They are not current milestones, reference-module requirements, acceptance criteria, or dependencies for the first foundation release. Do not extract, package, build, or integrate them unless the owner explicitly requests a separate plugin project.

Existing capability-specific source may be inspected only to identify a concrete dependency that prevents standalone Core operation. Preserve existing code/history; do not delete, migrate, or restructure it speculatively.

## 4. Shell navigation and Home

The app has exactly five primary destinations:

1. Home
2. Plugins
3. Develop
4. Activity
5. Settings

Command Manager is a Home module that opens its own screen with back navigation. It is not a sixth navigation destination.

Use the recovered design specification in `docs/STAGE_1_SPEC.md`. Do not invent visual details or reintroduce the rejected animated AI-core dashboard.

Recovered design tokens:

| Element | Approved value |
|---|---|
| Background / Ink | `#101114` |
| Panels | `#1A1C20` |
| Gold | `#D6AE58` |
| Primary text | `#F5F3EE` |
| Secondary text | `#9699A1` |
| Theme | Material 3 dark color scheme |
| Home horizontal padding | 20dp |
| Home block spacing | 18dp |
| Status / module / command panel corners | 13dp / 15dp / 18dp |

Home content remains grounded in `docs/STAGE_1_SPEC.md`: ONE AI header and tagline, “Your Control Centre”, Command ONE, the four platform modules (Plugin Centre, Floating Bubble, Developer Mode, Command Manager), and system status cards. Keep functional copy where the old placeholder text has become inaccurate; do not redesign the approved structure.

## 5. Floating Bubble — permanent, empty-by-default Core

The Floating Bubble is part of Core, not a plugin.

- It is enabled and controlled through Settings, subject to Android overlay permission.
- Enabling the Bubble must not preload or activate a service, command, provider, or capability.
- Its initial state is empty.
- The user separately controls whether an installed plugin is enabled and whether that plugin may appear in the Bubble.
- Enabling a plugin never grants Bubble access automatically.
- The Bubble renders generic core commands and compatible plugin contributions through the existing core-owned contracts.
- Disabling Bubble access hides that plugin's Bubble contribution without disabling the plugin itself.
- Removing or disabling a plugin removes or hides its contribution as appropriate.
- Command/result interaction should remain in the Bubble when invoked there; it should not unexpectedly redirect the user to the main app.

Do not add capability-specific UI or commands to the permanent Bubble shell.

## 6. Existing architecture to preserve

Preserve the owner-approved decisions in `docs/DECISIONS.md`:

- D1: independently installed extensions use separately signed Android APKs and the secure bound-service AIDL boundary; no arbitrary folder/dex loading.
- D2: Command Manager is a Home module, not a sixth tab.
- D3: proceed without routine approval prompts; PR merge, APK build, and architecture or 12-stage plan changes still require explicit owner authorisation.
- D4: the generic, versioned Plugin SDK and secure external extension contract are Core; individual capability reference modules are not required foundation deliverables.

Existing generic plugin infrastructure is part of the shell. Keep and reuse the SDK, plugin management, runtime, security, approval, command, and Bubble contracts already implemented. Do not rebuild completed infrastructure or create duplicate execution paths.

## 7. Core Shell completion and acceptance checklist

The shell is ready for owner acceptance when the following are implemented and evidenced:

- [ ] The app starts and remains usable without optional capability extensions.
- [ ] Production startup composition does not silently register or require optional capability providers, services, commands, or credentials.
- [ ] Home and all five primary destinations are present and navigable.
- [ ] General AI interaction uses the core AI engine and can operate independently of optional extensions (provider credentials/network availability are recorded as external runtime prerequisites, not as extension dependencies).
- [ ] Generic command management and execution work without a capability extension installed.
- [ ] Plugin Centre presents a valid empty state and retains generic import/trust/enable/disable/remove infrastructure.
- [ ] Developer Mode is a generic shell surface; no individual capability extension is required to open or use the shell.
- [ ] Activity and Settings are available and their core responsibilities do not depend on optional extensions.
- [ ] Floating Bubble can be enabled, opens as an empty generic shell, and does not activate anything by default.
- [ ] Existing security, approval, trust, and plugin isolation boundaries remain intact.
- [ ] Startup, persistence, navigation, and core interactions have been reviewed at the appropriate automated, emulator, and physical-device evidence levels.
- [ ] The recovered visual specification is checked on an actual screen; CI alone is not visual acceptance.

A test that manually constructs an empty host is not, by itself, proof that the real production application starts with no optional capability registrations. Verify the actual production composition path.

## 8. Evidence and change control

Keep evidence levels separate:

- **Source review:** implementation and dependency inspection.
- **JVM tests:** pure logic and host-side tests.
- **CI:** compile, JVM unit tests, lint, and compile-only Android instrumentation sources as currently configured. CI does not imply an APK build or device execution.
- **Emulator:** actual emulator execution.
- **Device:** actual physical-device execution.

Do not claim emulator/device verification unless it occurred and the exact result is recorded in `DEVELOPMENT_TRACKER.md`.

Preserve existing files and history. Make focused changes only after inspecting current contents. Do not delete or replace working code speculatively. Do not build an APK or merge PR #1 without explicit owner authorisation.

## 9. Definition of a successful Core Shell

ONE AI can start, operate its general AI interface, navigate, manage settings and activity, manage extensions, and provide an empty generic Floating Bubble without depending on any optional capability being installed or enabled.


## 10. Mandatory scope lock and handoff

All contributors and future AI assistants must read `docs/PROJECT_CONTINUATION_RULES.md` before working. It is the binding owner-approved clarification of the current scope and how to continue without repeating completed work. Older documents that schedule capability extraction or reference capability APKs are superseded historical records. The generic Plugin Centre is current Core work; individual capability plugins are not.
