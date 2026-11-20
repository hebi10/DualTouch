package com.hebi.dualtouch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPairPolicyTest {
    private val left = ScreenRect(0, 48, 500, 1100)
    private val right = ScreenRect(520, 48, 1020, 1100)
    private val owner = "com.hebi.dualtouch"

    private fun window(pkg: String, id: Int, bounds: ScreenRect, focused: Boolean) =
        ObservedWindow(WindowLayer.APPLICATION, pkg, bounds,
            windowId = id, focused = focused)

    private fun pair() = listOf(
        window("com.example.notes", 17, left, true),
        window("com.example.browser", 29, right, false),
    )

    @Test fun selectsActualThirdPartyAppPair() {
        val result = AppPairPolicy.select(pair(), owner)
        assertTrue(result.allowed)
        assertEquals("com.example.notes", result.pair!!.left.packageName)
        assertEquals("com.example.browser", result.pair.right.packageName)
    }

    @Test fun permitsTwoInstancesOfSamePackageOnlyWithDistinctWindowIds() {
        val windows = listOf(
            window("com.example.notes", 17, left, true),
            window("com.example.notes", 29, right, false)
        )
        assertTrue(AppPairPolicy.select(windows, owner).allowed)
        assertFalse(AppPairPolicy.select(windows.map {
            it.copy(windowId = 17)
        }, owner).allowed)
    }

    @Test fun requiresExplicitSessionApproval() {
        val candidates = pair()
        assertEquals(SafetyIssue.UNAPPROVED_PAIR,
            AppPairPolicy.verify(candidates, owner, null).issue)
        val approved = AppPairApproval.fromPair(
            AppPairPolicy.select(candidates, owner).pair!!)
        assertTrue(AppPairPolicy.verify(candidates, owner, approved).allowed)
        assertEquals(SafetyIssue.PAIR_CHANGED,
            AppPairPolicy.verify(candidates.map {
                if (it.windowId == 29) it.copy(windowId = 31) else it
            }, owner, approved).issue)
    }

    @Test fun rejectsProtectedSystemAndFinanceApps() {
        assertEquals(SafetyIssue.PROTECTED_APP,
            AppPairPolicy.select(
                listOf(pair()[0], window("com.android.settings", 29, right, false)),
                owner).issue)
        assertEquals(SafetyIssue.PROTECTED_APP,
            AppPairPolicy.select(
                listOf(pair()[0], window("com.example.bank", 29, right, false)),
                owner).issue)
        assertTrue(AppPairPolicy.isProtectedPackage(owner, owner))
    }

    @Test fun revokesApprovalWhenBoundsChangeWithoutChangingWindowId() {
        val candidates = pair()
        val approval = AppPairApproval.fromPair(AppPairPolicy.select(candidates, owner).pair!!)
        val moved = candidates.map {
            it.copy(bounds = it.bounds.copy(top = 60, bottom = 1080))
        }
        assertEquals(SafetyIssue.PAIR_CHANGED,
            AppPairPolicy.verify(moved, owner, approval).issue)
    }

    @Test fun rejectsTwoWindowsOnTheSameSecondaryDisplay() {
        assertEquals(SafetyIssue.UNSUPPORTED_DISPLAY,
            AppPairPolicy.select(pair().map { it.copy(displayId = 2) }, owner).issue)
    }

    @Test fun rejectsPasswordOrEditableUi() {
        val sensitive = pair().map {
            if (it.windowId == 29) it.copy(sensitiveInput = true) else it
        }
        assertEquals(SafetyIssue.SENSITIVE_INPUT,
            AppPairPolicy.select(sensitive, owner).issue)
    }

    @Test fun blocksKeyboardAndUnrecognizedOverlays() {
        assertEquals(SafetyIssue.KEYBOARD,
            AppPairPolicy.select(pair() + ObservedWindow(
                WindowLayer.INPUT_METHOD, "com.keyboard",
                ScreenRect(0, 700, 1020, 1100)), owner).issue)
        assertEquals(SafetyIssue.ACCESSIBILITY_OVERLAY,
            AppPairPolicy.select(pair() + ObservedWindow(
                WindowLayer.ACCESSIBILITY, "com.other.access",
                ScreenRect(100, 100, 200, 200)), owner).issue)
    }

    @Test fun rejectsUnexpectedThirdWindowOrDifferentDisplays() {
        assertEquals(SafetyIssue.MISSING_PAIR,
            AppPairPolicy.select(pair() + window("com.example.chat", 30,
                ScreenRect(0, 0, 100, 100), false), owner).issue)
        assertEquals(SafetyIssue.DIFFERENT_DISPLAY,
            AppPairPolicy.select(pair().map {
                if (it.windowId == 29) it.copy(displayId = 1) else it
            }, owner).issue)
    }

    @Test fun rejectsInvalidOverlapAndNoFocus() {
        val noFocus = pair().map { it.copy(focused = false) }
        assertEquals(SafetyIssue.NO_FOCUSED_TARGET,
            AppPairPolicy.select(noFocus, owner).issue)
        val overlap = pair().map {
            if (it.windowId == 29) it.copy(bounds = ScreenRect(300, 48, 800, 1100))
            else it
        }
        assertEquals(SafetyIssue.MISSING_PAIR,
            AppPairPolicy.select(overlap, owner).issue)
    }

    @Test fun treatsOwnOverlayAsAllowedButUnknownSystemDialogAsUnsafe() {
        assertTrue(AppPairPolicy.select(pair() + ObservedWindow(
            WindowLayer.ACCESSIBILITY, owner, ScreenRect(20, 80, 80, 140)
        ), owner).allowed)
        assertEquals(SafetyIssue.COVERED_BY_SYSTEM,
            AppPairPolicy.select(pair() + ObservedWindow(
                WindowLayer.SYSTEM, "android", ScreenRect(50, 200, 990, 780)
            ), owner).issue)
    }
}

