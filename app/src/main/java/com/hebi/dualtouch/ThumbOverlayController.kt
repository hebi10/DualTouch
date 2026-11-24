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
import android.widget.SeekBar
import android.widget.TextView
import com.hebi.dualtouch.core.CalibrationOffset
import com.hebi.dualtouch.core.SafetyAssessment
import com.hebi.dualtouch.core.AppPairApproval
import com.hebi.dualtouch.core.SafetyIssue
import com.hebi.dualtouch.core.WindowSafetyPolicy
import com.hebi.dualtouch.core.CalibrationGuide
import com.hebi.dualtouch.core.GuideAxis
import com.hebi.dualtouch.core.SplitOrientation
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
    private val pairProvider: () -> SafetyAssessment,
    private val sideProvider: () -> TouchSide,
    private val runningProvider: () -> Boolean,
    private val calibrationProvider: (WindowPair, TouchSide) -> CalibrationOffset,
    private val onRunToggle: () -> SafetyIssue,
    private val candidateProvider: () -> SafetyAssessment,
    private val onConnectApps: (TouchSide, AppPairApproval) -> SafetyIssue,
    private val onCalibrationStart: () -> Unit,
    private val onCalibrationSaved: (WindowPair, TouchSide, CalibrationOffset) -> Boolean,
    private val onCalibrationReset: (WindowPair, TouchSide) -> Boolean,
    private val onMenuVisibilityChanged: (Boolean) -> Unit,
) {
    private data class Marker(
        val view: TextView,
        val layout: WindowManager.LayoutParams,
    )

    private data class GuideSegment(
        val view: View,
        val layout: WindowManager.LayoutParams,
    )

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val appearanceSettings = AppearanceSettings(context)
    private val guidePrefs = context.getSharedPreferences("thumb_guide_v1", Context.MODE_PRIVATE)
    private var thumb: Marker? = null
    private var menu: View? = null
    private var red: Marker? = null
    private var white: Marker? = null
    private var calibrating = false
    private var draftOffset = CalibrationOffset.ZERO
    private var guideAxis = GuideAxis.VERTICAL
    private var guidePercent = CalibrationGuide.DEFAULT_PERCENT
    private val guideSegments = mutableListOf<GuideSegment>()

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
        return control.view.visibility == View.VISIBLE && x >= position[0] && x < position[0] + control.view.width &&
            y >= position[1] && y < position[1] + control.view.height
    }

    /** Used when the event is intercepted before the overlay gets an ACTION_UP. */
    fun openMenuFromCapturedTouch() {
        if (!calibrating) openMenu()
    }

    fun updateSourceSide(pair: WindowPair?) {
        val marker = thumb ?: return
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

    fun cancelCalibration() {
        if (calibrating) endCalibration()
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
            when (val issue = onRunToggle()) {
                SafetyIssue.NONE -> closeMenu()
                SafetyIssue.UNAPPROVED_PAIR -> openPairSelection()
                else -> openPairSelection(issue)
            }
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
    private fun openPairSelection(previousIssue: SafetyIssue? = null) {
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
        val issue = if (snapshot.allowed) previousIssue else snapshot.issue
        if (issue != null) {
            column.addView(TextView(context).apply {
                text = WindowSafetyPolicy.message(issue)
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(dp(10), dp(6), dp(10), dp(8))
            })
        }
        if (snapshot.allowed && pair != null) {
            val approval = AppPairApproval.fromPair(pair)
            val detail = TextView(context).apply {
                val firstLabel = if (pair.orientation == SplitOrientation.HORIZONTAL) "왼쪽" else "위쪽"
                val secondLabel = if (pair.orientation == SplitOrientation.HORIZONTAL) "오른쪽" else "아래쪽"
                text = "$firstLabel: ${pair.left.packageName}\n" +
                    "$secondLabel: ${pair.right.packageName}\n" +
                    "실행 중에는 한 손가락으로 짧게 누르는 탭만 처리합니다."
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(dp(10), dp(6), dp(10), dp(8))
            }
            column.addView(detail)
            addButton(column, if (pair.orientation == SplitOrientation.HORIZONTAL) {
                "왼쪽 직접 → 오른쪽 자동 연결"
            } else {
                "위쪽 직접 → 아래쪽 자동 연결"
            }) {
                confirmPair(TouchSide.LEFT, approval)
            }
            addButton(column, if (pair.orientation == SplitOrientation.HORIZONTAL) {
                "오른쪽 직접 → 왼쪽 자동 연결"
            } else {
                "아래쪽 직접 → 위쪽 자동 연결"
            }) {
                confirmPair(TouchSide.RIGHT, approval)
            }
        }
        addButton(column, "다시 검색") { openPairSelection() }
        addButton(column, "닫기") { closeMenu() }
        val at = IntArray(2)
        thumb?.view?.getLocationOnScreen(at)
        if (!showMenu(column, 292, at[0] + dp(25), at[1])) {
            onMenuVisibilityChanged(false)
        }
    }

    private fun confirmPair(side: TouchSide, approval: AppPairApproval) {
        val issue = onConnectApps(side, approval)
        if (issue == SafetyIssue.NONE) {
            updateSourceSide(pairProvider().pair)
            closeMenu()
        } else {
            openPairSelection(issue)
        }
    }

    private fun beginCalibration() {
        val assessment = pairProvider()
        val pair = assessment.pair
        if (!assessment.allowed || pair == null) {
            openPairSelection(assessment.issue)
            return
        }
        val side = sideProvider()
        onCalibrationStart()
        closeMenu()
        calibrating = true
        thumb?.view?.visibility = View.INVISIBLE

        val source = pair.source(side).bounds
        val target = pair.target(side).bounds
        draftOffset = calibrationProvider(pair, side)
        val defaultAxis = CalibrationGuide.defaultAxis(pair.orientation)
        guideAxis = runCatching {
            GuideAxis.valueOf(
                guidePrefs.getString("axis_${pair.orientation.name}", defaultAxis.name)
                    ?: defaultAxis.name
            )
        }.getOrDefault(defaultAxis)
        guidePercent = CalibrationGuide.clampPercent(guidePrefs.getInt(
            "position_${pair.orientation.name}_${guideAxis.name}",
            CalibrationGuide.DEFAULT_PERCENT,
        ))
        val startRed = CalibrationGuide.reference(source, guideAxis, guidePercent)
        if (startRed == null) {
            endCalibration()
            return
        }
        val startWhite = CoordinateMapper.map(source, target, startRed.x, startRed.y,
            safeInsetPx(), draftOffset)
        if (startWhite == null || !showGuides(pair)) {
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
                val current = pairProvider().pair
                if (current != pair || side != sideProvider()) {
                    endCalibration()
                    return@configureDrag
                }
                val requestedRed = TapPoint(
                    x + marker.view.width / 2f,
                    y + marker.view.height / 2f)
                val proposed = ThumbReferenceMotion.moveSourceTogether(
                    current.source(side).bounds,
                    current.target(side).bounds,
                    requestedRed, draftOffset, safeInsetPx()) ?: return@configureDrag
                moveCenter(marker, proposed.first)
                white?.let { moveCenter(it, proposed.second) }
            }, onTap = {})
        }
        white?.let { marker ->
            configureDrag(marker.view, marker.layout, onMove = { x, y ->
                val current = pairProvider().pair
                if (current != pair || side != sideProvider()) {
                    endCalibration()
                    return@configureDrag
                }
                val redPoint = red?.let { center(it) } ?: return@configureDrag
                val requestedWhite = TapPoint(
                    x + marker.view.width / 2f,
                    y + marker.view.height / 2f)
                val offset = ThumbReferenceMotion.moveAutomaticOnly(
                    current.source(side).bounds,
                    current.target(side).bounds,
                    redPoint, requestedWhite, safeInsetPx()) ?: return@configureDrag
                draftOffset = offset
                moveCenter(marker, requestedWhite)
            }, onTap = {})
        }

        val column = panel()
        val layoutLabel = if (pair.orientation == SplitOrientation.HORIZONTAL) {
            "가로 (좌·우)"
        } else {
            "세로 (상·하)"
        }
        column.addView(TextView(context).apply {
            text = "앱 분할 배치: $layoutLabel\n" +
                "앱의 실제 분할 방향은 Android에서 변경한 뒤 다시 연결하세요."
            textSize = 13f
            setTextColor(Color.BLACK)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        })
        column.addView(TextView(context).apply {
            text = "빨강: 직접 터치 위치 (두 점 동시 이동)\n흰색: 자동 터치 위치 (흰색만 이동)"
            textSize = 13f
            setTextColor(Color.BLACK)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        })
        val guideNotice = TextView(context).apply {
            text = "기준선은 표시용이며 Android 분할 경계를 이동하지 않습니다."
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        column.addView(guideNotice)
        val axisButton = Button(context).apply {
            isAllCaps = false
            textSize = 14f
            minHeight = dp(52)
        }
        fun axisLabel() = if (guideAxis == GuideAxis.VERTICAL) "세로 기준선" else "가로 기준선"
        axisButton.text = "가로/세로 기준선 변경 · " + axisLabel()
        column.addView(axisButton)
        val positionLabel = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        positionLabel.text = "중앙 기준선 위치: ${guidePercent}%"
        column.addView(positionLabel)
        val positionSlider = SeekBar(context).apply {
            max = CalibrationGuide.MAX_PERCENT - CalibrationGuide.MIN_PERCENT
            progress = guidePercent - CalibrationGuide.MIN_PERCENT
            minHeight = dp(52)
            contentDescription = "중앙 기준선 위치 조절"
        }
        axisButton.setOnClickListener {
            val next = if (guideAxis == GuideAxis.VERTICAL) GuideAxis.HORIZONTAL
                else GuideAxis.VERTICAL
            val nextPercent = CalibrationGuide.clampPercent(guidePrefs.getInt(
                "position_${pair.orientation.name}_${next.name}",
                CalibrationGuide.DEFAULT_PERCENT,
            ))
            if (changeGuide(pair, next, nextPercent)) {
                positionSlider.progress = guidePercent - CalibrationGuide.MIN_PERCENT
                positionLabel.text = "중앙 기준선 위치: ${guidePercent}%"
                axisButton.text = "가로/세로 기준선 변경 · " + axisLabel()
                guideNotice.text = "기준선 방향 변경됨. 영점 저장 시 반영됩니다."
            } else {
                guideNotice.text = "보정 범위 또는 창 위치가 유효하지 않습니다."
            }
        }
        positionSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val requested = CalibrationGuide.MIN_PERCENT + progress
                if (changeGuide(pair, guideAxis, requested)) {
                    positionLabel.text = "중앙 기준선 위치: ${guidePercent}%"
                } else {
                    positionSlider.progress = guidePercent - CalibrationGuide.MIN_PERCENT
                    guideNotice.text = "선택한 기준선은 안전한 보정 영역을 벗어났습니다."
                }
            }
        })
        column.addView(positionSlider)
        addButton(column, "중앙 기준선 50%") {
            if (changeGuide(pair, guideAxis, CalibrationGuide.DEFAULT_PERCENT)) {
                positionSlider.progress = guidePercent - CalibrationGuide.MIN_PERCENT
                positionLabel.text = "중앙 기준선 위치: ${guidePercent}%"
            }
        }
        addButton(column, "영점 저장") {
            val current = pairProvider().pair
            val redPoint = red?.let(::center)
            val whitePoint = white?.let(::center)
            if (current != pair || side != sideProvider()) {
                endCalibration()
                return@addButton
            }
            val corrected = if (redPoint != null && whitePoint != null) {
                ThumbReferenceMotion.moveAutomaticOnly(
                    current.source(side).bounds,
                    current.target(side).bounds,
                    redPoint, whitePoint, safeInsetPx())
            } else null
            if (corrected == null) {
                guideNotice.text = "터치점을 앱의 안전 입력 영역 안에 놓아 주세요."
            } else {
                if (!onCalibrationSaved(pair, side, corrected)) {
                    endCalibration()
                    return@addButton
                }
                guidePrefs.edit()
                    .putString("axis_${pair.orientation.name}", guideAxis.name)
                    .putInt("position_${pair.orientation.name}_${guideAxis.name}", guidePercent)
                    .apply()
                endCalibration()
            }
        }
        addButton(column, "영점 초기화") {
            if (onCalibrationReset(pair, side)) {
                guidePrefs.edit()
                    .remove("axis_${pair.orientation.name}")
                    .remove("position_${pair.orientation.name}_VERTICAL")
                    .remove("position_${pair.orientation.name}_HORIZONTAL")
                    .apply()
            }
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

    private fun changeGuide(
        original: WindowPair,
        nextAxis: GuideAxis,
        nextPercent: Int,
    ): Boolean {
        val current = pairProvider().pair
        if (current != original) {
            endCalibration()
            return false
        }
        val source = current.source(sideProvider()).bounds
        val target = current.target(sideProvider()).bounds
        val requestedRed = CalibrationGuide.reference(source, nextAxis, nextPercent)
            ?: return false
        val proposed = ThumbReferenceMotion.moveSourceTogether(
            source, target, requestedRed, draftOffset, safeInsetPx()
        ) ?: return false
        val formerAxis = guideAxis
        val formerPercent = guidePercent
        guideAxis = nextAxis
        guidePercent = CalibrationGuide.clampPercent(nextPercent)
        if (!showGuides(current)) {
            guideAxis = formerAxis
            guidePercent = formerPercent
            showGuides(current)
            return false
        }
        red?.let { moveCenter(it, proposed.first) }
        white?.let { moveCenter(it, proposed.second) }
        return true
    }

    private fun clearGuides() {
        guideSegments.forEach { safeRemove(it.view) }
        guideSegments.clear()
    }

    private fun showGuides(pair: WindowPair): Boolean {
        clearGuides()
        val a = drawGuide(pair.left.bounds) ?: return false
        guideSegments.add(a)
        val b = drawGuide(pair.right.bounds) ?: run {
            clearGuides()
            return false
        }
        guideSegments.add(b)
        return true
    }

    private fun drawGuide(bounds: ScreenRect): GuideSegment? {
        val line = CalibrationGuide.line(bounds, guideAxis, guidePercent, dp(2))
            ?: return null
        val guide = View(context).apply {
            setBackgroundColor(Color.rgb(105, 105, 105))
            alpha = 0.75f
        }
        val layout = params(line.width, line.height, line.left, line.top).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return try {
            windowManager.addView(guide, layout)
            GuideSegment(guide, layout)
        } catch (_: RuntimeException) {
            null
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
        view.contentDescription = if (label == "자동") {
            "자동 터치 보정점. 드래그하면 이 점만 움직입니다"
        } else {
            "직접 터치 보정점. 드래그하면 두 점이 함께 움직입니다"
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
        clearGuides()
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

        view.setOnClickListener { onTap() }
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
                    if (!moved) view.performClick()
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

