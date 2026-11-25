package com.hebi.dualtouch.core

import kotlin.math.max
import kotlin.math.min

/**
 * Only the two windows explicitly selected by the user are allowed.
 * Approvals are session-scoped and deliberately not stored across launches.
 */
data class AppPairApproval(
    val leftPackage: String,
    val rightPackage: String,
    val leftWindowId: Int,
    val rightWindowId: Int,
    val displayId: Int,
    val leftBounds: ScreenRect,
    val rightBounds: ScreenRect,
) {
    companion object {
        fun fromPair(pair: WindowPair): AppPairApproval = AppPairApproval(
            pair.left.packageName,
            pair.right.packageName,
            pair.left.windowId,
            pair.right.windowId,
            pair.left.displayId,
            pair.left.bounds,
            pair.right.bounds,
        )
    }

    fun matches(pair: WindowPair): Boolean =
        pair.left.packageName == leftPackage &&
            pair.right.packageName == rightPackage &&
            pair.left.windowId == leftWindowId &&
            pair.right.windowId == rightWindowId &&
            pair.left.displayId == displayId &&
            pair.right.displayId == displayId &&
            pair.left.bounds == leftBounds && pair.right.bounds == rightBounds
}

object AppPairPolicy {
    private val forbiddenExact = setOf(
        "android",
        "com.android.settings",
        "com.android.systemui",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.gms",
        "com.android.vending",
        "com.samsung.android.spay",
        "com.google.android.apps.walletnfcrel",
    )

    private val forbiddenParts = listOf(
        ".bank", ".wallet", ".payment", ".finance", ".password", ".authenticator",
        ".credential", ".security", ".identity", ".installer", ".permissioncontroller",
        ".spay", ".pay", ".otp",
    )

    fun isProtectedPackage(name: String, controllerPackage: String): Boolean {
        val normalized = name.lowercase()
        return normalized.isBlank() ||
            normalized == controllerPackage.lowercase() ||
            normalized in forbiddenExact ||
            forbiddenParts.any { normalized.contains(it) }
    }

