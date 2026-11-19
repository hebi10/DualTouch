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
    ): TapPoint? {
        if (!source.isValid || !target.isValid || safeInsetPx < 0f) return null
        if (!source.contains(x, y, safeInsetPx)) return null

        val relativeX = (x - source.left) / source.width.toFloat()
        val relativeY = (y - source.top) / source.height.toFloat()
        val targetX = target.left + relativeX * target.width
        val targetY = target.top + relativeY * target.height

        return TapPoint(targetX, targetY)
            .takeIf { target.contains(it.x, it.y, safeInsetPx) }
    }
}
