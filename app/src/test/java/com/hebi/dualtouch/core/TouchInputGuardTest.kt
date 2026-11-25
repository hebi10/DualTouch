package com.hebi.dualtouch.core

import org.junit.Assert.*
import org.junit.Test

class TouchInputGuardTest {
    @Test fun acceptsOnlyTheOriginalCompleteTap() {
        val start = TapStart(TapPoint(100f, 200f), 1000L, 1000L)
        assertTrue(start.accepts(TapPoint(106f, 205f), 1000L, 1100L, 8, 500L))
        assertFalse(start.accepts(TapPoint(100f, 200f), 1001L, 1100L, 8, 500L))
        assertFalse(start.accepts(TapPoint(100f, 200f), 1000L, 999L, 8, 500L))
        assertFalse(start.accepts(TapPoint(100f, 200f), 1000L, 1501L, 8, 500L))
        assertFalse(start.accepts(TapPoint(109f, 200f), 1000L, 1100L, 8, 500L))
    }

    @Test fun duplicateOrLateCallbacksCannotReleaseANewerGesture() {
        val flight = GestureFlight()
        val first = flight.begin()!!
        assertNull(flight.begin())
        assertTrue(flight.complete(first))
        val second = flight.begin()!!
        assertFalse(flight.complete(first))
        assertTrue(flight.pending)
        assertTrue(flight.complete(second))
        assertFalse(flight.pending)
    }

    @Test fun expiryDoesNotUnlockAnotherInputBeforeAndroidCompletes() {
        val flight = GestureFlight()
        val first = flight.begin()!!
        assertTrue(flight.expire(first))
        assertTrue(flight.expired)
        assertTrue(flight.pending)
        assertNull(flight.begin())
        assertFalse(flight.expire(first))
        assertTrue(flight.complete(first))
        assertFalse(flight.expired)
        assertNotNull(flight.begin())
    }

    @Test fun serviceReconnectInvalidatesOldCallbacks() {
        val flight = GestureFlight()
        val first = flight.begin()!!
        flight.reset()
        val second = flight.begin()!!
        assertFalse(flight.complete(first))
        assertFalse(flight.expire(first))
        assertTrue(flight.pending)
        assertTrue(flight.complete(second))
    }

    @Test fun stoppedGenerationCannotContinueEvenAfterAndroidCompletes() {
        val generation = DispatchGeneration()
        val flight = GestureFlight()
        val token = generation.current()
        val id = flight.begin()!!
        generation.invalidate()
        assertNull(flight.begin())
        assertTrue(flight.complete(id))
        assertFalse(generation.valid(token))
    }

    @Test fun timeBudgetAndFrameworkExceptionsFailClosed() {
        var clock = 0
        val result = inspectInputTree(
            root = 0, limit = 256, isSensitive = { false },
            shouldContinue = { clock < 2 },
            children = { node ->
                if (node == 0) (1..10).asSequence().map { clock++; it }.iterator()
                else emptyList<Int>().iterator()
            },
        )
        assertEquals(InputInspection.TIME_BUDGET_EXCEEDED, result)
        assertEquals(2, clock)
        val unavailable = inspectInputTree(0, 256, { false }) {
            throw IllegalStateException("window disappeared")
        }
        assertEquals(InputInspection.UNAVAILABLE, unavailable)
        val app = ObservedWindow(WindowLayer.APPLICATION, "example", ScreenRect(0, 0, 10, 10),
            inputInspection = InputInspection.TIME_BUDGET_EXCEEDED)
        assertEquals(SafetyIssue.INPUT_INSPECTION_TIMEOUT, WindowSafetyPolicy.inputIssue(listOf(app)))
    }
}
