package com.oneai.app.plugins

import org.junit.Assert.*
import org.junit.Test

class AppControlPolicyTest {
    @Test fun acceptsNormalAndroidPackageNames() {
        assertTrue(AppControlPolicy.validPackageName("com.android.settings"))
        assertTrue(AppControlPolicy.validPackageName("org.example.My_App"))
    }

    @Test fun rejectsMalformedOrOversizedNames() {
        listOf("", "calculator", "com..bad", "com.example space", ".example.app", "a".repeat(256))
            .forEach { assertFalse(it, AppControlPolicy.validPackageName(it)) }
    }
}
