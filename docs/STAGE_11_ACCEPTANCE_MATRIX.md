# ONE AI - Stage 11 acceptance matrix (preparation; nothing here is acceptance)

**Status:** preparation only. Written from source inspection of `1b63e25` plus the tracker and the owner's reports. Stage 11 is **not formally passed**, and nothing below was run by this document. It maps each Stage 11 criterion (master guide section 6) to the evidence that exists today and to what is still missing, so the remaining evidence can be gathered in one pass.

Evidence key (kept separate, as the guide requires): **CI** = compile + JVM unit tests + lint + androidTest compile (builds no APK); **Emulator** = never run; **Device** = the owner's single-tablet report only (owner-reported, two screenshots per report, Android version not recorded).

CI baseline: Android Validation #677 passed on exact SHA `40fdbf8c8747c8616469f715e3ea7de6f274f3c2` (run `42493659537`). The CI run for the docs commit that adds this file is not yet recorded.

Test-file names below are the candidate CI coverage. I matched them by file name and the tracker's descriptions; I did not audit every test case, so "candidate" is the strongest claim made.

## 1. Criteria

| # | Stage 11 criterion | CI (candidate coverage) | Device evidence so far | Still missing |
|---:|---|---|---|---|
| 1 | Fresh install launches independently; an empty plugin catalogue does not break Core | `PluginManagerTest` (empty catalogue), `CoreReservedNamesTest`, `CatalogueSourcesTest`, `PluginExecutionServicesTest` (empty host). The tracker itself notes the empty-host test does not prove production startup registers zero optional providers. | Owner: the first test APK installed and opened on one tablet | Real production startup on a clean install; no-sources catalogue behaviour (M20) |
| 2 | AI works without plugins; all five sections and Core commands work | `AiOrchestratorTest`, `AiConversationTest`, `ProviderProfilesTest`, `CommandFrameworkTest`, `CommandRouterTest`, navigation tests (five-destination guard) | Owner: Home, five-item navigation, Command ONE card and system status shown as intended | **No live AI request has ever been made** (owner has no API key yet). Core commands, Activity, Settings and Develop screens not reported as used on the device |
| 3 | Bubble can be enabled, starts empty, remains independent | `BubbleUiPolicyTest`, `BubbleContractTest`, `BubbleRunStateTest`, `BubbleRunningWiringTest` | Owner: the Bubble opens; quick panel shows Command Manager, Resize bubble, Hide for 1 minute, Close panel | Overlay-permission flow, dragging, position persistence, "starts empty" observed on screen, behaviour after service restart |
| 4 | A generic compatible test plugin can be installed or imported, registered, enabled and configured | `EchoSamplePluginContractTest`, `ExternalPluginIntegrationTest`, `ExternalPluginCenterTest`, `ExternalPluginCenterConfigurationTest`, `ExternalPluginConfigDeliveryTest`, `PluginConfigureFormTest`; instrumented tests compile only | Owner: Echo plugin installed, appeared in the Plugin Centre, imported and enabled | **Configure** never exercised on a device (M16-M19); the 9 instrumented tests have never run |
| 5 | Bubble access separately granted and revoked; plugin disable and removal behave correctly | `PluginManagerTest`, `ExternalPluginCenterTest`, `BubbleUiPolicyTest`, `InAppPluginAccessTest` | Owner: the plugin's screen works from the Bubble (so Bubble access was granted); revoke, disable and removal not reported | M3, M4, M8, M12, M30, M42 |
| 6 | Disabling Bubble does not corrupt plugin state; permissions block unauthorised actions | `PluginActionGatewayTest`, `ActionSecurityTest`, `PluginLaunchApprovalBrokerTest`, `CoreRuntimePermissionGuardTest`, `AccessOverviewTest` | None | M13, M14, M31 (M13, M14 and M31 need a test plugin variant that does not exist) |
| 7 | Plugin failure does not crash or disable Core; relevant settings persist across restarts | `CoreInfrastructureTest` (failure containment), `PluginRuntimeTest`, `PluginExecutionServicesTest`, `ActivityLogTest`, `SecretReadTest` | None | M7, M15, M33, M35, M39, restart persistence of settings and provider profiles |

## 2. Rows that cannot be run with what exists

- **Test variants needed (do not exist yet):** M13, M14, M19, M31, M46, M48, M49 (a plugin declaring `files`, a required-setting variant, a screen-requesting variant, a third-party binder caller).
- **Infrastructure needed:** M20-M28 need an HTTPS server you control for catalogue documents and a second plugin APK.
- **Authorisation needed:** any APK build, including the instrumentation APK for `gradle :app:connectedCoreDebugAndroidTest`. The owner has authorised two builds only, both already used. This matrix does not authorise a third.

## 3. Honest summary of what Stage 11 could claim today

- **Can claim (CI):** the logic behind every criterion is covered by passing JVM tests on `40fdbf8`.
- **Can claim (owner device report):** install, launch, the intended Home UI and navigation, the Bubble opening with its quick panel, and Echo import, enable and Bubble use, all on one tablet.
- **Cannot claim:** a live AI response, plugin configuration, Bubble access revoke, disable or removal on a device, failure isolation, restart persistence, the Running panel (M36-M40), the in-app plugin screen (M41-M49), provider profiles, or any emulator result.

## 4. Decisions that gate formal Stage 11

1. **Evidence route:** a third test APK (so the owner can run M1-M12, M16-M18, M29-M30, M33, M36-M45 and an AI request with a key) or CI-only acceptance. Not decided.
2. **Style work** (`docs/STYLE_CONFORMANCE_REVIEW.md` section 4): O-1, O-2 or O-3. Not decided.
3. **AI-to-plugin tool use** (D-W to D-Z): not part of the plan's Stage 1-10 requirements; leaving it open does not block Stage 11.

No source, build file or test changed in this commit.
