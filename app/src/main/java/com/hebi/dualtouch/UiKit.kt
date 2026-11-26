package com.hebi.dualtouch

import android.content.Context
import android.app.Activity
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowInsets
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.FrameLayout
import android.widget.TextView

class UiPalette(context: Context) {
    private val dark =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    val background = if (dark) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
    val surface = if (dark) 0xFF202020.toInt() else 0xFFF5F5F5.toInt()
    val text = if (dark) 0xFFF2F2F2.toInt() else 0xFF191919.toInt()
    val secondary = if (dark) 0xFFB7B7B7.toInt() else 0xFF666666.toInt()
    val line = if (dark) 0xFF535353.toInt() else 0xFFD0D0D0.toInt()
    val actionLine = if (dark) 0xFF737373.toInt() else 0xFFAAAAAA.toInt()
    val button = if (dark) 0xFFF0F0F0.toInt() else 0xFF242424.toInt()
    val buttonText = if (dark) 0xFF171717.toInt() else 0xFFFFFFFF.toInt()
    val success = if (dark) 0xFF86EFAC.toInt() else 0xFF166534.toInt()
    val warning = if (dark) 0xFFFBBF24.toInt() else 0xFF9A4C00.toInt()
    val danger = if (dark) 0xFFFCA5A5.toInt() else 0xFFB91C1C.toInt()
    val stopFill = if (dark) 0xFFB91C1C.toInt() else 0xFFC62828.toInt()
}

object UiKit {
    data class PanePair(
        val row: LinearLayout,
        val primary: LinearLayout,
        val secondary: LinearLayout,
    )

    fun panePair(context: Context, gapDp: Int = 24, primaryWeight: Float = 0.46f): PanePair {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBaselineAligned(false)
        }
        val primary = column(context)
        val secondary = column(context)
        row.addView(primary, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, primaryWeight,
        ).apply { marginEnd = dp(context, gapDp) })
        row.addView(secondary, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f - primaryWeight,
        ))
        return PanePair(row, primary, secondary)
    }

    fun applyWindowInsets(scroll: ScrollView) {
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(
                systemBars.left, systemBars.top, systemBars.right, systemBars.bottom
            )
            insets
        }
    }

    fun centeredScrollContent(
        activity: Activity,
        scroll: ScrollView,
        spec: AdaptiveSpec,
        maxReadingWidth: Boolean = false,
    ): LinearLayout {
        val content = column(activity, spec.innerPaddingDp)
        val frame = FrameLayout(activity)
        val width = if (maxReadingWidth) {
            spec.contentWidthForReadingDp
        } else {
            spec.contentWidthDp
        }
        frame.addView(content, FrameLayout.LayoutParams(
            dp(activity, width),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ))
        scroll.addView(frame, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        return content
    }

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
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            minHeight = dp(context, 52)
            setPadding(dp(context, 12), dp(context, 8),
                dp(context, 12), dp(context, 8))
            setTextColor(if (primary) palette.buttonText else palette.text)
            background = actionBackground(context, palette, primary)
            stateListAnimator = null
        }

    fun actionBackground(context: Context, palette: UiPalette, primary: Boolean, danger: Boolean = false): android.graphics.drawable.Drawable {
        val normal = if (danger) palette.stopFill else if (primary) palette.button else palette.background
        val radius = dp(context, 2).toFloat()
        val states = android.graphics.drawable.StateListDrawable()
        states.addState(intArrayOf(-android.R.attr.state_enabled), shape(palette.surface, palette.line, radius))
        states.addState(intArrayOf(), shape(normal, if (primary || danger) null else palette.actionLine, radius).apply {
            if (!primary && !danger) setStroke(dp(context, 1).coerceAtLeast(1), palette.actionLine)
        })
        return states
    }

    fun styleAction(button: Button, palette: UiPalette, primary: Boolean, danger: Boolean = false) {
        button.background = actionBackground(button.context, palette, primary, danger)
        button.setTextColor(if (danger) 0xFFFFFFFF.toInt() else if (primary) palette.buttonText else palette.text)
        button.stateListAnimator = null
    }

    fun toolbar(context: Context, palette: UiPalette, title: String, back: (() -> Unit)? = null, trailing: String? = null, onTrailing: (() -> Unit)? = null, trailingIcon: LineIcon? = null): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 48)
            if (back != null) {
                val button = android.widget.ImageButton(context).apply {
                    contentDescription = "뒤로가기"
                    setImageDrawable(icon(context, LineIcon.BACK, palette.text))
                    scaleType = android.widget.ImageView.ScaleType.CENTER
                    background = shape(palette.background)
                    stateListAnimator = null
                    setOnClickListener { back() }
                }
                addView(button, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)).apply { marginEnd = dp(context, 4) })
            }
            addView(text(context, palette, title, 24f, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (trailing != null && onTrailing != null) {
                val button = action(context, palette, trailing, false).apply {
                    textSize = 13f
                    minHeight = dp(context, 48)
                    minimumHeight = dp(context, 48)
                    minimumWidth = dp(context, 48)
                    if (trailingIcon != null) {
                        setCompoundDrawablesRelativeWithIntrinsicBounds(icon(context, trailingIcon, palette.text, 18), null, null, null)
                        compoundDrawablePadding = dp(context, 6)
                    }
                    setOnClickListener { onTrailing() }
                }
                addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(context, 8) })
            }
        }
    }

    fun icon(context: Context, kind: LineIcon, color: Int, sizeDp: Int = 24) =
        UiLineIcon(kind, color, dp(context, sizeDp))

    fun iconView(context: Context, kind: LineIcon, color: Int, sizeDp: Int = 24) =
        android.widget.ImageView(context).apply {
            setImageDrawable(icon(context, kind, color, sizeDp))
            scaleType = android.widget.ImageView.ScaleType.CENTER
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
