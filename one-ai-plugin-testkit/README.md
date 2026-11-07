# ONE AI Plugin Test Kit

A reusable, in-memory test harness for plugin authors.

Add `testImplementation(project(":one-ai-plugin-testkit"))` to a plugin module and construct `FakePluginHost(handler = yourCommandHandler::run)`. The harness uses the same `PluginCallRunner` as `OneAiPluginService` and exposes progress, launch, completion and failure events.

It exercises command input/output bounds, one-request-at-a-time behaviour, timeout, cancellation and exactly-once terminal replies without requiring an Android device.

It does not emulate Android Binder, PackageManager identity/signature checks, separate-process behaviour, ONE AI's trust/approval policy or Bubble rendering. Those remain host integration and Stage 11 emulator/device acceptance concerns.
