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

    fun overlaps(other: ScreenRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

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


data class WindowSnapshot(val packageName: String, val bounds: ScreenRect, val displayId: Int = 0, val windowId: Int = -1)

enum class SplitOrientation { HORIZONTAL, VERTICAL }

data class WindowPair(
    val left: WindowSnapshot,
    val right: WindowSnapshot,
    val orientation: SplitOrientation = SplitOrientation.HORIZONTAL,
)

enum class TouchSide { LEFT, RIGHT }

fun WindowPair.source(side: TouchSide): WindowSnapshot =
    if (side == TouchSide.LEFT) left else right

fun WindowPair.target(side: TouchSide): WindowSnapshot =
    if (side == TouchSide.LEFT) right else left

data class SplitWorkArea(
    val display: ScreenRect,
    val usable: ScreenRect,
    val tolerancePx: Int,
) {
    private fun nearEdge(position: Int, outer: Int, inner: Int): Boolean =
        position in min(outer, inner) - tolerancePx..max(outer, inner) + tolerancePx

    private fun fillsWidth(bounds: ScreenRect): Boolean =
        nearEdge(bounds.left, display.left, usable.left) &&
            nearEdge(bounds.right, display.right, usable.right)

    private fun fillsHeight(bounds: ScreenRect): Boolean =
        nearEdge(bounds.top, display.top, usable.top) &&
            nearEdge(bounds.bottom, display.bottom, usable.bottom)

    fun supports(first: ScreenRect, second: ScreenRect, orientation: SplitOrientation): Boolean {
        if (!display.isValid || !usable.isValid || tolerancePx < 1) return false
        return when (orientation) {
            SplitOrientation.HORIZONTAL ->
                first.width >= usable.width / 5 && second.width >= usable.width / 5 &&
                    second.left - first.right in 0..min(tolerancePx, usable.width / 12) &&
                    nearEdge(first.left, display.left, usable.left) &&
                    nearEdge(second.right, display.right, usable.right) &&
                    fillsHeight(first) && fillsHeight(second)
            SplitOrientation.VERTICAL ->
                first.height >= usable.height / 5 && second.height >= usable.height / 5 &&
                    second.top - first.bottom in 0..min(tolerancePx, usable.height / 12) &&
                    nearEdge(first.top, display.top, usable.top) &&
                    nearEdge(second.bottom, display.bottom, usable.bottom) &&
                    fillsWidth(first) && fillsWidth(second)
        }
    }
}

object SplitGeometry {
    fun detect(
        first: WindowSnapshot,
        second: WindowSnapshot,
        systemDivider: ScreenRect? = null,
        workArea: SplitWorkArea? = null,
    ): WindowPair? {
        val a = first.bounds
        val b = second.bounds
        if (!a.isValid || !b.isValid || first.displayId != second.displayId) return null

        val left = if (a.left <= b.left) first else second
        val right = if (left === first) second else first
        if (left.bounds.right <= right.bounds.left &&
            overlapFraction(left.bounds.top, left.bounds.bottom, right.bounds.top, right.bounds.bottom) >= 0.8f &&
            hasSplitEvidence(left.bounds, right.bounds, systemDivider, workArea, SplitOrientation.HORIZONTAL)
        ) return WindowPair(left, right, SplitOrientation.HORIZONTAL)

        val upper = if (a.top <= b.top) first else second
        val lower = if (upper === first) second else first
        if (upper.bounds.bottom <= lower.bounds.top &&
            overlapFraction(upper.bounds.left, upper.bounds.right, lower.bounds.left, lower.bounds.right) >= 0.8f &&
            hasSplitEvidence(upper.bounds, lower.bounds, systemDivider, workArea, SplitOrientation.VERTICAL)
        ) return WindowPair(upper, lower, SplitOrientation.VERTICAL)
        return null
    }

    private fun hasSplitEvidence(
        first: ScreenRect,
        second: ScreenRect,
        divider: ScreenRect?,
        workArea: SplitWorkArea?,
        orientation: SplitOrientation,
    ): Boolean {
        if (divider == null) return workArea?.supports(first, second, orientation) == true
        if (!divider.isValid) return false
        val x = divider.left + divider.width / 2
        val y = divider.top + divider.height / 2
        return when (orientation) {
            SplitOrientation.HORIZONTAL -> divider.height >= divider.width &&
                x in first.right..second.left && y in max(first.top, second.top) until min(first.bottom, second.bottom)
            SplitOrientation.VERTICAL -> divider.width >= divider.height &&
                y in first.bottom..second.top && x in max(first.left, second.left) until min(first.right, second.right)
        }
    }

    private fun overlapFraction(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Float {
        val length = (min(aEnd, bEnd) - max(aStart, bStart)).coerceAtLeast(0)
        val denominator = min(aEnd - aStart, bEnd - bStart)
        return if (denominator <= 0) 0f else length.toFloat() / denominator
    }
}

enum class GuideAxis { VERTICAL, HORIZONTAL }

object CalibrationGuide {
    const val MIN_PERCENT = 20
    const val MAX_PERCENT = 80
    const val DEFAULT_PERCENT = 50

    fun clampPercent(value: Int): Int = value.coerceIn(MIN_PERCENT, MAX_PERCENT)

    fun defaultAxis(orientation: SplitOrientation): GuideAxis =
        if (orientation == SplitOrientation.HORIZONTAL) GuideAxis.VERTICAL
        else GuideAxis.HORIZONTAL

    fun reference(bounds: ScreenRect, axis: GuideAxis, percent: Int): TapPoint? {
        if (!bounds.isValid) return null
        val fraction = clampPercent(percent) / 100f
        return when (axis) {
            GuideAxis.VERTICAL -> TapPoint(
                bounds.left + bounds.width * fraction,
                bounds.top + bounds.height * 0.5f
            )
            GuideAxis.HORIZONTAL -> TapPoint(
                bounds.left + bounds.width * 0.5f,
                bounds.top + bounds.height * fraction
            )
        }
    }

    fun line(bounds: ScreenRect, axis: GuideAxis, percent: Int, thickness: Int): ScreenRect? {
        val point = reference(bounds, axis, percent) ?: return null
        val width = thickness.coerceAtLeast(1)
        return when (axis) {
            GuideAxis.VERTICAL -> ScreenRect(
                point.x.toInt() - width / 2,
                bounds.top,
                point.x.toInt() - width / 2 + width,
                bounds.bottom
            )
            GuideAxis.HORIZONTAL -> ScreenRect(
                bounds.left,
                point.y.toInt() - width / 2,
                bounds.right,
                point.y.toInt() - width / 2 + width
            )
        }
    }
}

object ThumbReferenceMotion {
    fun moveSourceTogether(
        source: ScreenRect,
        target: ScreenRect,
        requestedSource: TapPoint,
        calibration: CalibrationOffset,
        safeInsetPx: Float,
    ): Pair<TapPoint, TapPoint>? {
        val automatic = CoordinateMapper.map(
            source, target,
            requestedSource.x, requestedSource.y,
            safeInsetPx, calibration,
        ) ?: return null
        return requestedSource to automatic
    }

    fun moveAutomaticOnly(
        source: ScreenRect,
        target: ScreenRect,
        fixedSource: TapPoint,
        requestedAutomatic: TapPoint,
        safeInsetPx: Float,
    ): CalibrationOffset? {
        if (!source.contains(fixedSource.x, fixedSource.y, safeInsetPx) ||
            !target.contains(requestedAutomatic.x, requestedAutomatic.y, safeInsetPx)
        ) return null
        return CalibrationMath.fromReferencePoints(
            source, target, fixedSource, requestedAutomatic
        )
    }
}

object TestPackages {
    const val LEFT = "com.hebi.dualtouch.probeleft"
    const val RIGHT = "com.hebi.dualtouch.proberight"
}

object WindowPairResolver {
    fun resolve(
        windows: List<WindowSnapshot>,
        systemDivider: ScreenRect?,
        workArea: SplitWorkArea?,
    ): WindowPair? {
        val first = windows.filter { it.packageName == TestPackages.LEFT }
        val second = windows.filter { it.packageName == TestPackages.RIGHT }
        if (first.size != 1 || second.size != 1) return null
        return SplitGeometry.detect(first.single(), second.single(), systemDivider, workArea)
            ?.takeIf {
                it.left.packageName == TestPackages.LEFT &&
                    it.right.packageName == TestPackages.RIGHT
            }
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
