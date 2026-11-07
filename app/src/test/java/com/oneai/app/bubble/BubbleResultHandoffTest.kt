package com.oneai.app.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BubbleResultHandoffTest {
    @Test fun trimsAndAcceptsValidResult() {
        assertEquals(
            BubbleHandoffResult("Screen result", "Selected region summary"),
            BubbleResultHandoff.validate(" Screen result ", " Selected region summary ")
        )
    }

    @Test fun rejectsMissingOrBlankFields() {
        assertNull(BubbleResultHandoff.validate(null, "content"))
        assertNull(BubbleResultHandoff.validate("title", "  "))
    }

    @Test fun rejectsOversizedPayloads() {
        assertNull(BubbleResultHandoff.validate("x".repeat(101), "content"))
        assertNull(BubbleResultHandoff.validate("title", "x".repeat(12_001)))
    }
}
