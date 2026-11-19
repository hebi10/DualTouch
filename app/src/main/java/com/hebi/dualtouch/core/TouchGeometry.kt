package com.hebi.dualtouch.core

import kotlin.math.max
import kotlin.math.min

data class ScreenRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isValid: Boolean get() = width > 0 && height > 0

    fun contains(x: Float, y: Float, inset: Float = 0f): Boolean =
        isValid && x >= left + inset && x < right - inset &&
            y >= top + inset && y < bottom - inset
}

data class TapPoint(val x: Float, val y: Float)

data class CalibrationOffset(val dx: Float, val dy: Float) {
    fun isSafe(): Boolean =
        dx.isFinite() && dy.isFinite() &&
            kotlin.math.abs(dx) <= 0.2f && kotlin.math.abs(dy) <= 0.2f

    companion object {
        val ZERO = CalibrationOffset(0f, 0f)
    }
}

object CalibrationMath {
    fun fromReferencePoints(
        source: ScreenRect,
        target: ScreenRect,
        leftReference: TapPoint,
        rightReference: TapPoint,
    ): CalibrationOffset? {
        if (!source.contains(leftReference.x, leftReference.y) ||
            !target.contains(rightReference.x, rightReference.y)
        ) return null

        val sourceX = (leftReference.x - source.left) / source.width
        val sourceY = (leftReference.y - source.top) / source.height
        val targetX = (rightReference.x - target.left) / target.width
        val targetY = (rightReference.y - target.top) / target.height
        return CalibrationOffset(targetX - sourceX, targetY - sourceY)
            .takeIf { it.isSafe() }
    }
}


data class WindowSnapshot(val packageName: String, val bounds: ScreenRect)

data class WindowPair(val left: WindowSnapshot, val right: WindowSnapshot)

object TestPackages {
    const val LEFT = "com.hebi.dualtouch.probeleft"
    const val RIGHT = "com.hebi.dualtouch.proberight"
}

object WindowPairResolver {
    fun resolve(windows: List<WindowSnapshot>): WindowPair? {
        val left = windows.filter { it.packageName == TestPackages.LEFT }
        val right = windows.filter { it.packageName == TestPackages.RIGHT }
        if (left.size != 1 || right.size != 1) return null

        val a = left.single()
        val b = right.single()
        if (!a.bounds.isValid || !b.bounds.isValid) return null
        if (a.bounds.right > b.bounds.left) return null

        val verticalOverlap =
            min(a.bounds.bottom, b.bounds.bottom) - max(a.bounds.top, b.bounds.top)
        if (verticalOverlap <= 0) return null

        val overlapFraction = verticalOverlap.toFloat() /
            min(a.bounds.height, b.bounds.height).toFloat()
        if (overlapFraction < 0.8f) return null

        val widthFraction = min(a.bounds.width, b.bounds.width).toFloat() /
            max(a.bounds.width, b.bounds.width).toFloat()
        if (widthFraction < 0.75f) return null

        return WindowPair(a, b)
    }
}

object CoordinateMapper {
    fun map(
        source: ScreenRect,
        target: ScreenRect,
        x: Float,
        y: Float,
        safeInsetPx: Float,
        calibration: CalibrationOffset = CalibrationOffset.ZERO,
    ): TapPoint? {
        if (!source.isValid || !target.isValid || safeInsetPx < 0f ||
            !calibration.isSafe()
        ) return null
        if (!source.contains(x, y, safeInsetPx)) return null

        val relativeX = (x - source.left) / source.width.toFloat()
        val relativeY = (y - source.top) / source.height.toFloat()
        val targetX = target.left + (relativeX + calibration.dx) * target.width
        val targetY = target.top + (relativeY + calibration.dy) * target.height

        return TapPoint(targetX, targetY)
            .takeIf { target.contains(it.x, it.y, safeInsetPx) }
    }
}
