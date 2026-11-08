package com.oneai.app.bubble

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleRunStateTest {
    @Test fun eachRunGetsItsOwnIncreasingId() {
        val state = BubbleRunState()
        val first = state.begin()
        val second = state.begin()
        assertNotEquals(first, second)
        assertTrue(second > first)
        assertTrue(state.isRunning(first))
        assertTrue(state.isRunning(second))
    }

    @Test fun aRunThatCompletesShowsItsResultOnce() {
        val state = BubbleRunState()
        val id = state.begin()
        assertTrue(state.complete(id))
        assertFalse(state.isRunning(id))
        assertFalse("a second completion shows nothing", state.complete(id))
    }

    @Test fun cancellingCancelsTheAttachedJobAndSuppressesTheResult() {
        val state = BubbleRunState()
        val id = state.begin()
        val job = Job()
        state.attach(id, job)
        assertTrue(state.cancel(id))
        assertTrue(job.isCancelled)
        assertFalse("a result arriving after Cancel must not be shown", state.complete(id))
    }

    @Test fun cancelIsIdempotent() {
        val state = BubbleRunState()
        val id = state.begin()
        assertTrue(state.cancel(id))
        assertFalse(state.cancel(id))
    }

    @Test fun cancellingBeforeTheJobIsAttachedCancelsItOnAttach() {
        val state = BubbleRunState()
        val id = state.begin()
        assertTrue(state.cancel(id))
        val job = Job()
        state.attach(id, job)
        assertTrue(job.isCancelled)
    }

    @Test fun cancellingAFinishedRunDoesNothing() {
        val state = BubbleRunState()
        val id = state.begin()
        val job = Job()
        state.attach(id, job)
        assertTrue(state.complete(id))
        assertFalse(state.cancel(id))
        assertFalse("a finished run's job is left alone", job.isCancelled)
    }

    @Test fun runsAreIndependent() {
        val state = BubbleRunState()
        val first = state.begin()
        val second = state.begin()
        val firstJob = Job()
        val secondJob = Job()
        state.attach(first, firstJob)
        state.attach(second, secondJob)
        assertTrue(state.cancel(first))
        assertTrue(firstJob.isCancelled)
        assertFalse(secondJob.isCancelled)
        assertTrue(state.isRunning(second))
        assertTrue(state.complete(second))
    }

    @Test fun anUnknownIdIsNeverRunning() {
        val state = BubbleRunState()
        assertFalse(state.isRunning(99))
        assertFalse(state.cancel(99))
        assertFalse(state.complete(99))
    }

    @Test fun theHeadingUsesTheCleanedTitleOnly() {
        assertEquals("Running Add note...", BubbleRunning.heading("  Add   note ", "add_note"))
        assertEquals("Running add_note...", BubbleRunning.heading("   ", "add_note"))
        assertEquals("Running add_note...", BubbleRunning.heading(null, "add_note"))
        assertEquals("Running command...", BubbleRunning.heading(null, ""))
    }

    @Test fun theHeadingStripsHiddenCharactersAndIsBounded() {
        val tricky = "Safe\u202Etxt\u200B" + "x".repeat(200)
        val heading = BubbleRunning.heading(tricky, "fallback")
        assertFalse(heading.contains('\u202E'))
        assertFalse(heading.contains('\u200B'))
        assertTrue(heading.length <= "Running ".length + BubbleRunning.MAX_TITLE + "...".length)
    }
}
