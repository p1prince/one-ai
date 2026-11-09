package com.oneai.app.ai

/**
 * What the Settings screen needs beyond the saved profiles: which providers have an adapter in this build, the ChatGPT
 * account and its sign-in, and how to open the browser. Passed as one object so the UI never reaches into the services.
 */
class ProviderSettingsEnv(
    val registeredKinds: () -> Set<String>,
    val account: ChatGptAccount,
    val signIn: ChatGptSignInController,
    /** Opens an address in the user's browser. Throws [AiProviderException] when there is no browser. */
    val openBrowser: (String) -> Unit
) {
    /** Only providers whose adapter exists in this build are offered. */
    fun offered(): List<ProviderDescriptor> = ProviderCatalog.available(registeredKinds())
}
