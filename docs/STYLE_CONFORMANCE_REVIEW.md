# ONE AI - Style conformance review (Plugins, Develop, Activity, Settings)

**Type:** read-only source inspection of `40fdbf8` (`app/src/main/java/com/oneai/app/MainActivity.kt`, 787 lines). Nothing was rendered, run, or changed. This is **not** visual acceptance: only a screen can give that (Stage 11). Reference: `docs/STAGE_1_SPEC.md` section 1 (values recovered from `b2d025a`).

CI at review time: Android Validation #677 passed on exact SHA `40fdbf8c8747c8616469f715e3ea7de6f274f3c2` (run `42493659537`, GitHub CLI 2026-11-08).

## 1. What matches the recovered specification

| Item | Spec | Source finding |
|---|---|---|
| Palette | Ink `#101114`, Panel `#1A1C20`, Gold `#D6AE58`, Muted `#9699A1`, White `#F5F3EE` | `MainActivity.kt` lines 81-82: identical values, shared by every page |
| Screen heading | 27sp bold | One shared `Header()` (line 166) used by Plugin Centre, Developer Mode, Command Manager, Activity and Settings: 27sp bold, 13sp muted subtitle |
| Horizontal padding | 20dp | Home, Plugins, Command Manager and Settings use `padding(horizontal=20.dp)`; Develop and Activity use `padding(20.dp)` on all sides (same horizontal value) |
| Navigation | Bottom bar, Panel background, gold selected, gold 14% indicator, 10sp labels | Lines 139-144: same |
| Section labels | 11sp bold, 1.4sp spacing | `letterSpacing=1.4.sp` appears 3 times; other 11sp bold labels omit it |
| Five destinations | Home, Plugins, Develop, Activity, Settings | Enforced by existing unit tests (not re-run here) |

## 2. Deviations and unknowns (genuine, not yet decided)

| # | Finding | Evidence | Kind |
|---|---|---|---|
| V1 | `CardBox`, used for every card on the four screens, has 16dp corners and 16dp padding. The spec recovers 13dp (status cards), 15dp (module cards) and 18dp (command card, empty-state panel). 16dp matches none. | line 167 | Deviation (known as D5 in Stage 1 spec; fixed on Home only) |
| V2 | Block spacing: Home uses 18dp; Plugins, Activity, Settings use 12dp or 10dp between blocks and 24dp top padding (Home 26dp). The spec recovers 18dp for Home only; the other screens had no recovered spacing. | lines 173, 284, 615, 630, 682 | Unknown: the spec does not say |
| V3 | Two colours exist that are not in the recovered palette: error red `#FF8A80` (used 20 times) and warning amber `#FFD27A` (2 uses). | grep of `Color(0x` | Not recovered. They were added for function; the owner never approved values. |
| V4 | Body and caption sizes (11sp, 12sp, 13sp, 10sp on cards) are not in the spec beyond the Home card sizes. | size survey | Unknown |
| V5 | Section label letter-spacing (1.4sp) is applied inconsistently: three labels have it, other 11sp bold labels do not. | size survey | Minor deviation |
| V6 | Empty states: Plugins reads "No plugins installed" / "ONE AI starts empty..." and the spec's original strings ("Plugin Centre", "No plugins installed yet.") differ. Activity and Settings headings are functional copy, not the original placeholders. | spec section 2 vs lines 287, 317, 633, 683 | Accepted by Stage 1 spec D7 (functional copy replaced placeholders) |
| V7 | The Activity filter chips use a fully rounded shape (`RoundedCornerShape(50)`), a radius the spec does not mention. | line 641 | Unknown |
| V7b | Vertical padding differs by page: Home 26dp top, Plugins/Command Manager/Settings 24dp, Develop and Activity 20dp. Spec recovers Home only. | lines 173, 284, 595, 615, 630, 682 | Unknown |
| V8 | The Bubble visual design was never recovered; this review does not cover it. | Stage 1 spec | Open since Stage 1 |

## 3. What this review cannot decide

V1 and V5 are small, concrete deviations from recovered values. V2, V3, V4 and V7 concern values the spec never recorded, so changing them would be inventing design details, which the owner's rules forbid. They need either the owner's original design references or an owner decision to accept the current values as the standard.

## 4. Options for the owner

- **O-1 (recommended):** accept the current functional values for everything the spec did not recover (V2-V4, V7), and authorise a small correction slice for the two recovered-value deviations only (V1 card radius, V5 label spacing). Verify on a device afterwards.
- **O-2:** supply the original design references; the review is then redone against them before any change.
- **O-3:** defer all style work to Stage 11 and judge it on the tablet first.

No source change is made by this document. The recorded evidence is source inspection only.
