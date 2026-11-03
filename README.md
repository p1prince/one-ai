# ONE AI

ONE AI is a modular Android AI assistant platform built around a lightweight shell and optional capabilities.

## Implemented foundation

- Premium graphite/gold Compose dashboard and six-section navigation.
- Persistent plugin registry with catalogue, install, enable, disable and remove lifecycle.
- Plugins and commands start disabled/empty; installation never silently enables a service or adds commands.
- Developer Mode creates validated declarative plugin descriptors.
- Floating overlay service with draggable bubble and independent quick panel. It requires explicit Android overlay permission and remains off by default.
- Bubble services are individually opted in and remembered.
- Command Manager stores user-created instructions and sends them to the configured AI provider.
- The bubble's Command Manager lists and executes enabled commands without redirecting to the main app.
- OpenAI-compatible HTTPS chat-completions provider client with model/endpoint settings.
- Provider API keys encrypted using Android Keystore AES-GCM.
- Local activity log and persistent settings.
- Plugin descriptors declare capabilities. The shell does not dynamically load untrusted executable code.
- GitHub Actions APK workflow is manual-only; pushes do not create APK builds.

## Important integration boundaries

Android restricts device control, background work, screen capture, accessibility and cross-app actions. Each such capability must use the relevant Android API, user consent and runtime permission. The catalogue currently provides integration slots; it does not claim that every external capability is already implemented. AutoDroid remains a separate project and is not yet bundled as a plugin.

## Build

JDK 17, Android SDK 36 and Gradle 8.13 are required. Run `gradle assembleDebug` when the foundation is ready for an APK build.

## Security

- No arbitrary downloaded plugin code is loaded into the shell.
- Provider endpoints must use HTTPS.
- API keys are encrypted at rest with Android Keystore.
- Overlay access is requested only when the user enables the bubble.
- Optional plugins and bubble services are off by default.
