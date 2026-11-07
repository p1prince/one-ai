# ONE AI

ONE AI is a modular Android AI assistant platform built around a lightweight shell and optional capabilities.

## Implemented foundation

- Premium graphite/gold Compose dashboard and six-section navigation.
- Persistent plugin registry with catalogue, install, enable, disable and remove lifecycle.
- Plugins and commands start disabled/empty; installation never silently enables a service or adds commands.
- Developer Mode creates validated declarative plugin manifests (v1) with reverse-domain IDs, semantic versions, capability allowlists and dependency declarations.
- Plugin lifecycle checks required dependencies before enabling, disabling or removing plugins; existing saved plugin records remain readable.
- Plugin manifest contract and lifecycle rules are documented in `PLUGIN_SDK.md`.
- Floating overlay service with draggable bubble and independent quick panel. It requires explicit Android overlay permission and remains off by default.
- Bubble services are individually opted in and remembered.
- Command Manager stores user-created instructions and sends them to the configured AI provider.
- The bubble's Command Manager lists and executes enabled commands without redirecting to the main app.
- Provider-neutral AI interface with an OpenAI-compatible HTTPS chat-completions adapter.
- Shared AI orchestration entry point used by the main assistant and floating-bubble command execution; it records request outcomes and does not claim unimplemented device actions.
- Provider API keys encrypted using Android Keystore AES-GCM.
- Local activity log and persistent settings.
- Plugin descriptors declare capabilities. The shell does not dynamically load untrusted executable code.
- GitHub Actions APK workflow is manual-only; pushes do not create APK builds.
- Optional AutoDroid integration opens the separately installed automation app only after a fresh ONE AI approval; macro selection, permissions and execution remain inside AutoDroid.

## Important integration boundaries

Android restricts device control, background work, screen capture, accessibility and cross-app actions. Each such capability must use the relevant Android API, user consent and runtime permission. The catalogue does not claim that every external capability is implemented. AutoDroid remains a separate project; ONE AI provides an explicitly approved launcher hand-off only. Macro data is not shared and macros are not started remotely.

## Build

JDK 17, Android SDK 36 and Gradle 8.13 are required. Run `gradle assembleDebug` when the foundation is ready for an APK build.

## Security

- No arbitrary downloaded plugin code is loaded into the shell.
- Provider endpoints must use HTTPS.
- API keys are encrypted at rest with Android Keystore.
- Overlay access is requested only when the user enables the bubble.
- Optional plugins and bubble services are off by default.