    fun select(
        windows: List<ObservedWindow>,
        controllerPackage: String,
        workArea: SplitWorkArea,
    ): SafetyAssessment {
        val apps = windows.filter { it.layer == WindowLayer.APPLICATION }
        if (apps.any { it.pictureInPicture }) return SafetyAssessment(null, SafetyIssue.PICTURE_IN_PICTURE)
        if (apps.size > 2) return SafetyAssessment(null, SafetyIssue.EXTRA_APPLICATION_WINDOWS)
        if (apps.size != 2) return SafetyAssessment(null, SafetyIssue.MISSING_PAIR)
        // A root not queried before the read deadline is a timeout, not a
        // confirmed missing identity. Neither state may authorize a pair.
        if (apps.any {
                it.packageName.isNullOrBlank() &&
                    it.inputInspection != InputInspection.TIME_BUDGET_EXCEEDED
            }) {
            return SafetyAssessment(null, SafetyIssue.INPUT_INSPECTION_INCOMPLETE)
        }
        if (apps.any { app ->
                app.packageName?.takeIf { it.isNotBlank() }?.let {
                    isProtectedPackage(it, controllerPackage)
                } == true
            }) {
            return SafetyAssessment(null, SafetyIssue.PROTECTED_APP)
        }
        WindowSafetyPolicy.inputIssue(apps)?.let { return SafetyAssessment(null, it) }
        // Retain an explicit final identity check for every accepted snapshot.
        if (apps.any { it.packageName.isNullOrBlank() }) {
            return SafetyAssessment(null, SafetyIssue.INPUT_INSPECTION_INCOMPLETE)
        }
        if (apps.any { it.windowId < 0 } || apps[0].windowId == apps[1].windowId) {
            return SafetyAssessment(null, SafetyIssue.MISSING_PAIR)
        }
        if (apps[0].displayId != apps[1].displayId) {
            return SafetyAssessment(null, SafetyIssue.DIFFERENT_DISPLAY)
        }
        if (apps[0].displayId != 0) {
            return SafetyAssessment(null, SafetyIssue.UNSUPPORTED_DISPLAY)
        }
        val first = apps[0]
        val second = apps[1]
        val dividers = windows.filter { it.layer == WindowLayer.SPLIT_DIVIDER && it.displayId == first.displayId }
        if (dividers.size > 1) return SafetyAssessment(null, SafetyIssue.SPLIT_DIVIDER_MISMATCH)
        val pair = SplitGeometry.detect(
            WindowSnapshot(first.packageName!!, first.bounds, first.displayId, first.windowId),
            WindowSnapshot(second.packageName!!, second.bounds, second.displayId, second.windowId),
            dividers.singleOrNull()?.bounds,
            workArea,
        ) ?: return SafetyAssessment(null, if (dividers.isEmpty()) {
            SafetyIssue.NOT_SPLIT_LAYOUT
        } else {
            SafetyIssue.SPLIT_DIVIDER_MISMATCH
        })

        if (apps.none { it.focused }) {
            return SafetyAssessment(null, SafetyIssue.NO_FOCUSED_TARGET)
        }
        if (windows.any { it.layer == WindowLayer.INPUT_METHOD }) {
            return SafetyAssessment(null, SafetyIssue.KEYBOARD)
        }
        if (windows.any {
            it.layer == WindowLayer.ACCESSIBILITY &&
                it.packageName != controllerPackage
        }) {
            return SafetyAssessment(null, SafetyIssue.ACCESSIBILITY_OVERLAY)
        }
        if (windows.any {
            it.layer == WindowLayer.WINDOW_CONTROL && it.displayId == first.displayId &&
                (!it.bounds.isValid || it.bounds.overlaps(pair.left.bounds) ||
                    it.bounds.overlaps(pair.right.bounds))
        }) {
            return SafetyAssessment(null, SafetyIssue.BLOCKING_WINDOW_CONTROL)
        }
        if (windows.any { it.layer == WindowLayer.OTHER }) {
            return SafetyAssessment(null, SafetyIssue.UNKNOWN_WINDOW)
        }
        if (windows.any { it.layer == WindowLayer.SYSTEM && (
            covers(it.bounds, pair.left.bounds) || covers(it.bounds, pair.right.bounds)
        ) }) {
            return SafetyAssessment(null, SafetyIssue.COVERED_BY_SYSTEM)
        }
        return SafetyAssessment(pair, SafetyIssue.NONE)
    }

    fun verify(
        windows: List<ObservedWindow>,
        controllerPackage: String,
        workArea: SplitWorkArea,
        approval: AppPairApproval?,
    ): SafetyAssessment {
        if (approval == null) {
            return SafetyAssessment(null, SafetyIssue.UNAPPROVED_PAIR)
        }
        val assessment = select(windows, controllerPackage, workArea)
        return if (!assessment.allowed || assessment.pair == null) {
            assessment
        } else if (!approval.matches(assessment.pair)) {
            SafetyAssessment(null, SafetyIssue.PAIR_CHANGED)
        } else {
            assessment
        }
    }

    private fun covers(overlay: ScreenRect, target: ScreenRect): Boolean {
        if (!overlay.isValid || !target.isValid) return false
        val horizontal = (min(overlay.right, target.right) -
            max(overlay.left, target.left)).coerceAtLeast(0)
        val vertical = (min(overlay.bottom, target.bottom) -
            max(overlay.top, target.top)).coerceAtLeast(0)
        return horizontal.toLong() * vertical >=
            target.width.toLong() * target.height / 5
    }
}


internal class PairCalibration {
    private var pair: WindowPair? = null
    private val offsets = mutableMapOf<TouchSide, CalibrationOffset>()

    fun setPair(current: WindowPair?) {
        if (pair == current) return
        pair = current
        offsets.clear()
    }

    fun offsetFor(current: WindowPair, side: TouchSide): CalibrationOffset =
        if (current == pair) offsets[side] ?: CalibrationOffset.ZERO else CalibrationOffset.ZERO

    fun hasSaved(current: WindowPair, side: TouchSide): Boolean = current == pair && offsets.containsKey(side)

    fun save(current: WindowPair, side: TouchSide, offset: CalibrationOffset): Boolean {
        if (current != pair || !offset.isSafe()) return false
        offsets[side] = offset
        return true
    }

    fun reset(current: WindowPair, side: TouchSide): Boolean {
        if (current != pair) return false
        offsets.remove(side)
        return true
    }
}
