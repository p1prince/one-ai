# ONE AI

ONE AI is a complete, independent Android AI application with a first-class, generic plugin ecosystem.

The foundation application is useful without any individual capability plugin installed. Its purpose is to provide the AI platform, approved five-section interface, generic command infrastructure, permanent Floating Bubble, Kodi-inspired plugin management centre, Developer Mode and SDK, security, settings, and activity management.

## Permanent application features

- Five primary destinations: Home, Plugins, Develop, Activity, Settings.
- Independent provider-neutral AI engine and conversation experience.
- Generic command registration, routing, execution, results, and error handling.
- Permanent Floating Bubble, independently enabled from Settings and empty by default.
- Generic plugin discovery/import, trust, compatibility, dependency checks, enable/disable, configuration, updates, removal, and lifecycle management.
- Independent plugin enablement and Floating Bubble access controls.
- Generic Developer Mode and versioned plugin SDK/contracts.
- Core security, permissions, approvals, credential protection, settings, and activity history.

## Plugin scope

The Plugins section and generic plugin ecosystem are current Core deliverables. Individual capability plugins are not.

Browser/Web Research, Voice, Screen Intelligence, App Control, Device Control, AutoDroid, and other named capabilities are examples of possible future plugins only. They are not current roadmap tasks, acceptance dependencies, or foundation-release requirements. Do not extract, package, build, or integrate them unless the project owner explicitly adds a specific plugin project.

The application must remain complete and usable with an empty plugin catalogue. The Floating Bubble is Core infrastructure and starts empty. Enabling a plugin does not automatically grant Bubble access.

## Android security and extension model

Use the documented, versioned plugin contract and secure Android-compatible inter-process communication. Do not load arbitrary executable code from an untrusted plugin folder. Enforce identity, signing/trust, compatibility, permissions, user approvals, and failure isolation.

## Build and release

JDK 17, Android SDK 36 and Gradle 8.13 are required. APK builds and release actions require explicit project-owner authorization.

## Mandatory continuation instructions

Every AI or contributor must read `docs/PROJECT_CONTINUATION_RULES.md` before working. It defines the scope boundary, source-of-truth hierarchy, stage order, handoff requirements, and prohibition on treating future capability examples as current deliverables.
