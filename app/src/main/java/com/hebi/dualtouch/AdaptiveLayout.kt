package com.hebi.dualtouch

import android.app.Activity
import android.view.WindowInsets
import kotlin.math.roundToInt

enum class WidthCategory { COMPACT, MEDIUM, EXPANDED }

data class AdaptiveSpec(
    val availableWidthDp: Int,
    val availableHeightDp: Int,
) {
    val widthCategory: WidthCategory = when {
        availableWidthDp < 600 -> WidthCategory.COMPACT
        availableWidthDp < 840 -> WidthCategory.MEDIUM
        else -> WidthCategory.EXPANDED
    }

    val useTwoPanes: Boolean =
        widthCategory == WidthCategory.EXPANDED && availableHeightDp >= 480

    val pageGutterDp: Int = when (widthCategory) {
        WidthCategory.COMPACT -> 12
        WidthCategory.MEDIUM -> 24
        WidthCategory.EXPANDED -> 32
    }

    val innerPaddingDp: Int = if (widthCategory == WidthCategory.COMPACT) 12 else 20
    val paneGapDp: Int = 24

    val contentWidthDp: Int = (availableWidthDp - pageGutterDp * 2)
        .coerceAtLeast(1)
        .coerceAtMost(
            when {
                useTwoPanes -> 1180
                widthCategory == WidthCategory.COMPACT -> 600
                else -> 720
            }
        )

    val contentWidthForReadingDp: Int = (availableWidthDp - pageGutterDp * 2)
        .coerceAtLeast(1).coerceAtMost(720)
}

object AdaptiveLayout {
    fun specFor(widthDp: Int, heightDp: Int) =
        AdaptiveSpec(widthDp.coerceAtLeast(1), heightDp.coerceAtLeast(1))

    fun specFor(activity: Activity): AdaptiveSpec {
        val metrics = activity.windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
        val bounds = metrics.bounds
        val widthPx = (bounds.width() - insets.left - insets.right).coerceAtLeast(1)
        val heightPx = (bounds.height() - insets.top - insets.bottom).coerceAtLeast(1)
        val density = activity.resources.displayMetrics.density
        return specFor((widthPx / density).roundToInt(),
            (heightPx / density).roundToInt())
    }
}
