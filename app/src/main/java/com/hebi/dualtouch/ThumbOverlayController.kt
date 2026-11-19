package com.hebi.dualtouch

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.hebi.dualtouch.core.CalibrationMath
import com.hebi.dualtouch.core.CalibrationOffset
import com.hebi.dualtouch.core.TapPoint
import com.hebi.dualtouch.core.WindowPair
import kotlin.math.abs

/**
 * A small, trusted accessibility overlay used only in the two test applications.
 * Nothing outside the bubble, markers and menu is touchable. No full-screen overlay.
 */
internal class ThumbOverlayController(
    private val context: Context,
    private val pairProvider: () -> WindowPair?,
    private val onTap: (TapPoint) -> Unit,
    private val onStop: () -> Unit,
    private val onCalibrationSaved: (CalibrationOffset) -> Unit,
    private val onCalibrationReset: () -> Unit,
) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bubble: TextView? = null
    private var bubbleLayout: WindowManager.LayoutParams? = null
    private var menu: LinearLayout? = null
    private var leftMarker: TextView? = null
    private var rightMarker: TextView? = null
    private var calibrating = false

    val isCalibrating: Boolean get() = calibrating

    fun show(pair: WindowPair): Boolean {
        if (bubble != null) return true
        val size = dp(54)
        val targetX = pair.left.bounds.left + pair.left.bounds.width / 2 - size / 2
        val targetY = pair.left.bounds.top + pair.left.bounds.height / 2 - size / 2
        val params = params(size, size, targetX, targetY)
        val thumb = circle("◎", Color.rgb(35, 35, 35), Color.WHITE, 54)
        thumb.contentDescription = "터치 썸: 드래그로 위치 이동, 탭하면 좌우 한 번씩 클릭, 길게 눌러 영점 조절"
        setDraggable(thumb, params, onShortTap = {
            val center = centerOnScreen(thumb)
            onTap(center)
        }, onLongPress = { openMenu() })
        return try {
            manager.addView(thumb, params)
            bubble = thumb
            bubbleLayout = params
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun hideForDispatch() {
        bubble?.visibility = View.INVISIBLE
        closeMenu()
    }

    fun restoreAfterDispatch() {
        if (!calibrating) bubble?.visibility = View.VISIBLE
    }

    fun hide() {
        closeMenu()
        clearMarkers()
        bubble?.let { safeRemove(it) }
        bubble = null
        bubbleLayout = null
        calibrating = false
    }

    private fun openMenu() {
        if (menu != null || calibrating) return
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = panelBackground()
            contentDescription = "DualTouch 썸 조절 메뉴"
        }
        addButton(column, "영점 조절") { beginCalibration() }
        addButton(column, "영점 초기화") {
            onCalibrationReset()
            closeMenu()
        }
        addButton(column, "동기화 종료") {
            closeMenu()
            onStop()
        }
        val bounds = manager.currentWindowMetrics.bounds
        val lp = params(dp(190), WindowManager.LayoutParams.WRAP_CONTENT,
            bounds.right - dp(205), bounds.top + dp(70))
        try {
            manager.addView(column, lp)
            menu = column
        } catch (_: RuntimeException) {
            // The hardware volume key remains the emergency stop.
        }
    }

    private fun beginCalibration() {
        val pair = pairProvider()
        if (pair == null) {
            closeMenu()
            return
        }
        closeMenu()
        calibrating = true
        bubble?.visibility = View.INVISIBLE

        leftMarker = createMarker("L", pair.left.bounds.left + pair.left.bounds.width / 2,
            pair.left.bounds.top + pair.left.bounds.height / 2)
        rightMarker = createMarker("R", pair.right.bounds.left + pair.right.bounds.width / 2,
            pair.right.bounds.top + pair.right.bounds.height / 2)

        if (leftMarker == null || rightMarker == null) {
            cancelCalibration()
            return
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = panelBackground()
        }
        val help = TextView(context).apply {
            text = "L/R 원을 양쪽 같은 버튼 위치로 이동"
            textSize = 13f
            setTextColor(Color.BLACK)
        }
        column.addView(help)
        addButton(column, "영점 저장") {
            val current = pairProvider()
            val left = leftMarker
            val right = rightMarker
            val offset = if (current != null && left != null && right != null) {
                CalibrationMath.fromReferencePoints(
                    current.left.bounds,
                    current.right.bounds,
                    centerOnScreen(left),
                    centerOnScreen(right),
                )
            } else null

            if (offset == null) {
                help.text = "오차가 너무 크거나 화면이 변경됐습니다. 다시 맞춰주세요."
            } else {
                onCalibrationSaved(offset)
                cancelCalibration()
            }
        }
        addButton(column, "영점 취소") { cancelCalibration() }

        val bounds = manager.currentWindowMetrics.bounds
        val lp = params(dp(260), WindowManager.LayoutParams.WRAP_CONTENT,
            bounds.left + dp(24), bounds.top + dp(64))
        try {
            manager.addView(column, lp)
            menu = column
        } catch (_: RuntimeException) {
            cancelCalibration()
        }
    }

    private fun createMarker(label: String, centerX: Int, centerY: Int): TextView? {
        val size = dp(44)
        val dot = circle(label, Color.rgb(65, 65, 65), Color.WHITE, 44)
        dot.contentDescription = "영점 조절 " + label + " 기준점. 원하는 버튼 중심으로 드래그"
        val lp = params(size, size, centerX - size / 2, centerY - size / 2)
        setDraggable(dot, lp, onShortTap = {}, onLongPress = {})
        return try {
            manager.addView(dot, lp)
            dot
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun cancelCalibration() {
        clearMarkers()
        calibrating = false
        closeMenu()
        bubble?.visibility = View.VISIBLE
    }

    private fun clearMarkers() {
        leftMarker?.let { safeRemove(it) }
        rightMarker?.let { safeRemove(it) }
        leftMarker = null
        rightMarker = null
    }

    private fun closeMenu() {
        menu?.let { safeRemove(it) }
        menu = null
    }

    private fun safeRemove(view: View) {
        try {
            manager.removeViewImmediate(view)
        } catch (_: RuntimeException) {
            // The window was already removed by Android.
        }
    }

    private fun setDraggable(
        view: View,
        lp: WindowManager.LayoutParams,
        onShortTap: () -> Unit,
        onLongPress: () -> Unit,
    ) {
        var downX = 0f
        var downY = 0f
        var initialX = 0
        var initialY = 0
        var downAt = 0L
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    initialX = lp.x
                    initialY = lp.y
                    downAt = SystemClock.uptimeMillis()
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downX
                    val deltaY = event.rawY - downY
                    if (abs(deltaX) > dp(7) || abs(deltaY) > dp(7)) moved = true
                    if (moved) {
                        val bounds = manager.currentWindowMetrics.bounds
                        lp.x = (initialX + deltaX.toInt()).coerceIn(
                            bounds.left, (bounds.right - lp.width).coerceAtLeast(bounds.left))
                        lp.y = (initialY + deltaY.toInt()).coerceIn(
                            bounds.top, (bounds.bottom - lp.height).coerceAtLeast(bounds.top))
                        try {
                            manager.updateViewLayout(view, lp)
                        } catch (_: RuntimeException) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && SystemClock.uptimeMillis() - downAt >= 650L) {
                        onLongPress()
                    } else if (!moved) {
                        onShortTap()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun circle(label: String, fill: Int, foreground: Int, size: Int): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = if (size >= 50) 28f else 18f
            setTextColor(foreground)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
                setStroke(dp(2), Color.WHITE)
            }
        }
    }

    private fun panelBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.WHITE)
        setStroke(dp(1), Color.DKGRAY)
    }

    private fun addButton(column: LinearLayout, label: String, action: () -> Unit) {
        val button = Button(context).apply {
            text = label
            textSize = 13f
            setAllCaps(false)
            setOnClickListener { action() }
        }
        column.addView(button)
    }

    private fun centerOnScreen(view: View): TapPoint {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return TapPoint(location[0] + view.width / 2f, location[1] + view.height / 2f)
    }

    private fun params(width: Int, height: Int, x: Int, y: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
