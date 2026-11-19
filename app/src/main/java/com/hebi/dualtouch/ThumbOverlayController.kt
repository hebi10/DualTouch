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
import com.hebi.dualtouch.core.CalibrationOffset
import com.hebi.dualtouch.core.CoordinateMapper
import com.hebi.dualtouch.core.ScreenRect
import com.hebi.dualtouch.core.TapPoint
import com.hebi.dualtouch.core.ThumbReferenceMotion
import com.hebi.dualtouch.core.TouchSide
import com.hebi.dualtouch.core.WindowPair
import com.hebi.dualtouch.core.source
import com.hebi.dualtouch.core.target
import kotlin.math.abs
import kotlin.math.max

/**
 * Only the small controller, calibration markers and menus receive overlay input.
 * Window focus stays with the underlying independent test applications.
 */
internal class ThumbOverlayController(
    private val context: Context,
    private val pairProvider: () -> WindowPair?,
    private val sideProvider: () -> TouchSide,
    private val runningProvider: () -> Boolean,
    private val calibrationProvider: () -> CalibrationOffset,
    private val onRunToggle: () -> Unit,
    private val onSideToggle: () -> Unit,
    private val onCalibrationStart: () -> Unit,
    private val onCalibrationSaved: (CalibrationOffset) -> Unit,
    private val onCalibrationReset: () -> Unit,
    private val onMenuVisibilityChanged: (Boolean) -> Unit,
) {
    private data class Marker(
        val view: TextView,
        val layout: WindowManager.LayoutParams,
    )

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var thumb: Marker? = null
    private var menu: LinearLayout? = null
    private var red: Marker? = null
    private var white: Marker? = null
    private var calibrating = false
    private var draftOffset = CalibrationOffset.ZERO

    val isCalibrating: Boolean get() = calibrating
    val isMenuOpen: Boolean get() = menu != null

    fun show(pair: WindowPair): Boolean {
        if (thumb != null) return true
        val source = pair.source(sideProvider()).bounds
        val size = dp(52)
        val view = circle("◎", Color.rgb(32, 32, 32), Color.WHITE, size, Color.WHITE)
        view.contentDescription = "DualTouch 썸. 누르면 실행·중지, 방향, 영점 조절 메뉴"
        val layout = params(size, size,
            source.left + dp(12),
            source.top + dp(108))
        configureDrag(view, layout, onMove = { x, y ->
            val bounds = windowManager.currentWindowMetrics.bounds
            move(Marker(view, layout),
                x.coerceIn(bounds.left, (bounds.right - size).coerceAtLeast(bounds.left)),
                y.coerceIn(bounds.top, (bounds.bottom - size).coerceAtLeast(bounds.top)))
        }, onTap = { toggleMenu() })
        return try {
            windowManager.addView(view, layout)
            thumb = Marker(view, layout)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun isControlAt(x: Float, y: Float): Boolean {
        val control = thumb ?: return false
        val position = IntArray(2)
        control.view.getLocationOnScreen(position)
        return x >= position[0] && x < position[0] + control.view.width &&
            y >= position[1] && y < position[1] + control.view.height
    }

    /** Used when the event is intercepted before the overlay gets an ACTION_UP. */
    fun openMenuFromCapturedTouch() {
        if (!calibrating) openMenu()
    }

    fun updateSourceSide() {
        val pair = pairProvider() ?: return
        val marker = thumb ?: return
        val source = pair.source(sideProvider()).bounds
        move(marker, source.left + dp(12), source.top + dp(108))
    }

    fun hideForDispatch() {
        thumb?.view?.visibility = View.INVISIBLE
    }

    fun restoreAfterDispatch() {
        if (!calibrating) thumb?.view?.visibility = View.VISIBLE
    }

    fun hide() {
        clearCalibration()
        closeMenu()
        thumb?.let { safeRemove(it.view) }
        thumb = null
        calibrating = false
    }

    private fun toggleMenu() {
        if (calibrating) return
        if (menu == null) openMenu() else closeMenu()
    }

    private fun openMenu() {
        if (menu != null || calibrating) return
        onMenuVisibilityChanged(true)
        val column = panel()
        addButton(column, if (runningProvider()) "중지" else "실행") {
            onRunToggle()
            closeMenu()
        }
        addButton(column, if (sideProvider() == TouchSide.LEFT) {
            "좌 선택  →  우 자동"
        } else {
            "우 선택  →  좌 자동"
        }) {
            onSideToggle()
            updateSourceSide()
            closeMenu()
        }
        addButton(column, "영점 조절") { beginCalibration() }

        val position = IntArray(2)
        thumb?.view?.getLocationOnScreen(position)
        val bounds = windowManager.currentWindowMetrics.bounds
        val width = dp(218)
        val x = (position[0] + dp(55)).coerceIn(
            bounds.left, (bounds.right - width).coerceAtLeast(bounds.left))
        val y = position[1].coerceIn(bounds.top, (bounds.bottom - dp(180)).coerceAtLeast(bounds.top))
        try {
            windowManager.addView(column,
                params(width, WindowManager.LayoutParams.WRAP_CONTENT, x, y))
            menu = column
        } catch (_: RuntimeException) {
            onMenuVisibilityChanged(false)
        }
    }

    private fun beginCalibration() {
        val pair = pairProvider() ?: run { closeMenu(); return }
        onCalibrationStart()
        closeMenu()
        calibrating = true
        thumb?.view?.visibility = View.INVISIBLE

        val source = pair.source(sideProvider()).bounds
        val target = pair.target(sideProvider()).bounds
        draftOffset = calibrationProvider()
        val startRed = TapPoint(
            source.left + source.width / 2f,
            source.top + source.height / 2f)
        val startWhite = CoordinateMapper.map(
            source, target, startRed.x, startRed.y, safeInsetPx(), draftOffset)
        if (startWhite == null) {
            endCalibration()
            return
        }

        red = createMarker(startRed, "터치", Color.rgb(198, 40, 40), Color.WHITE, Color.WHITE)
        white = createMarker(startWhite, "자동", Color.WHITE, Color.BLACK, Color.DKGRAY)
        if (red == null || white == null) {
            endCalibration()
            return
        }

        red?.let { marker ->
            configureDrag(marker.view, marker.layout, onMove = { x, y ->
                val current = pairProvider()
                if (current == null || current != pair) {
                    endCalibration()
                    return@configureDrag
                }
                val requestedRed = TapPoint(
                    x + marker.view.width / 2f,
                    y + marker.view.height / 2f)
                val proposed = ThumbReferenceMotion.moveSourceTogether(
                    current.source(sideProvider()).bounds,
                    current.target(sideProvider()).bounds,
                    requestedRed, draftOffset, safeInsetPx()) ?: return@configureDrag
                moveCenter(marker, proposed.first)
                white?.let { moveCenter(it, proposed.second) }
            }, onTap = {})
        }
        white?.let { marker ->
            configureDrag(marker.view, marker.layout, onMove = { x, y ->
                val current = pairProvider()
                if (current == null || current != pair) {
                    endCalibration()
                    return@configureDrag
                }
                val redPoint = red?.let { center(it) } ?: return@configureDrag
                val requestedWhite = TapPoint(
                    x + marker.view.width / 2f,
                    y + marker.view.height / 2f)
                val offset = ThumbReferenceMotion.moveAutomaticOnly(
                    current.source(sideProvider()).bounds,
                    current.target(sideProvider()).bounds,
                    redPoint, requestedWhite, safeInsetPx()) ?: return@configureDrag
                draftOffset = offset
                moveCenter(marker, requestedWhite)
            }, onTap = {})
        }

        val column = panel()
        column.addView(TextView(context).apply {
            text = "빨강: 직접 터치 위치 (두 점 동시 이동)\n흰색: 자동 터치 위치 (흰색만 이동)"
            textSize = 13f
            setTextColor(Color.BLACK)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        })
        addButton(column, "영점 저장") {
            val current = pairProvider()
            val redPoint = red?.let(::center)
            val whitePoint = white?.let(::center)
            val corrected = if (current != null && current == pair &&
                redPoint != null && whitePoint != null
            ) {
                ThumbReferenceMotion.moveAutomaticOnly(
                    current.source(sideProvider()).bounds,
                    current.target(sideProvider()).bounds,
                    redPoint, whitePoint, safeInsetPx())
            } else null
            if (corrected == null) {
                column.addView(TextView(context).apply {
                    text = "조절 범위 또는 창 위치가 유효하지 않습니다."
                    setTextColor(Color.RED)
                    textSize = 12f
                })
            } else {
                onCalibrationSaved(corrected)
                endCalibration()
            }
        }
        addButton(column, "영점 초기화") {
            onCalibrationReset()
            endCalibration()
        }
        addButton(column, "취소") { endCalibration() }

        val bounds = windowManager.currentWindowMetrics.bounds
        val width = dp(270)
        onMenuVisibilityChanged(true)
        try {
            windowManager.addView(column,
                params(width, WindowManager.LayoutParams.WRAP_CONTENT,
                    (bounds.left + dp(10)).coerceAtMost(bounds.right - width),
                    bounds.top + dp(60)))
            menu = column
        } catch (_: RuntimeException) {
            endCalibration()
        }
    }

    private fun createMarker(
        point: TapPoint,
        label: String,
        background: Int,
        foreground: Int,
        border: Int,
    ): Marker? {
        val size = dp(52)
        val view = circle(label, background, foreground, size, border)
        view.textSize = 12f
        view.contentDescription = if (background == Color.WHITE) {
            "자동 터치 흰색 보정점. 드래그하면 흰색만 움직입니다"
        } else {
            "직접 터치 빨간색 보정점. 드래그하면 두 점이 함께 움직입니다"
        }
        val layout = params(size, size,
            (point.x - size / 2).toInt(),
            (point.y - size / 2).toInt())
        return try {
            windowManager.addView(view, layout)
            Marker(view, layout)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun endCalibration() {
        clearCalibration()
        calibrating = false
        closeMenu()
        thumb?.view?.visibility = View.VISIBLE
    }

    private fun clearCalibration() {
        red?.let { safeRemove(it.view) }
        white?.let { safeRemove(it.view) }
        red = null
        white = null
    }

    private fun moveCenter(marker: Marker, center: TapPoint) {
        move(marker, (center.x - marker.view.width / 2f).toInt(),
            (center.y - marker.view.height / 2f).toInt())
    }

    private fun move(marker: Marker, x: Int, y: Int) {
        marker.layout.x = x
        marker.layout.y = y
        try {
            windowManager.updateViewLayout(marker.view, marker.layout)
        } catch (_: RuntimeException) {
            // Android may remove the overlay when the accessibility service stops.
        }
    }

    private fun center(marker: Marker): TapPoint {
        val position = IntArray(2)
        marker.view.getLocationOnScreen(position)
        return TapPoint(position[0] + marker.view.width / 2f,
            position[1] + marker.view.height / 2f)
    }

    private fun closeMenu() {
        val target = menu ?: return
        menu = null
        safeRemove(target)
        onMenuVisibilityChanged(false)
    }

    private fun safeRemove(view: View) {
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: RuntimeException) { }
    }

    private fun configureDrag(
        view: View,
        layout: WindowManager.LayoutParams,
        onMove: (Int, Int) -> Unit,
        onTap: () -> Unit,
    ) {
        var downX = 0f
        var downY = 0f
        var initialX = 0
        var initialY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    initialX = layout.x
                    initialY = layout.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > dp(7) ||
                        abs(event.rawY - downY) > dp(7)
                    ) moved = true
                    if (moved) {
                        onMove(
                            initialX + (event.rawX - downX).toInt(),
                            initialY + (event.rawY - downY).toInt())
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun circle(
        label: String,
        backgroundColor: Int,
        textColor: Int,
        size: Int,
        borderColor: Int,
    ) = TextView(context).apply {
        text = label
        textSize = if (label == "◎") 28f else 12f
        gravity = Gravity.CENTER
        setTextColor(textColor)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(backgroundColor)
            setStroke(dp(2), borderColor)
        }
        minHeight = size
        minWidth = size
    }

    private fun panel() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(dp(1), Color.DKGRAY)
        }
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setOnClickListener { action() }
        })
    }

    private fun params(width: Int, height: Int, x: Int, y: Int) =
        WindowManager.LayoutParams(
            width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    private fun safeInsetPx() = max(dp(26), dp(52) / 2 + dp(4)).toFloat()

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
