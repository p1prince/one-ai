package com.oneai.app.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenRegionPolicyTest {
    @Test fun normalizesDragInEitherDirection() {
        assertEquals(
            ScreenRegion(10, 20, 80, 90),
            ScreenRegionPolicy.normalize(80f, 90f, 10f, 20f, 100, 120)
        )
    }

    @Test fun clampsSelectionToImageBounds() {
        assertEquals(
            ScreenRegion(0, 0, 100, 120),
            ScreenRegionPolicy.normalize(-12f, -4f, 140f, 180f, 100, 120)
        )
    }

    @Test fun rejectsEmptyTinyAndInvalidImageSelections() {
        assertNull(ScreenRegionPolicy.normalize(4f, 4f, 10f, 10f, 100, 100))
        assertNull(ScreenRegionPolicy.normalize(4f, 4f, 4f, 30f, 100, 100))
        assertNull(ScreenRegionPolicy.normalize(0f, 0f, 20f, 20f, 0, 100))
    }

    @Test fun mapsDisplayedSelectionToSourcePixels() {
        assertEquals(
            ScreenRegion(200, 100, 800, 500),
            ScreenRegionPolicy.mapToSource(
                ScreenRegion(100, 50, 400, 250),
                displayedWidth = 500,
                displayedHeight = 300,
                sourceWidth = 1000,
                sourceHeight = 600
            )
        )
    }

    @Test fun rejectsInvalidMappingDimensions() {
        assertNull(
            ScreenRegionPolicy.mapToSource(
                ScreenRegion(1, 1, 10, 10), 0, 100, 100, 100
            )
        )
    }
}
