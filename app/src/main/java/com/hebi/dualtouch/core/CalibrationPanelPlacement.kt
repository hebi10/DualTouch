package com.hebi.dualtouch.core

/** UI placement only; never changes marker geometry or authorizes input. */
internal object CalibrationPanelPlacement {
    fun find(
        safe: ScreenRect,
        width: Int,
        height: Int,
        obstacles: List<ScreenRect>,
        preferredX: Int,
        preferredY: Int,
        gap: Int = 0,
    ): ScreenRect? {
        if (!safe.isValid || width <= 0 || height <= 0 ||
            width > safe.width || height > safe.height || gap < 0) return null
        val blocked = obstacles.filter { it.isValid }.map {
            ScreenRect(it.left - gap, it.top - gap, it.right + gap, it.bottom + gap)
        }
        val maxX = safe.right - width
        val maxY = safe.bottom - height
        val preferredLeft = preferredX.coerceIn(safe.left, maxX)
        val preferredTop = preferredY.coerceIn(safe.top, maxY)
        // Edges and obstacle boundaries enumerate the feasible rectangular
        // gaps. Keep the closest placement to the original panel.
        val xs = (listOf(preferredLeft, safe.left, maxX) +
            blocked.flatMap { listOf(it.left - width, it.right) })
            .filter { it in safe.left..maxX }.distinct()
        val ys = (listOf(preferredTop, safe.top, maxY) +
            blocked.flatMap { listOf(it.top - height, it.bottom) })
            .filter { it in safe.top..maxY }.distinct()
        return xs.flatMap { x -> ys.map { y -> ScreenRect(x, y, x + width, y + height) } }
            .filter { candidate -> blocked.none { candidate.overlaps(it) } }
            .minByOrNull {
                val dx = it.left.toLong() - preferredLeft
                val dy = it.top.toLong() - preferredTop
                dx * dx + dy * dy
            }
    }
}
