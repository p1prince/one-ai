# ONE AI — V1.0 Release, Distribution & Update Roadmap

**Document status:** Active roadmap  
**Product baseline:** Owner-tested ONE AI Core build  
**Baseline branch:** `foundation/bubble-panel-gestures`  
**Baseline commit:** `658fd987089ed9770a38fc9545c54362882573ff`  
**Last recorded CI evidence:** Android Validation run #693 passed on the exact baseline SHA.  
**Owner device report:** The owner reports that the latest tested build works as expected. This is owner-reported physical-device verification, not an independently observed test.

This roadmap replaces the completed app-construction roadmap as the active work plan. It does not discard the development history, change the product architecture, or authorize a merge, APK build, signing-key creation, publication, or release. Those actions still require the owner's explicit authorization.

## 1. Mission

Prepare ONE AI for its first official public release as **ONE AI 1.0.0**, and ensure that the first public release already contains the foundation needed to discover and install future updates.

The release process must preserve the tested application, its package identity, its data, its plugin ecosystem, and its release-signing continuity. Future versions must install as Android updates over earlier official releases without requiring users to uninstall the app or lose their data.

## 2. Non-negotiable release rules

1. Treat commit `658fd987089ed9770a38fc9545c54362882573ff` as the current owner-tested source baseline unless the owner approves a later baseline.
2. Do not rebuild, redesign, or reimplement completed ONE AI features as part of release preparation.
3. Preserve the Android application ID `com.oneai.app`. Changing it creates a different Android application and breaks normal in-place upgrades.
4. Use one permanent release-signing identity for all official releases. Back up the private keystore and recovery information securely. Never commit signing keys, passwords, or signing secrets to Git.
5. Android's `versionCode` must increase with every published update. The current app configuration is `versionName = "0.2.0"` and `versionCode = 2`; do not reset the code to 1. Confirm the release value during Batch 1. A proposed first official release code is 3, subject to checking all variants and existing distribution.
6. Every official update must use the same application ID and compatible release-signing certificate. Android itself must validate the APK signature during installation.
7. Preserve user data. Database changes require explicit, tested, forward-safe migrations; never clear app data as an update strategy.
8. The first public V1.0 release must include the update checker. If the updater is postponed until after V1.0, V1.0 cannot discover updates from inside the app.
9. The updater must not silently install APKs. It downloads and validates the update, then hands installation to Android's package installer, where the user confirms.
10. Use GitHub Releases as the initial official distribution channel. Keep each published release, tag, APK, checksum, and release note available as version history.
11. Keep debug/test signing separate from release signing. The first transition from a debug-installed test build to a release-signed build may require a one-time uninstall/reinstall because Android rejects a different signing certificate for an in-place update. Plan a safe data backup/export or migration route before that transition; do not promise seamless debug-to-release replacement.
12. Never merge into `main`, create/publish a release, build a release APK, or change release credentials without explicit owner authorization.
13. Verify exact commit SHA and CI status before and after each implementation batch. Distinguish CI, automated tests, emulator tests, physical-device tests, and owner-reported results.

## 3. Release batches

### Batch 1 — Release baseline and identity audit

**Objective:** Establish the exact artifact identity and the safe release path before changing build configuration.

Tasks:
- Verify the current branch, HEAD SHA, repository status, and Android Validation result.
- Confirm `applicationId = "com.oneai.app"`, namespace, product flavors, current version name/code, min/target SDK, and all APK output variants.
- Inspect the current Android Build workflow and determine how it builds Core debug and sample artifacts.
- Inspect signing configuration and confirm whether any permanent release keystore or signing setup already exists. Do not generate or replace keys during the audit.
- Determine how the owner's current debug installation can transition to the release-signed build without accidental data loss.
- Define the official release artifact name, release notes format, checksum format, and GitHub release channel.
- Record the exact baseline and all findings in the tracker.

**Acceptance:** A reviewed release-identity report with no guessed package IDs, version codes, signing state, or workflow behavior.

### Batch 2 — Permanent release signing and build pipeline

**Objective:** Make official release builds repeatable and protect the signing identity.

Tasks:
- Agree and document the release keystore ownership, secure backup, recovery procedure, and signing-certificate fingerprint.
- Generate or import the permanent release key only with owner authorization. Keep the private key outside the repository.
- Store signing materials in appropriately protected GitHub Actions secrets or another owner-controlled secure signing environment. Never print secret values in logs.
- Configure a distinct signed release build variant; retain debug builds for testing.
- Confirm the official application ID remains `com.oneai.app` and set the first release version to `1.0.0` with a version code greater than the existing value 2. Proposed starting value: 3, after checking every shipped variant.
- Update the manual Android workflow to build and upload the signed Core release APK only when explicitly invoked through the approved release process.
- Generate a SHA-256 checksum and record the release certificate fingerprint alongside build metadata.
- Ensure sample/test APKs are not accidentally presented as the ONE AI consumer release.

**Acceptance:** A signed release candidate can be built reproducibly from a known commit; its signing identity and checksum can be verified. Do not publish it yet.

### Batch 3 — Core in-app update manager

**Objective:** Make the first public release capable of discovering future official releases.

The update manager is permanent Core functionality, not a plugin.

