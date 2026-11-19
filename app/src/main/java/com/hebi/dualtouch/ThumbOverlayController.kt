package com.hebi.dualtouch

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hebi.dualtouch.core.CalibrationOffset
import com.hebi.dualtouch.core.SafetyAssessment
import com.hebi.dualtouch.core.WindowSafetyPolicy
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
    private val candidateProvider: () -> SafetyAssessment,
    private val onConnectApps: (TouchSide) -> Boolean,
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
    private val appearanceSettings = AppearanceSettings(context)
    private var thumb: Marker? = null
    private var menu: View? = null
    private var red: Marker? = null
    private var white: Marker? = null
    private var calibrating = false
    private var draftOffset = CalibrationOffset.ZERO

    val isCalibrating: Boolean get() = calibrating
    val isMenuOpen: Boolean get() = menu != null

    fun show(pair: WindowPair?): Boolean {
        if (thumb != null) return true
        val appearance = appearanceSettings.get()
        val bounds = safeWindowBounds()
        val source = pair?.source(sideProvider())?.bounds
        val size = dp(appearance.thumbSizeDp)
        val x = source?.left?.plus(dp(12)) ?: bounds.left + dp(24)
        val y = source?.top?.plus(dp(108)) ?: bounds.top + dp(150)
        val view = circle("◎", appearance.thumbColor,
            AppearanceRules.readableTextColor(appearance.thumbColor),
            size, Color.WHITE)
        view.alpha = appearance.thumbOpacityPercent / 100f
        view.contentDescription = "DualTouch 썸. 누르면 실행·중지, 좌우 방향, 영점 조절 메뉴"
        val layout = params(size, size, x, y)
        configureDrag(view, layout, onMove = { nextX, nextY ->
            val screen = safeWindowBounds()
            move(Marker(view, layout),
                nextX.coerceIn(screen.left,
                    (screen.right - layout.width).coerceAtLeast(screen.left)),
                nextY.coerceIn(screen.top,
                    (screen.bottom - layout.height).coerceAtLeast(screen.top)))
        }, onTap = { toggleMenu() })
        return try {
            windowManager.addView(view, layout)
            thumb = Marker(view, layout)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun updateAppearance() {
        val appearance = appearanceSettings.get()
        thumb?.let {
            updateMarkerAppearance(it, appearance.thumbSizeDp,
                appearance.thumbColor, appearance.thumbOpacityPercent, "◎")
        }
        red?.let {
            updateMarkerAppearance(it, appearance.markerSizeDp,
                appearance.directColor, appearance.markerOpacityPercent, "터치")
        }
        white?.let {
            updateMarkerAppearance(it, appearance.markerSizeDp,
                appearance.automaticColor, appearance.markerOpacityPercent, "자동")
        }
    }

    private fun updateMarkerAppearance(
        marker: Marker,
        sizeDp: Int,
        fill: Int,
        opacityPercent: Int,
        label: String,
    ) {
        val size = dp(sizeDp)
        val oldX = marker.layout.x + marker.layout.width / 2
        val oldY = marker.layout.y + marker.layout.height / 2
        marker.layout.width = size
        marker.layout.height = size
        val safe = safeWindowBounds()
        marker.layout.x = (oldX - size / 2).coerceIn(
            safe.left, (safe.right - size).coerceAtLeast(safe.left))
        marker.layout.y = (oldY - size / 2).coerceIn(
            safe.top, (safe.bottom - size).coerceAtLeast(safe.top))
        marker.view.minWidth = size
        marker.view.minHeight = size
        marker.view.setTextColor(AppearanceRules.readableTextColor(fill))
        marker.view.alpha = opacityPercent / 100f
        marker.view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(dp(2), if (fill == Color.WHITE) Color.DKGRAY else Color.WHITE)
        }
        marker.view.textSize = if (label == "◎") 28f else 12f
        try {
            windowManager.updateViewLayout(marker.view, marker.layout)
        } catch (_: RuntimeException) {
            // The service may stop while a setting is being changed.
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
        val marker = thumb ?: return
        val pair = pairProvider()
        val source = pair?.source(sideProvider())?.bounds
        val bounds = safeWindowBounds()
        val x = source?.left?.plus(dp(12)) ?: if (sideProvider() == TouchSide.LEFT) {
            bounds.left + dp(24)
        } else {
            bounds.right - marker.layout.width - dp(24)
        }
        val y = source?.top?.plus(dp(108)) ?: bounds.top + dp(150)
        move(marker,
            x.coerceIn(bounds.left, (bounds.right - marker.layout.width).coerceAtLeast(bounds.left)),
            y.coerceIn(bounds.top, (bounds.bottom - marker.layout.height).coerceAtLeast(bounds.top))
        )
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
        addButton(column, "좌/우 선택 · 앱 연결") {
            openPairSelection()
        }
        addButton(column, "영점 조절") { beginCalibration() }

        val position = IntArray(2)
        thumb?.view?.getLocationOnScreen(position)
        val preferredX = position[0] + (thumb?.view?.width ?: dp(52)) + dp(8)
        if (!showMenu(column, 242, preferredX, position[1])) {
            onMenuVisibilityChanged(false)
        }
    }

    /**
     * Display packages captured from the two currently visible app windows.
     * The user must explicitly approve the current pair AND the input direction.
     */
    private fun openPairSelection() {
        closeMenu()
        onMenuVisibilityChanged(true)
        val column = panel()
        val snapshot = candidateProvider()
        val pair = snapshot.pair
        val title = TextView(context).apply {
            text = "현재 분할 앱 연결"
            textSize = 17f
            setTextColor(Color.BLACK)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        column.addView(title)
        if (snapshot.allowed && pair != null) {
            val detail = TextView(context).apply {
                text = "왼쪽: ${pair.left.packageName}\n" +
                    "오른쪽: ${pair.right.packageName}\n" +
                    "선택한 두 창에서만 1회 입력을 복제합니다."
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(dp(10), dp(6), dp(10), dp(8))
            }
            column.addView(detail)
            addButton(column, "왼쪽 직접 → 오른쪽 자동 연결") {
                if (onConnectApps(TouchSide.LEFT)) {
                    updateSourceSide()
                    closeMenu()
                } else {
                    closeMenu()
                    openPairSelection()
                }
            }
            addButton(column, "오른쪽 직접 → 왼쪽 자동 연결") {
                if (onConnectApps(TouchSide.RIGHT)) {
                    updateSourceSide()
                    closeMenu()
                } else {
                    closeMenu()
                    openPairSelection()
                }
            }
        } else {
            column.addView(TextView(context).apply {
                text = WindowSafetyPolicy.message(snapshot.issue) +
                    "\n먼저 사용하려는 앱 두 개를 좌우 5:5로 실행하세요. " +
                    "보안·결제·입력 화면은 연결하지 않습니다."
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            })
        }
        addButton(column, "다시 검색") {
            closeMenu()
            openPairSelection()
        }
        addButton(column, "닫기") { closeMenu() }
        val at = IntArray(2)
        thumb?.view?.getLocationOnScreen(at)
        if (!showMenu(column, 292, at[0] + dp(25), at[1])) {
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

        val a = appearanceSettings.get()
        red = createMarker(startRed, "터치", a.directColor,
            AppearanceRules.readableTextColor(a.directColor), Color.WHITE)
        white = createMarker(startWhite, "자동", a.automaticColor,
            AppearanceRules.readableTextColor(a.automaticColor), Color.DKGRAY)
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

        val bounds = safeWindowBounds()
        onMenuVisibilityChanged(true)
        if (!showMenu(column, 276, bounds.left + dp(10), bounds.top + dp(12))) {
            endCalibration()
            onMenuVisibilityChanged(false)
        }
    }

    private fun createMarker(
        point: TapPoint,
        label: String,
        background: Int,
        foreground: Int,
        border: Int,
    ): Marker? {
        val a = appearanceSettings.get()
        val size = dp(a.markerSizeDp)
        val view = circle(label, background, foreground, size, border)
        view.alpha = a.markerOpacityPercent / 100f
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
            textSize = 15f
            minHeight = dp(52)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { action() }
        })
    }

    private fun safeWindowBounds(): Rect {
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
        val left = bounds.left + insets.left
        val top = bounds.top + insets.top
        return Rect(
            left, top,
            (bounds.right - insets.right).coerceAtLeast(left + 1),
            (bounds.bottom - insets.bottom).coerceAtLeast(top + 1)
        )
    }

    private fun showMenu(
        content: LinearLayout,
        widthDp: Int,
        requestedX: Int,
        requestedY: Int,
    ): Boolean {
        val safe = safeWindowBounds()
        val width = dp(widthDp).coerceAtMost(safe.width()).coerceAtLeast(1)
        val availableHeight = (safe.height() - dp(8)).coerceAtLeast(1)
        val scrollView = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeight = View.MeasureSpec.makeMeasureSpec(
                    availableHeight, View.MeasureSpec.AT_MOST
                )
                super.onMeasure(widthMeasureSpec, maxHeight)
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = false
            addView(content)
        }
        scrollView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST)
        )
        val height = scrollView.measuredHeight.coerceAtLeast(1)
        val x = requestedX.coerceIn(
            safe.left, (safe.right - width).coerceAtLeast(safe.left)
        )
        val y = requestedY.coerceIn(
            safe.top, (safe.bottom - height).coerceAtLeast(safe.top)
        )
        return try {
            windowManager.addView(
                scrollView,
                params(width, WindowManager.LayoutParams.WRAP_CONTENT, x, y)
            )
            menu = scrollView
            true
        } catch (_: RuntimeException) {
            false
        }
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

    private fun safeInsetPx(): Float {
        val size = appearanceSettings.get().markerSizeDp
        return max(dp(26), dp(size) / 2 + dp(4)).toFloat()
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
