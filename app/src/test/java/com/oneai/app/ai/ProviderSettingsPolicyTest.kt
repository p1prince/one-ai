package com.oneai.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderSettingsPolicyTest {
    @Test fun acceptsSecureProviderEndpoint() {
        assertNull(ProviderSettingsPolicy.endpointError("https://api.example.com/v1/chat/completions"))
    }

    @Test fun rejectsHttpAndMissingHost() {
        assertEquals("Provider endpoint must use HTTPS.", ProviderSettingsPolicy.endpointError("http://api.example.com/chat"))
        assertEquals("Provider endpoint must include a valid host.", ProviderSettingsPolicy.endpointError("https:///chat"))
    }

    @Test fun rejectsEmbeddedCredentialsAndFragments() {
        assertEquals("Provider endpoint cannot contain embedded credentials or a fragment.", ProviderSettingsPolicy.endpointError("https://user:pass@example.com/chat"))
        assertEquals("Provider endpoint cannot contain embedded credentials or a fragment.", ProviderSettingsPolicy.endpointError("https://api.example.com/chat#token"))
    }

    @Test fun rejectsBlankOrMalformedInput() {
        assertEquals("Enter an HTTPS chat-completions endpoint.", ProviderSettingsPolicy.endpointError("  "))
        assertEquals("The provider endpoint is not a valid URL.", ProviderSettingsPolicy.endpointError("not a url"))
    }
}