Tasks:
- Add a small release service that queries the official ONE AI GitHub Releases endpoint.
- Support a stable release channel and a controlled prerelease/test channel so update behavior can be tested without confusing test builds with public stable releases.
- Compare the installed `versionCode` with the candidate release's version code; never offer a downgrade as a normal update.
- Display installed version, available version, release date, and release notes.
- Provide a manual **Check for updates** action in Settings → App Updates.
- Check at an appropriate app lifecycle point and use a restrained, user-respecting notification strategy. Avoid frequent background polling; any periodic checks must use Android-compliant scheduling and clear user controls.
- Download the APK using HTTPS, handle interruptions, and store it in an app-private location.
- Validate expected package identity, version, file size, SHA-256 metadata, and expected release signer/certificate identity before handing off installation. Treat a checksum delivered from the same compromised source as an integrity check, not independent proof of publisher identity.
- Use Android's supported package-install flow (for example, PackageInstaller or an appropriate content-URI install intent). Explain any required Android “install unknown apps” permission and let Android show its confirmation UI.
- Handle cancellation, insufficient storage, network failure, invalid APK, unsupported Android version, and install failure without corrupting the existing installation.
- Never request or implement silent installation or bypass Android's package installer.
- Keep update checking and downloading independent from AI provider credentials, plugin permissions, and Floating Bubble permissions.

**Acceptance:** The app can detect a newer test release, show its notes, download and validate it, and invoke Android's installer safely. No production update is published as part of this batch.

### Batch 4 — Upgrade continuity and data-preservation verification

**Objective:** Prove that future official versions update in place and preserve user state.

Tasks:
- Inventory persistent data: provider profiles and encrypted credentials, conversations/history if persisted, commands, plugin records and configuration, trust state, Bubble settings, preferences, and database files.
- Document which data is expected to survive an Android package update and which data is not currently persisted by design.
- Add database schema versioning and explicit migrations wherever the current persistence implementation requires them.
- Test a signed build upgrading over an earlier build signed with the same release key and using the same application ID.
- Test a small patch update (for example 1.0.0 → 1.0.1) and a major update (for example 1.0.0 → 2.0.0) using increasing version codes.
- Verify provider configuration, plugin enablement/trust/configuration, Bubble settings, preferences, and all actually persisted user content after each update.
- Test failed/cancelled installation and confirm the existing installed app remains usable.
- Prepare the one-time debug-to-release transition instructions and data-protection procedure for the owner's current test installation.

**Acceptance:** A recorded upgrade matrix passes on a physical device or a suitable emulator, with the release certificate and exact version codes recorded. No data-loss claim may be made for data that is not persisted by the app.

### Batch 5 — V1.0 release candidate review and owner acceptance

**Objective:** Review the complete release candidate before publication.

Tasks:
- Verify all prior batches and exact commit/CI evidence.
- Review permissions, privacy disclosures, network destinations, release notes, app label/icon, version display, and support/contact information.
- Verify the release APK signature, package ID, version name/code, checksum, and artifact provenance.
- Verify the updater points only to the official repository and handles draft/prerelease/stable releases according to the selected channel.
- Complete the owner-approved device test checklist.
- Produce a release-candidate report with known limitations and a clear go/no-go recommendation.

**Acceptance:** The owner explicitly approves the exact source SHA and signed artifact for publication.

### Batch 6 — Merge, tag, and publish ONE AI 1.0.0

**Objective:** Publish the first official release without losing the development record.

Tasks:
- Reconfirm the target branch and exact approved release commit.
- Merge the approved development branch into `main` only after explicit owner authorization.
- Re-run required validation on the exact merge commit.
- Create the immutable Git tag `v1.0.0` at the approved release commit.
- Publish the GitHub Release with the signed Core APK, SHA-256 checksum, release notes, and installation/update instructions.
- Keep the release asset and source tag available as the permanent V1.0 record.
- Record the release URL, tag, commit SHA, version code, signer fingerprint, and artifact checksum in the tracker.

**Acceptance:** ONE AI 1.0.0 is published and downloadable, with its exact source and signed artifact traceable.

## 4. Ongoing release policy after V1.0

- Patch releases: `1.0.1`, `1.0.2`, etc. Use for compatible fixes.
- Minor releases: `1.1.0`, `1.2.0`, etc. Use for backward-compatible features.
- Major releases: `2.0.0`, etc. Use for substantial product changes or deliberate compatibility changes.
- Every published APK receives a strictly increasing Android `versionCode`, regardless of the visible semantic version.
- Keep all official releases and their notes/assets on GitHub. Do not overwrite an old release asset to represent a new version.
- Keep the package ID and release-signing identity stable.
- Run data migrations and the upgrade test matrix before publishing any release that changes persistent storage.
- Future release branches and pull requests must state the source baseline, target version, version code, migration impact, signing identity, CI evidence, and upgrade-test evidence.

## 5. Current verified starting point

| Item | Current evidence |
|---|---|
| Repository | `p1prince/one-ai` |
| Active tested branch | `foundation/bubble-panel-gestures` |
| Tested source baseline | `658fd987089ed9770a38fc9545c54362882573ff` |
| Android Validation | Run #693 passed on exact baseline SHA |
| Physical-device result | Owner reports latest tested build works |
| Application ID | `com.oneai.app` |
| Current configured version | `0.2.0` |
| Current configured version code | `2` |
| Permanent release signing | Not yet established by this audit |
| In-app update manager | Not yet implemented |
| Official GitHub Release | Not yet published |
| Merge to `main` | Not authorized / not performed |

## 6. Required handoff format

At every handoff, report:
1. Active branch and exact HEAD SHA.
2. Current batch and its acceptance status.
3. Files changed and why.
4. CI run IDs and exact SHAs; distinguish CI from device testing.
5. Signing/version/data-migration implications.
6. Any owner decision needed.
7. The single next task.

Do not ask the owner to repeat settled architecture or completed feature work. Read the current repository state and this roadmap first.
