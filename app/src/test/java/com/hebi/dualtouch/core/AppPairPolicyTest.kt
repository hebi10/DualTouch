package com.hebi.dualtouch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPairPolicyTest {
    private val left = ScreenRect(0, 48, 500, 1100)
    private val right = ScreenRect(520, 48, 1020, 1100)
    private val owner = "com.hebi.dualtouch"
    private val workArea = SplitWorkArea(
        ScreenRect(0, 0, 1020, 1140), ScreenRect(0, 48, 1020, 1100), 24,
    )

    private fun select(windows: List<ObservedWindow>) =
        AppPairPolicy.select(windows, owner, workArea)

    private fun verify(windows: List<ObservedWindow>, approval: AppPairApproval?) =
        AppPairPolicy.verify(windows, owner, workArea, approval)

    private fun window(pkg: String, id: Int, bounds: ScreenRect, focused: Boolean) =
        ObservedWindow(WindowLayer.APPLICATION, pkg, bounds,
            windowId = id, focused = focused)

    private fun pair() = listOf(
        window("com.example.notes", 17, left, true),
        window("com.example.browser", 29, right, false),
    )

    @Test fun selectsActualThirdPartyAppPair() {
        val result = select(pair())
        assertTrue(result.allowed)
        assertEquals("com.example.notes", result.pair!!.left.packageName)
        assertEquals("com.example.browser", result.pair.right.packageName)
    }

    @Test fun permitsTwoInstancesOfSamePackageOnlyWithDistinctWindowIds() {
        val windows = listOf(
            window("com.example.notes", 17, left, true),
            window("com.example.notes", 29, right, false)
        )
        assertTrue(select(windows).allowed)
        assertFalse(select(windows.map {
            it.copy(windowId = 17)
        }).allowed)
    }

    @Test fun requiresExplicitSessionApproval() {
        val candidates = pair()
        assertEquals(SafetyIssue.UNAPPROVED_PAIR,
            verify(candidates, null).issue)
        val approved = AppPairApproval.fromPair(
            select(candidates).pair!!)
        assertTrue(verify(candidates, approved).allowed)
        assertEquals(SafetyIssue.PAIR_CHANGED,
            verify(candidates.map {
                if (it.windowId == 29) it.copy(windowId = 31) else it
            }, approved).issue)
    }

    @Test fun rejectsProtectedSystemAndFinanceApps() {
        assertEquals(SafetyIssue.PROTECTED_APP,
            select(
                listOf(pair()[0], window("com.android.settings", 29, right, false))
            ).issue)
        assertEquals(SafetyIssue.PROTECTED_APP,
            select(
                listOf(pair()[0], window("com.example.bank", 29, right, false))
            ).issue)
        assertTrue(AppPairPolicy.isProtectedPackage(owner, owner))
    }

    @Test fun revokesApprovalWhenBoundsChangeWithoutChangingWindowId() {
        val candidates = pair()
        val approval = AppPairApproval.fromPair(select(candidates).pair!!)
        val moved = candidates.map {
            it.copy(bounds = it.bounds.copy(top = 60, bottom = 1080))
        }
        assertEquals(SafetyIssue.PAIR_CHANGED,
            verify(moved, approval).issue)
    }

    @Test fun rejectsTwoWindowsOnTheSameSecondaryDisplay() {
        assertEquals(SafetyIssue.UNSUPPORTED_DISPLAY,
            select(pair().map { it.copy(displayId = 2) }).issue)
    }

    @Test fun rejectsPasswordOrEditableUi() {
        val sensitive = pair().map {
            if (it.windowId == 29) it.copy(inputInspection = InputInspection.SENSITIVE) else it
        }
        assertEquals(SafetyIssue.SENSITIVE_INPUT,
            select(sensitive).issue)
    }

    @Test fun blocksKeyboardAndUnrecognizedOverlays() {
        assertEquals(SafetyIssue.KEYBOARD,
            select(pair() + ObservedWindow(
                WindowLayer.INPUT_METHOD, "com.keyboard",
                ScreenRect(0, 700, 1020, 1100))).issue)
        assertEquals(SafetyIssue.ACCESSIBILITY_OVERLAY,
            select(pair() + ObservedWindow(
                WindowLayer.ACCESSIBILITY, "com.other.access",
                ScreenRect(100, 100, 200, 200))).issue)
    }

    @Test fun reportsOpenKeyboardWhenBrowserInspectionIsIncomplete() {
        val (first, second) = pair()
        val keyboard = ObservedWindow(WindowLayer.INPUT_METHOD, null, ScreenRect(0, 700, 1020, 1100))
        val result = select(listOf(first, second.copy(inputInspection = InputInspection.UNAVAILABLE), keyboard))
        assertEquals(SafetyIssue.KEYBOARD, result.issue)
        assertFalse(result.allowed)
    }

    @Test fun rejectsUnexpectedThirdWindowOrDifferentDisplays() {
        assertEquals(SafetyIssue.EXTRA_APPLICATION_WINDOWS,
            select(pair() + window("com.example.chat", 30,
                ScreenRect(0, 0, 100, 100), false)).issue)
        assertEquals(SafetyIssue.DIFFERENT_DISPLAY,
            select(pair().map {
                if (it.windowId == 29) it.copy(displayId = 1) else it
            }).issue)
    }

    @Test fun rejectsInvalidOverlapAndNoFocus() {
        val noFocus = pair().map { it.copy(focused = false) }
        assertEquals(SafetyIssue.NO_FOCUSED_TARGET,
            select(noFocus).issue)
        val overlap = pair().map {
            if (it.windowId == 29) it.copy(bounds = ScreenRect(300, 48, 800, 1100))
            else it
        }
        assertEquals(SafetyIssue.SPLIT_DIVIDER_MISMATCH,
            select(overlap + ObservedWindow(WindowLayer.SPLIT_DIVIDER, "android",
                ScreenRect(495, 400, 510, 600))).issue)
    }

    @Test fun rejectsInvalidOrAmbiguousReportedDivider() {
        val smaller = window("com.example.notes", 17, ScreenRect(0, 48, 320, 1100), true)
        val larger = window("com.example.browser", 29, ScreenRect(340, 48, 1020, 1100), false)
        val divider = ObservedWindow(WindowLayer.SPLIT_DIVIDER, "android",
            ScreenRect(320, 400, 340, 600))
        val windows = listOf(smaller, larger)
        assertTrue(select(windows).allowed)
        assertTrue(select(windows + divider.copy(displayId = 1)).allowed)
        assertEquals(SafetyIssue.SPLIT_DIVIDER_MISMATCH, select(windows + divider + divider).issue)
        assertEquals(SafetyIssue.SPLIT_DIVIDER_MISMATCH, select(
            windows + divider.copy(bounds = ScreenRect(700, 400, 720, 600))).issue)
        assertTrue(select(windows + divider).allowed)

        val upper = smaller.copy(bounds = ScreenRect(0, 48, 1020, 380))
        val lower = larger.copy(bounds = ScreenRect(0, 400, 1020, 1100))
        assertEquals(SplitOrientation.VERTICAL, select(
            listOf(lower, upper, divider.copy(bounds = ScreenRect(400, 380, 620, 400)))
        ).pair?.orientation)
    }

    @Test fun neverAllowsWindowControlsToOverlapEitherTarget() {
        val control = ObservedWindow(WindowLayer.WINDOW_CONTROL, null,
            ScreenRect(100, 0, 200, 48))
        assertTrue(select(pair() + control).allowed)
        assertEquals(SafetyIssue.BLOCKING_WINDOW_CONTROL, select(
            pair() + control.copy(bounds = ScreenRect(100, 0, 200, 80))).issue)
        assertEquals(SafetyIssue.UNKNOWN_WINDOW, select(
            pair() + control.copy(layer = WindowLayer.OTHER)).issue)
    }

    @Test fun treatsOwnOverlayAsAllowedButUnknownSystemDialogAsUnsafe() {
        assertTrue(select(pair() + ObservedWindow(
            WindowLayer.ACCESSIBILITY, owner, ScreenRect(20, 80, 80, 140)
        )).allowed)
        assertEquals(SafetyIssue.COVERED_BY_SYSTEM,
            select(pair() + ObservedWindow(
                WindowLayer.SYSTEM, "android", ScreenRect(50, 200, 990, 780)
            )).issue)
    }

    @Test fun rejectsSmallAlignedPopupsAndLargeGapsWithoutDivider() {
        val small = listOf(
            window("com.example.notes", 17, ScreenRect(20, 100, 260, 450), true),
            window("com.example.browser", 29, ScreenRect(270, 100, 510, 450), false),
        )
        assertEquals(SafetyIssue.NOT_SPLIT_LAYOUT, select(small).issue)
        val farApart = pair().toMutableList()
        farApart[0] = farApart[0].copy(bounds = left.copy(right = 350))
        assertEquals(SafetyIssue.NOT_SPLIT_LAYOUT, select(farApart).issue)
    }

    @Test fun blocksPictureInPictureAndDistinguishesUnavailableAndOversizedTrees() {
        val (first, second) = pair()
        assertEquals(SafetyIssue.PICTURE_IN_PICTURE,
            select(listOf(first, second.copy(pictureInPicture = true))).issue)
        assertEquals(SafetyIssue.INPUT_INSPECTION_INCOMPLETE,
            select(listOf(first, second.copy(packageName = null))).issue)
        assertEquals(SafetyIssue.INPUT_INSPECTION_LIMIT,
            select(listOf(first, second.copy(inputInspection = InputInspection.LIMIT_REACHED))).issue)
    }

    @Test fun detectsUnevenSplitFillingTheWorkAreaWithSystemInsets() {
        val workArea = SplitWorkArea(
            ScreenRect(0, 0, 1600, 1020), ScreenRect(0, 0, 1600, 950), 32,
        )
        val windows = listOf(
            window("com.example.notes", 17, ScreenRect(0, 0, 1050, 1020), true),
            window("com.example.browser", 29, ScreenRect(1070, 0, 1600, 950), false),
        )
        assertTrue(AppPairPolicy.select(windows, owner, workArea).allowed)
    }
    @Test fun displayedApprovalStillRequiresFreshSafetyAfterMenuRemoval() {
        val apps = pair()
        val ownMenu = ObservedWindow(WindowLayer.ACCESSIBILITY, owner, ScreenRect(20, 80, 300, 400))
        val approval = AppPairApproval.fromPair(select(apps + ownMenu).pair!!)
        assertTrue(verify(apps, approval).allowed)
        assertEquals(SafetyIssue.ACCESSIBILITY_OVERLAY,
            verify(apps + ownMenu.copy(packageName = null), approval).issue)
        assertEquals(SafetyIssue.ACCESSIBILITY_OVERLAY,
            verify(apps + ownMenu.copy(packageName = "com.example.other"), approval).issue)
    }

    @Test fun displayedApprovalCannotBeAppliedAfterControllerReplacesOneWindow() {
        val apps = pair()
        val approval = AppPairApproval.fromPair(select(apps).pair!!)
        assertEquals(SafetyIssue.PROTECTED_APP,
            verify(listOf(apps.first(), apps.last().copy(packageName = owner)), approval).issue)
        assertEquals(SafetyIssue.PAIR_CHANGED,
            verify(listOf(apps.first(), apps.last().copy(windowId = 42)), approval).issue)
    }

    @Test fun deadlineSkippedIdentityIsTimeoutAndNeverApproves() {
        val (first, second) = pair()
        val windows = listOf(first, second.copy(packageName = null,
            inputInspection = InputInspection.TIME_BUDGET_EXCEEDED))
        val result = select(windows)
        assertEquals(SafetyIssue.INPUT_INSPECTION_TIMEOUT, result.issue)
        assertFalse(result.allowed)
        assertEquals(null, result.pair)
        val approval = AppPairApproval.fromPair(select(pair()).pair!!)
        assertFalse(verify(windows, approval).allowed)
    }

    @Test fun actuallyMissingIdentityRemainsIncompleteEvenWithAnotherTimeout() {
        val (first, second) = pair()
        val result = select(listOf(
            first.copy(packageName = null, inputInspection = InputInspection.UNAVAILABLE),
            second.copy(packageName = null, inputInspection = InputInspection.TIME_BUDGET_EXCEEDED)
        ))
        assertEquals(SafetyIssue.INPUT_INSPECTION_INCOMPLETE, result.issue)
        assertFalse(result.allowed)
    }

    @Test fun timeoutNeverOverridesKnownProtectedOrSensitiveApplication() {
        val (first, second) = pair()
        val timedOut = second.copy(packageName = null,
            inputInspection = InputInspection.TIME_BUDGET_EXCEEDED)
        val protected = select(listOf(first.copy(packageName = owner), timedOut))
        assertEquals(SafetyIssue.PROTECTED_APP, protected.issue)
        assertFalse(protected.allowed)
        val sensitive = select(listOf(first.copy(inputInspection = InputInspection.SENSITIVE), timedOut))
        assertEquals(SafetyIssue.SENSITIVE_INPUT, sensitive.issue)
        assertFalse(sensitive.allowed)
    }
}
