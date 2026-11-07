package com.oneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NavigationTest {
    @Test fun navigationHasExactlyTheFiveApprovedDestinationsInOrder() {
        assertEquals(listOf("Home", "Plugins", "Develop", "Activity", "Settings"), Page.destinations.map { it.title })
    }

    @Test fun commandManagerIsNotANavigationDestination() {
        assertFalse(Page.Commands in Page.destinations)
    }
}
