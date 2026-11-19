package com.hebi.dualtouch

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class UiPalette(context: Context) {
    private val dark =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    val background = if (dark) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
    val surface = if (dark) 0xFF202020.toInt() else 0xFFF5F5F5.toInt()
    val text = if (dark) 0xFFF2F2F2.toInt() else 0xFF191919.toInt()
    val secondary = if (dark) 0xFFB7B7B7.toInt() else 0xFF666666.toInt()
    val line = if (dark) 0xFF535353.toInt() else 0xFFD9D9D9.toInt()
    val button = if (dark) 0xFFF0F0F0.toInt() else 0xFF242424.toInt()
    val buttonText = if (dark) 0xFF171717.toInt() else 0xFFFFFFFF.toInt()
}

object UiKit {
    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun shape(fill: Int, stroke: Int? = null, radius: Float = 0f) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radius
            if (stroke != null) setStroke(1, stroke)
        }

    fun column(context: Context, padding: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val d = dp(context, padding)
        setPadding(d, d, d, d)
    }

    fun text(
        context: Context,
        palette: UiPalette,
        value: String,
        sp: Float = 15f,
        bold: Boolean = false,
        secondary: Boolean = false,
    ) = TextView(context).apply {
        text = value
        textSize = sp
        setTextColor(if (secondary) palette.secondary else palette.text)
        setLineSpacing(dp(context, 3).toFloat(), 1f)
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    fun action(context: Context, palette: UiPalette, label: String, primary: Boolean = true) =
        Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            minHeight = dp(context, 52)
            setPadding(dp(context, 12), dp(context, 8),
                dp(context, 12), dp(context, 8))
            setTextColor(if (primary) palette.buttonText else palette.text)
            background = shape(if (primary) palette.button else palette.background,
                if (primary) null else palette.line)
        }

    fun add(
        parent: LinearLayout,
        child: android.view.View,
        topDp: Int = 0,
        bottomDp: Int = 0,
    ) {
        parent.addView(child, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(parent.context, topDp)
            bottomMargin = dp(parent.context, bottomDp)
        })
    }

    fun sectionHeader(context: Context, palette: UiPalette, heading: String) =
        text(context, palette, heading, 18f, true)

    fun card(context: Context, palette: UiPalette, padding: Int = 16) =
        column(context, padding).apply { background = shape(palette.surface) }

    fun centeredLabel(context: Context, value: String, color: Int, sizeSp: Float = 13f) =
        TextView(context).apply {
            text = value
            textSize = sizeSp
            gravity = Gravity.CENTER
            setTextColor(color)
        }
}
