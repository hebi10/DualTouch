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
    ): SafetyAssessment {
        val apps = windows.filter { it.layer == WindowLayer.APPLICATION }
        if (apps.size != 2 || apps.any { it.packageName.isNullOrBlank() }) {
            return SafetyAssessment(null, SafetyIssue.MISSING_PAIR)
        }
        if (apps.any { isProtectedPackage(it.packageName!!, controllerPackage) }) {
            return SafetyAssessment(null, SafetyIssue.PROTECTED_APP)
        }
        if (apps.any { it.sensitiveInput }) {
            return SafetyAssessment(null, SafetyIssue.SENSITIVE_INPUT)
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
        val ordered = apps.sortedBy { it.bounds.left }
        val a = ordered[0]
        val b = ordered[1]
        val left = a.bounds
        val right = b.bounds
        if (!left.isValid || !right.isValid || left.right > right.left) {
            return SafetyAssessment(null, SafetyIssue.MISSING_PAIR)
        }
        val overlap = (min(left.bottom, right.bottom) -
            max(left.top, right.top)).coerceAtLeast(0)
        if (overlap.toFloat() / min(left.height, right.height) < 0.8f) {
            return SafetyAssessment(null, SafetyIssue.MISSING_PAIR)
        }
        if (min(left.width, right.width).toFloat() /
            max(left.width, right.width) < 0.75f) {
            return SafetyAssessment(null, SafetyIssue.MISSING_PAIR)
        }
        if (apps.none { it.focused }) {
            return SafetyAssessment(null, SafetyIssue.NO_FOCUSED_TARGET)
        }

        val pair = WindowPair(
            WindowSnapshot(a.packageName!!, left, a.displayId, a.windowId),
            WindowSnapshot(b.packageName!!, right, b.displayId, b.windowId),
        )
        if (windows.any { it.layer == WindowLayer.INPUT_METHOD }) {
            return SafetyAssessment(null, SafetyIssue.KEYBOARD)
        }
        if (windows.any {
            it.layer == WindowLayer.ACCESSIBILITY &&
                it.packageName != controllerPackage
        }) {
            return SafetyAssessment(null, SafetyIssue.ACCESSIBILITY_OVERLAY)
        }
        if (windows.any { it.layer == WindowLayer.OTHER }) {
            return SafetyAssessment(null, SafetyIssue.UNKNOWN_WINDOW)
        }
        if (windows.any { it.layer == WindowLayer.SYSTEM && (
            covers(it.bounds, left) || covers(it.bounds, right)
        ) }) {
            return SafetyAssessment(null, SafetyIssue.COVERED_BY_SYSTEM)
        }
        return SafetyAssessment(pair, SafetyIssue.NONE)
    }

    fun verify(
        windows: List<ObservedWindow>,
        controllerPackage: String,
        approval: AppPairApproval?,
    ): SafetyAssessment {
        if (approval == null) {
            return SafetyAssessment(null, SafetyIssue.UNAPPROVED_PAIR)
        }
        val assessment = select(windows, controllerPackage)
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

