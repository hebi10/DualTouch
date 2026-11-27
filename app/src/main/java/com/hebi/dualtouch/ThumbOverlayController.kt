package com.hebi.dualtouch

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.Toast
import com.hebi.dualtouch.core.ThumbUiState
import java.util.Locale
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
    private val stateProvider: () -> ThumbUiState,
    private val calibrationProvider: (WindowPair, TouchSide) -> CalibrationOffset,
    private val calibrationSavedProvider: (WindowPair, TouchSide) -> Boolean,
    private val onRunRequest: (Boolean) -> SafetyIssue,
    private val candidateProvider: () -> SafetyAssessment,
    private val onConnectApps: (TouchSide, AppPairApproval) -> SafetyIssue,
    private val onCalibrationStart: () -> SafetyIssue,
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
    private val palette: UiPalette get() = UiPalette(context)
    private val displayHandler = Handler(Looper.getMainLooper())
    private var pendingPairConfirmation: Runnable? = null
    private var pendingRunRequest: Runnable? = null
    private var pendingCalibrationExit: Runnable? = null
    private var menuStateLabel: TextView? = null
    private var menuRunButton: Button? = null
    private var menuRunStarts = false
    private val displayTick = object : Runnable {
        override fun run() {
            if (thumb == null) return
            updateThumbState()
            displayHandler.postDelayed(this, 250L)
        }
    }
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
            if (calibrating) return@configureDrag
            if (pendingPairConfirmation != null || pendingRunRequest != null) {
                cancelPairConfirmation()
                cancelRunRequest()
                onMenuVisibilityChanged(false)
            }
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
            view.setAutoSizeTextTypeUniformWithConfiguration(9, 18, 1, TypedValue.COMPLEX_UNIT_SP)
            displayHandler.removeCallbacks(displayTick)
            displayTick.run()
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun updateThumbState() {
        val state = stateProvider()
        thumb?.view?.let { view ->
            if (view.text.toString() != state.label) view.text = state.label
            val action = if (calibrating) "영점 편집 중에는 메뉴가 잠깁니다." else "누르면 메뉴를 엽니다."
            val description = "DualTouch Thumb. ${state.description}. $action"
            if (view.contentDescription?.toString() != description) view.contentDescription = description
        }
        menuStateLabel?.let { label ->
            if (label.text.toString() != state.description) label.text = state.description
            val ready = state.label == "대기" && state.description.startsWith("연결됨")
            label.setTextColor(if (ready) palette.success else if (state.label == "오류") palette.danger else palette.text)
            label.compoundDrawablePadding = dp(8)
            val hasIcon = label.compoundDrawablesRelative[0] != null
            if (ready != hasIcon) {
                label.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    if (ready) UiKit.icon(context, LineIcon.CHECK_BADGE, palette.success, 20) else null,
                    null, null, null
                )
            }
        }
        menuRunButton?.let { button ->
            val unchanged = menuRunStarts == !runningProvider()
            val label = if (!unchanged) "상태 변경 · 메뉴 다시 열기" else if (menuRunStarts) "실행" else "중지"
            if (button.text.toString() != label) button.text = label
            if (button.isEnabled != unchanged) {
                button.isEnabled = unchanged
                UiKit.styleAction(button, palette, primary = unchanged, danger = unchanged && !menuRunStarts)
            }
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
                appearance.directColor, appearance.markerOpacityPercent, "직접")
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
        if (label != "◎") marker.view.textSize = 12f
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

    fun updateSourceSide(pair: WindowPair?) =
        positionThumb(pair?.source(sideProvider())?.bounds)

    private fun positionThumb(source: ScreenRect?) {
        val marker = thumb ?: return
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
        cancelCalibrationExit()
        cancelPairConfirmation()
        cancelRunRequest()
        displayHandler.removeCallbacks(displayTick)
        menuStateLabel = null
        menuRunButton = null
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
        cancelCalibrationExit()
        cancelPairConfirmation()
        cancelRunRequest()
        onMenuVisibilityChanged(true)
        if (thumb == null) return
        val column = panel()
        menuStateLabel = TextView(context).apply {
            text = stateProvider().description
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (stateProvider().label == "대기") palette.success else palette.text)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }.also { column.addView(it) }
        val requestedStart = !runningProvider()
        menuRunStarts = requestedStart
        menuRunButton = UiKit.action(context, palette, if (requestedStart) "실행" else "중지", primary = true).apply {
            UiKit.styleAction(this, palette, primary = true, danger = !requestedStart)
            setOnClickListener {
                if (requestedStart != !runningProvider()) {
                    closeMenu()
                } else if (requestedStart) {
                    requestRunAfterMenuRemoval()
                } else {
                    // A stop request remains immediate.
                    when (val issue = onRunRequest(false)) {
                        SafetyIssue.NONE -> closeMenu()
                        SafetyIssue.UNAPPROVED_PAIR -> openPairSelection()
                        else -> openPairSelection(issue)
                    }
                }
            }
        }.also { UiKit.add(column, it, topDp = 4) }
        addButton(column, "좌/우 선택 · 앱 연결") {
            openPairSelection()
        }
        addButton(column, "영점 조절") { beginCalibration() }

        val position = IntArray(2)
        thumb?.view?.getLocationOnScreen(position)
        val preferredX = position[0] + (thumb?.view?.width ?: dp(52)) + dp(8)
        if (!showMenu(column, 242, preferredX, position[1])) {
            menuStateLabel = null
            menuRunButton = null
            onMenuVisibilityChanged(false)
        }
    }

    /**
     * Display packages captured from the two currently visible app windows.
     * The user must explicitly approve the current pair AND the input direction.
     */
    private fun openPairSelection(previousIssue: SafetyIssue? = null) {
        cancelCalibrationExit()
        cancelPairConfirmation()
        cancelRunRequest()
        // Inspect while the current overlay is still attached. Removing it first
        // can leave a window with no readable root in the framework snapshot.
        // Keep capture paused across the replacement; approval revalidates the pair.
        onMenuVisibilityChanged(true)
        if (thumb == null) return
        val snapshot = candidateProvider()
        closeMenu(notifyVisibility = false)
        if (thumb == null) return
        val column = panel()
        val pair = snapshot.pair
        val title = TextView(context).apply {
            text = "현재 분할 앱 연결"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(palette.text)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        column.addView(title)
        val issue = if (snapshot.allowed) previousIssue else snapshot.issue
        if (issue != null) {
            column.addView(TextView(context).apply {
                text = WindowSafetyPolicy.message(issue)
                textSize = 13f
                setTextColor(palette.secondary)
                setPadding(dp(10), dp(6), dp(10), dp(8))
            })
        }
        if (snapshot.allowed && pair != null) {
            val approval = AppPairApproval.fromPair(pair)
            val firstLabel = if (pair.orientation == SplitOrientation.HORIZONTAL) "왼쪽" else "위쪽"
            val secondLabel = if (pair.orientation == SplitOrientation.HORIZONTAL) "오른쪽" else "아래쪽"
            column.addView(UiKit.text(context, palette, "표시된 앱 두 개와 방향을 확인하고 승인해 주세요. 한 손가락 짧은 탭만 지원합니다.", 13f, secondary = true).apply {
                setPadding(dp(10), dp(0), dp(10), dp(8))
            })
            val identities = UiKit.column(context).apply {
                background = UiKit.shape(palette.surface, palette.line)
            }
            UiKit.add(identities, appIdentityRow(context, palette, firstLabel, pair.left.packageName))
            identities.addView(View(context).apply { setBackgroundColor(palette.line) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1)))
            UiKit.add(identities, appIdentityRow(context, palette, secondLabel, pair.right.packageName))
            UiKit.add(column, identities, bottomDp = 4)
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
        if (safeWindowBounds().width() >= dp(300) && context.resources.configuration.fontScale <= 1.3f) {
            val actions = UiKit.panePair(context, 8, 0.5f)
            addButton(actions.primary, "다시 검색") { openPairSelection() }
            addButton(actions.secondary, "닫기") { closeMenu() }
            UiKit.add(column, actions.row)
        } else {
            addButton(column, "다시 검색") { openPairSelection() }
            addButton(column, "닫기") { closeMenu() }
        }
        val at = IntArray(2)
        thumb?.view?.getLocationOnScreen(at)
        if (!showMenu(column, 316, at[0] + (thumb?.view?.width ?: dp(56)) + dp(8), at[1])) {
            onMenuVisibilityChanged(false)
        }
    }

    private fun cancelRunRequest() {
        pendingRunRequest?.let { displayHandler.removeCallbacks(it) }
        pendingRunRequest = null
    }

    private fun requestRunAfterMenuRemoval() {
        cancelRunRequest()
        val selectedThumb = thumb ?: return
        if (menu == null || calibrating || runningProvider()) return
        onMenuVisibilityChanged(true)
        if (thumb !== selectedThumb) return
        closeMenu(notifyVisibility = false)
        val start = object : Runnable {
            override fun run() {
                if (pendingRunRequest !== this) return
                pendingRunRequest = null
                if (thumb !== selectedThumb || menu != null || calibrating || runningProvider()) return
                // This remains an explicit single request. The service must
                // freshly verify the approved pair, environment and input state.
                val issue = onRunRequest(true)
                if (thumb !== selectedThumb) return
                when (issue) {
                    SafetyIssue.NONE -> onMenuVisibilityChanged(false)
                    SafetyIssue.UNAPPROVED_PAIR -> openPairSelection()
                    else -> openPairSelection(issue)
                }
            }
        }
        pendingRunRequest = start
        // Input stays paused while the removed menu window leaves the snapshot.
        displayHandler.postDelayed(start, 300L)
    }

    private fun cancelPairConfirmation() {
        pendingPairConfirmation?.let { displayHandler.removeCallbacks(it) }
        pendingPairConfirmation = null
    }

    private fun confirmPair(side: TouchSide, approval: AppPairApproval) {
        cancelPairConfirmation()
        val selectedThumb = thumb ?: return
        if (menu == null || calibrating) return
        // Explicit selection stops any previous run. The displayed approval is
        // not installed until a fresh inspection after removing the menu.
        onMenuVisibilityChanged(true)
        if (thumb !== selectedThumb) return
        val stopped = onRunRequest(false)
        if (stopped != SafetyIssue.NONE) {
            openPairSelection(stopped)
            return
        }
        // Reposition only the control (not input). The safety check below still
        // requires the exact displayed package, window IDs and bounds.
        positionThumb(if (side == TouchSide.LEFT) approval.leftBounds else approval.rightBounds)
        closeMenu(notifyVisibility = false)
        val confirm = object : Runnable {
            override fun run() {
                if (pendingPairConfirmation !== this) return
                pendingPairConfirmation = null
                if (thumb !== selectedThumb || menu != null || calibrating) return
                val issue = onConnectApps(side, approval)
                if (thumb !== selectedThumb) return
                if (issue == SafetyIssue.NONE) {
                    onMenuVisibilityChanged(false)
                } else {
                    openPairSelection(issue)
                }
            }
        }
        pendingPairConfirmation = confirm
        // Let WindowManager publish the new topology; no capture is permitted
        // during this interval. Do not retry a rejected approval automatically.
        displayHandler.postDelayed(confirm, 300L)
    }

    private fun beginCalibration() {
        val selectedThumb = thumb ?: return
        if (menu == null || calibrating) return
        cancelCalibrationExit()
        onMenuVisibilityChanged(true)
        if (thumb !== selectedThumb) return
        val stopped = onCalibrationStart()
        if (thumb !== selectedThumb) return
        if (stopped != SafetyIssue.NONE) {
            openPairSelection(stopped)
            return
        }
        // Read the actual approved pair while its menu is still attached.
        val assessment = pairProvider()
        val pair = assessment.pair
        if (!assessment.allowed || pair == null) {
            openPairSelection(assessment.issue)
            return
        }
        val side = sideProvider()
        val saved = calibrationSavedProvider(pair, side)
        var feedback: TextView? = null
        fun showFeedback(message: String) { feedback?.text = message }
        fun offsetText(offset: CalibrationOffset): String = String.format(Locale.KOREA,
            "보정값: 가로 %+.1f%% · 세로 %+.1f%%", offset.dx * 100f, offset.dy * 100f)
        // Never notify an input-resume transition between two modal overlays.
        calibrating = true
        closeMenu(notifyVisibility = false)
        // Keep the real control's accessibility root available. The existing
        // "보정" state is shown and menu/drag input is disabled during editing.
        updateThumbState()

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
            Toast.makeText(context, "현재 창에서 기준점을 만들 수 없습니다. 앱을 다시 연결해 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        val startWhite = CoordinateMapper.map(source, target, startRed.x, startRed.y,
            safeInsetPx(), draftOffset)
        if (startWhite == null || !showGuides(pair)) {
            endCalibration()
            Toast.makeText(context, "기준점이 안전 영역을 벗어났습니다. 창 크기와 기준선 위치를 확인해 주세요.", Toast.LENGTH_LONG).show()
            return
        }

        val a = appearanceSettings.get()
        red = createMarker(startRed, "직접", a.directColor,
            AppearanceRules.readableTextColor(a.directColor), Color.WHITE)
        white = createMarker(startWhite, "자동", a.automaticColor,
            AppearanceRules.readableTextColor(a.automaticColor), Color.DKGRAY)
        if (red == null || white == null) {
            endCalibration()
            Toast.makeText(context, "영점 표시를 열 수 없습니다. 접근성 상태를 확인해 주세요.", Toast.LENGTH_LONG).show()
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
                    requestedRed, draftOffset, safeInsetPx()) ?: run {
                    showFeedback("직접점과 자동점을 모두 안전 영역 안에 놓아 주세요.")
                    return@configureDrag
                }
                moveCenter(marker, proposed.first)
                white?.let { moveCenter(it, proposed.second) }
                showFeedback(offsetText(draftOffset))
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
                    redPoint, requestedWhite, safeInsetPx()) ?: run {
                    showFeedback("자동점이 안전 영역 또는 허용 보정 범위를 벗어났습니다.")
                    return@configureDrag
                }
                draftOffset = offset
                moveCenter(marker, requestedWhite)
                showFeedback(offsetText(draftOffset))
            }, onTap = {})
        }

        val frame = panel()
        val canFoldPanel = pair.orientation == SplitOrientation.VERTICAL
        val column = if (canFoldPanel) UiKit.column(context) else frame
        val folded = if (canFoldPanel) UiKit.column(context).apply { visibility = View.GONE } else null
        var expandedPosition: Pair<Int, Int>? = null

        fun setFolded(value: Boolean) {
            val controls = folded ?: return
            val scroll = menu as? ScrollView ?: return
            val layout = scroll.layoutParams as? WindowManager.LayoutParams ?: return
            if (!calibrating) return
            // Keep the same attached modal and actual reference markers. A
            // position change affects only this panel, not input or calibration.
            if (value) {
                val safe = safeWindowBounds()
                val markerBounds = listOfNotNull(red?.view, white?.view).map { view ->
                    val at = IntArray(2)
                    view.getLocationOnScreen(at)
                    ScreenRect(at[0], at[1], at[0] + view.width, at[1] + view.height)
                }
                if (markerBounds.size != 2 || markerBounds.any { !it.isValid }) return
                val obstacles = markerBounds + listOfNotNull(thumb?.view).map { view ->
                    val at = IntArray(2)
                    view.getLocationOnScreen(at)
                    ScreenRect(at[0], at[1], at[0] + view.width, at[1] + view.height)
                }.filter { it.isValid }
                column.visibility = View.GONE
                controls.visibility = View.VISIBLE
                val width = layout.width.coerceAtMost(safe.width()).coerceAtLeast(1)
                frame.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val place = com.hebi.dualtouch.core.CalibrationPanelPlacement.find(
                    ScreenRect(safe.left, safe.top, safe.right, safe.bottom),
                    width, frame.measuredHeight, obstacles, layout.x, layout.y, dp(4))
                if (place == null) {
                    controls.visibility = View.GONE
                    column.visibility = View.VISIBLE
                    scroll.requestLayout()
                    Toast.makeText(context, "기준점을 가리지 않는 패널 공간이 부족합니다.", Toast.LENGTH_SHORT).show()
                    return
                }
                expandedPosition = layout.x to layout.y
                layout.x = place.left
                layout.y = place.top
            } else {
                val original = expandedPosition ?: return
                column.visibility = View.VISIBLE
                controls.visibility = View.GONE
                layout.x = original.first
                layout.y = original.second
                expandedPosition = null
            }
            try {
                windowManager.updateViewLayout(scroll, layout)
            } catch (_: RuntimeException) {
                // Do not continue editing with an uncertain overlay state.
                endCalibration()
                return
            }
            scroll.requestLayout()
            scroll.post { if (calibrating && menu === scroll) scroll.scrollTo(0, 0) }
        }
        folded?.let { controls ->
            UiKit.add(frame, column)
            UiKit.add(frame, controls)
            if (safeWindowBounds().width() >= dp(276) &&
                context.resources.configuration.fontScale <= 1.3f) {
                val actions = UiKit.panePair(context, 4, 0.66f)
                addButton(actions.primary, "영점 설정 펼치기") { setFolded(false) }
                addButton(actions.secondary, "취소") { endCalibration() }
                UiKit.add(controls, actions.row)
            } else {
                addButton(controls, "영점 설정 펼치기") { setFolded(false) }
                addButton(controls, "취소") { endCalibration() }
            }
        }
        UiKit.add(column, UiKit.text(context, palette, "영점 조절", 18f, true).apply {
            setPadding(dp(4), dp(4), dp(4), dp(8))
        })
        if (canFoldPanel) {
            addButton(column, "기준점 조절 · 패널 접기") { setFolded(true) }
        }
        val layoutLabel = if (pair.orientation == SplitOrientation.HORIZONTAL) {
            "가로 (좌·우)"
        } else {
            "세로 (상·하)"
        }
        column.addView(TextView(context).apply {
            text = "앱 분할 배치: $layoutLabel\n" +
                "Android에서 분할 방향을 바꾸면 다시 연결하세요."
            textSize = 13f
            setTextColor(palette.text)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        })
        column.addView(TextView(context).apply {
            text = "직접점: 두 점 함께 이동\n자동점: 자동점만 이동\n현재 연결 및 선택 방향에만 적용됩니다."
            textSize = 13f
            setTextColor(palette.text)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        })
        val calibrationStatus = UiKit.text(context, palette,
            (if (saved) "저장된 영점 있음" else "미보정 · 기본값") + "\n" + offsetText(draftOffset), 13f, true).apply {
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        feedback = calibrationStatus
        column.addView(calibrationStatus)
        val guideNotice = TextView(context).apply {
            text = "기준선은 표시용입니다. 실제 분할 경계는 이동하지 않습니다."
            textSize = 12f
            setTextColor(palette.secondary)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        column.addView(guideNotice)
        val axisButton = UiKit.action(context, palette, "", primary = false)
        fun axisLabel() = if (guideAxis == GuideAxis.VERTICAL) "세로 기준선" else "가로 기준선"
        axisButton.text = "가로/세로 기준선 변경 · " + axisLabel()
        column.addView(axisButton)
        val positionLabel = TextView(context).apply {
            textSize = 14f
            setTextColor(palette.text)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        positionLabel.text = "중앙 기준선 위치: ${guidePercent}%"
        column.addView(positionLabel)
        val positionSlider = SeekBar(context).apply {
            max = CalibrationGuide.MAX_PERCENT - CalibrationGuide.MIN_PERCENT
            progress = guidePercent - CalibrationGuide.MIN_PERCENT
            minHeight = dp(52)
            progressTintList = android.content.res.ColorStateList.valueOf(palette.text)
            thumbTintList = android.content.res.ColorStateList.valueOf(palette.text)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(palette.line)
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
                showFeedback(offsetText(draftOffset))
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
                    guideNotice.text = "기준선 변경됨. 저장 시 반영됩니다."
                } else {
                    positionSlider.progress = guidePercent - CalibrationGuide.MIN_PERCENT
                    guideNotice.text = "선택한 기준선은 안전한 보정 영역을 벗어났습니다."
                }
            }
        })
        column.addView(positionSlider)
        val range = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        range.addView(UiKit.text(context, palette, "20%", 12f, secondary = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        range.addView(UiKit.text(context, palette, "80%", 12f, secondary = true))
        UiKit.add(column, range, bottomDp = 4)
        addButton(column, "중앙 기준선 50%") {
            if (changeGuide(pair, guideAxis, CalibrationGuide.DEFAULT_PERCENT)) {
                positionSlider.progress = guidePercent - CalibrationGuide.MIN_PERCENT
                positionLabel.text = "중앙 기준선 위치: ${guidePercent}%"
                guideNotice.text = "중앙 기준선 50%로 이동했습니다."
            }
        }
        addButton(column, "영점 저장", primary = true) {
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
                Toast.makeText(context, "현재 연결과 방향의 영점을 저장했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        val compactActions = safeWindowBounds().width() >= dp(300) &&
            context.resources.configuration.fontScale <= 1.3f
        val secondaryActions = if (compactActions) UiKit.panePair(context, 8, 0.5f) else null
        addButton(secondaryActions?.primary ?: column, "영점 초기화") {
            val reset = onCalibrationReset(pair, side)
            if (reset) {
                guidePrefs.edit()
                    .remove("axis_${pair.orientation.name}")
                    .remove("position_${pair.orientation.name}_VERTICAL")
                    .remove("position_${pair.orientation.name}_HORIZONTAL")
                    .apply()
            }
            endCalibration()
            Toast.makeText(context, if (reset) "현재 방향의 영점을 초기화했습니다." else "창이 변경되어 초기화할 수 없습니다. 다시 연결해 주세요.", Toast.LENGTH_LONG).show()
        }
        addButton(secondaryActions?.secondary ?: column, "취소") { endCalibration() }
        secondaryActions?.let { UiKit.add(column, it.row) }

        val bounds = safeWindowBounds()
        val panelWidth = dp(276)
        val preferredX = if (pair.orientation == SplitOrientation.HORIZONTAL && bounds.width() >= dp(720)) {
            (pair.left.bounds.right + pair.right.bounds.left) / 2 - panelWidth / 2
        } else bounds.left + dp(10)
        if (!showMenu(frame, 276, preferredX, bounds.top + dp(12))) {
            endCalibration()
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
            // Real accessible guide identity, not an ownership exemption.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "영점 기준선 · 표시용"
            isClickable = false
            isFocusable = false
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

    private fun cancelCalibrationExit() {
        pendingCalibrationExit?.let { displayHandler.removeCallbacks(it) }
        pendingCalibrationExit = null
    }

    private fun endCalibration() {
        cancelCalibrationExit()
        calibrating = false
        clearCalibration()
        closeMenu(notifyVisibility = false)
        val selectedThumb = thumb ?: return
        selectedThumb.view.visibility = View.VISIBLE
        updateThumbState()
        // Calibration has stopped the run. Keep modal pause until its windows
        // settle, then use the normal fresh safety inspection. All independent
        // window-change and emergency-stop checks remain active throughout.
        val finish = object : Runnable {
            override fun run() {
                if (pendingCalibrationExit !== this) return
                pendingCalibrationExit = null
                if (thumb !== selectedThumb || calibrating || menu != null ||
                    pendingPairConfirmation != null || pendingRunRequest != null) return
                onMenuVisibilityChanged(false)
            }
        }
        pendingCalibrationExit = finish
        displayHandler.postDelayed(finish, 300L)
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

    private fun closeMenu(notifyVisibility: Boolean = true) {
        val target = menu ?: return
        menuStateLabel = null
        menuRunButton = null
        menu = null
        safeRemove(target)
        if (notifyVisibility) onMenuVisibilityChanged(false)
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
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = GradientDrawable().apply {
            setColor(palette.background)
            setStroke(dp(1), palette.line)
        }
    }

    private fun addButton(parent: LinearLayout, label: String, primary: Boolean = false, action: () -> Unit) {
        UiKit.add(parent, UiKit.action(context, palette, label, primary = primary).apply {
            setOnClickListener { action() }
        }, topDp = 4)
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

