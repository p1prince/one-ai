# ONE AI — Stage 1: Design Recovery and Requirements Freeze

Sources: the owner's Master Development Instructions (4 Nov 2026), the Official Open-Shell Development Plan (3 Nov 2026), and the original dashboard commit `b2d025a70c0f777749a7214bec3fffb4ccac7a01` ("Build initial ONE AI Android shell foundation", single file `MainActivity.kt`, 188 lines). Evidence for every "recovered" value below was read directly from that commit.

## 1. Recovered visual specification (verified against `b2d025a`)

| Item | Value | Source in `b2d025a` |
|---|---|---|
| Background (Ink) | `#101114` | `Color(0xFF101114)` |
| Panels | `#1A1C20` | `Color(0xFF1A1C20)` |
| Gold | `#D6AE58` | `Color(0xFFD6AE58)` |
| Primary text | `#F5F3EE` | `Color(0xFFF5F3EE)` |
| Secondary text | `#9699A1` | `Color(0xFF9699A1)` |
| Theme | Material 3 `darkColorScheme` (primary Gold, background Ink, surface Panel) | `MaterialTheme` |
| Screen heading | 27sp bold | "Your Control Centre", `SimpleScreen` titles |
| Brand title | 20sp bold, letter-spacing 2sp | "ONE AI" |
| Brand tagline | 9sp, letter-spacing 1.5sp, secondary text | "YOUR INTELLIGENT PLATFORM" |
| Brand mark | 42dp gold rounded square (13dp), black "1" 25sp | Home header |
| Horizontal padding | 20dp (Home) | `LazyColumn` |
| Standard spacing | 18dp between Home blocks | `spacedBy(18.dp)` |
| Panel corners | 13dp (status cards), 15dp (module cards), 18dp (command card, empty-state panel) | shapes |
| Section labels | 11sp bold, letter-spacing 1.4sp, secondary text | "PLATFORM MODULES", "SYSTEM STATUS" |
| Module card | 42dp icon tile (gold 12% alpha, 12dp corners), title semibold, 12sp subtitle, chevron | `ModuleCard` |
| Status card | 9sp bold label, 11sp semibold gold value, 11dp padding, 13dp corners | `StatusCard` |
| Navigation | Bottom `NavigationBar`, panel background, gold selected, gold 14% indicator, 10sp labels | `OneAiApp` |

Rejected (must not return): the animated AI-core dashboard.

## 2. Recovered Home content

1. Header: brand mark, **ONE AI**, **YOUR INTELLIGENT PLATFORM**, settings icon.
2. **Your Control Centre** / "One platform. Infinite possibilities."
3. Command card: **Command ONE** / "Your AI command interface is being prepared." (sparkle icon, arrow).
4. **PLATFORM MODULES**: Plugin Centre ("Install and manage capabilities"), Floating Bubble ("Independent quick-access interface"), Developer Mode ("Create and test your own plugins"), **Command Manager** ("Organise commands and shortcuts").
5. **SYSTEM STATUS** cards: AI CORE "Foundation", EXTENSIONS "None installed", SECURITY "Ready".

Five navigation destinations only: **Home, Plugins, Develop, Activity, Settings** (icons: Home, Extension, Code, History, Settings). Command Manager is a Home module, **not** a sixth destination (owner instruction, 4 Nov 2026).

Original empty states: Plugins "Plugin Centre — Your platform starts with a clean slate. / No plugins installed yet."; Develop "Developer Mode — Build extensions for ONE AI."; Activity "Your ONE AI activity will appear here. / No activity yet."; Settings "Manage your ONE AI experience."

## 3. Differences between the current app and the recovered design (as of `7dc6be0`)

| # | Difference | Status |
|---|---|---|
| D1 | Six bottom-nav items; **Commands** added | Conflict. Correct in Stage 4: remove tab, make Command Manager a Home module opening a sub-screen with back navigation |
| D2 | Command card reads "COMMAND ONE" (caps) with a working prompt field; original placeholder "Your AI command interface is being prepared." no longer present | Likely legitimate evolution, because an AI interface now exists. Keep the "Command ONE" title; the subtitle string is obsolete once the interface works. Not a redesign, but recorded for owner confirmation |
| D3 | "EXTENSIONS / None installed" status card absent in current Home (string not found) | Verify in Stage 4; restore live equivalent (installed-plugin count, "None installed" when zero) |
| D4 | Colour, 27sp heading, 20sp brand title tokens present | Matches |
| D5 | Spacing: current Home uses 15dp between blocks (original 18dp); card corner 16dp used in `CardBox` | Minor deviation; align to 18dp / approved radii in Stage 4 |
| D6 | Nav label size 9sp (original 10sp) | Align in Stage 4 |
| D7 | Plugin Centre / Settings text changed to functional copy | Acceptable where function replaced placeholder; keep headings as originally approved |

No values are invented: anything not listed above (fonts beyond default, icon sizes for the Bubble, Bubble visual design) was **not** recovered and must be specified by the owner before being designed.

## 4. Functional requirements (frozen)

Core (permanent): main app; dashboard and five-section navigation; AI engine and orchestration; conversation management; generic command framework; plugin management; plugin SDK; registration and lifecycle; security and permissions; user approvals; settings and activity history; Floating Bubble and its infrastructure; generic plugin-to-Bubble integration.

Not core (future plugins): Voice, Screen Intelligence/Capture, Web Intelligence, App Control, Device Control, AutoDroid, advanced automation, any future capability.

**Floating Bubble (core, not a plugin):** enabled from Settings; appears over other apps (subject to Android overlay permission); movable; independently accessible; own interface; **starts empty**; never auto-activates plugins, services or commands. Provides a generic area where compatible plugins expose commands/results/interfaces. Plugin enablement and plugin Bubble access are two independent user controls; disabling Bubble access does not disable the plugin; disabling or removing a plugin removes its Bubble functionality. No capability is hard-coded in the Bubble.

**Plugin contract (Stage 8):** each plugin declares identity, version, compatibility, capabilities, commands, permissions and optional Bubble interface. Extension model: separate signed plugin APKs (decision D1 in `docs/DECISIONS.md`); no arbitrary dex/class loading.

**Interaction rules:** sensitive actions need explicit, exact-action, one-use user approval; plugins and Bubble services are disabled/empty by default; installing a plugin never silently enables it or grants Bubble access.

## 5. Process constraints (frozen)

No unapproved scope or architecture change; fixed 12 stages in order; verify each stage before advancing; CI checked on the exact commit; never claim untested behaviour as verified (CI = compile + JVM unit tests; emulator and device are separate); PR #1 stays draft and unmerged; **no APK without explicit authorisation**.

## 6. Exit criteria status

Specification documented from recovered evidence: **done (this document)**. Owner confirmation outstanding on D2 only. Stage 1 may be marked complete once D2 is answered or accepted.
