package com.hebi.dualtouch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowSafetyPolicyTest {
    private val leftBounds = ScreenRect(0, 44, 500, 1120)
    private val rightBounds = ScreenRect(520, 44, 1020, 1120)

    private fun left(focused: Boolean = true, display: Int = 0) =
        ObservedWindow(WindowLayer.APPLICATION, TestPackages.LEFT, leftBounds, display, focused)
    private fun right(focused: Boolean = false, display: Int = 0) =
        ObservedWindow(WindowLayer.APPLICATION, TestPackages.RIGHT, rightBounds, display, focused)

    private fun assessment(vararg extra: ObservedWindow) =
        WindowSafetyPolicy.inspect(
            listOf(left(), right()) + extra.toList(),
            "com.hebi.dualtouch"
        )

    @Test fun acceptsExactlyTwoFocusedTestApps() {
        assertTrue(assessment().allowed)
        assertEquals(SafetyIssue.NONE, assessment().issue)
    }

    @Test fun rejectsOtherAppsEvenWhenTwoProbesArePresent() {
        val other = ObservedWindow(
            WindowLayer.APPLICATION, "com.random.app",
            ScreenRect(200, 100, 600, 700), focused = true
        )
        assertEquals(SafetyIssue.OTHER_APPLICATION, assessment(other).issue)
    }

    @Test fun rejectsKeyboardAndOtherOverlays() {
        val keyboard = ObservedWindow(
            WindowLayer.INPUT_METHOD, "com.keyboard",
            ScreenRect(0, 700, 1020, 1120)
        )
        assertEquals(SafetyIssue.KEYBOARD, assessment(keyboard).issue)
        val foreignOverlay = ObservedWindow(
            WindowLayer.ACCESSIBILITY, "com.other.accessibility",
            ScreenRect(0, 500, 100, 600)
        )
        assertEquals(SafetyIssue.ACCESSIBILITY_OVERLAY, assessment(foreignOverlay).issue)
    }

    @Test fun allowsOwnControllerOverlay() {
        val overlay = ObservedWindow(
            WindowLayer.ACCESSIBILITY, "com.hebi.dualtouch",
            ScreenRect(20, 100, 100, 160)
        )
        assertTrue(assessment(overlay).allowed)
    }

    @Test fun blocksWideSystemDialogButNotThinSystemBars() {
        val dialog = ObservedWindow(
            WindowLayer.SYSTEM, "android",
            ScreenRect(60, 200, 900, 850)
        )
        assertEquals(SafetyIssue.COVERED_BY_SYSTEM, assessment(dialog).issue)
        val bar = ObservedWindow(
            WindowLayer.SYSTEM, "android",
            ScreenRect(0, 1050, 1020, 1120)
        )
        assertTrue(assessment(bar).allowed)
    }

    @Test fun rejectsUnfocusedAndDifferentDisplay() {
        val noFocus = WindowSafetyPolicy.inspect(
            listOf(left(false), right(false)), "com.hebi.dualtouch"
        )
        assertEquals(SafetyIssue.NO_FOCUSED_TARGET, noFocus.issue)
        val different = WindowSafetyPolicy.inspect(
            listOf(left(display = 0), right(display = 2)), "com.hebi.dualtouch"
        )
        assertEquals(SafetyIssue.DIFFERENT_DISPLAY, different.issue)
    }

    @Test fun rejectsMissingOrUnknownWindows() {
        assertFalse(
            WindowSafetyPolicy.inspect(
                listOf(left()), "com.hebi.dualtouch"
            ).allowed
        )
        assertEquals(
            SafetyIssue.UNKNOWN_WINDOW,
            assessment(ObservedWindow(
                WindowLayer.OTHER, null, ScreenRect(0, 0, 100, 100)
            )).issue
        )
    }

    @Test fun generationInvalidatesPendingDispatches() {
        val generation = DispatchGeneration()
        val token = generation.current()
        assertTrue(generation.valid(token))
        generation.invalidate()
        assertFalse(generation.valid(token))
        assertTrue(generation.valid(generation.current()))
    }
}
