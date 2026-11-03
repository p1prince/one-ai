# ONE AI

ONE AI is a modular Android AI assistant platform built around a lightweight, dependable shell and optional capabilities delivered as plugins.

## Current milestone: Shell foundation

This first implementation establishes:
- A native Android app with a dark graphite and gold dashboard.
- Bottom navigation for Home, Plugins, Develop, Activity and Settings.
- Initial Plugin Centre and Developer Mode screens.
- A GitHub Actions workflow that builds and uploads a debug APK.

## Architecture direction

The shell will own navigation, plugin discovery/registry, command routing, permissions and the floating-bubble framework. Optional capabilities will be isolated behind explicit plugin contracts. AutoDroid is planned as the first automation integration, not as a dependency of the shell.

## Build

Requires JDK 17, Android SDK 36 and Gradle 8.13.

```bash
gradle assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Safety

Plugins must declare capabilities and request only the permissions they need. The shell must remain usable when no plugins are installed or when a plugin is disabled or removed.
