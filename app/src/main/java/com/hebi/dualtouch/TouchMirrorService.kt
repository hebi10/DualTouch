package com.hebi.dualtouch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.hebi.dualtouch.core.AppPairApproval
import com.hebi.dualtouch.core.AppPairPolicy
import com.hebi.dualtouch.core.CalibrationOffset
import com.hebi.dualtouch.core.DispatchGeneration
import com.hebi.dualtouch.core.ObservedWindow
import com.hebi.dualtouch.core.WindowLayer
import com.hebi.dualtouch.core.WindowSafetyPolicy
import com.hebi.dualtouch.core.SafetyAssessment
import com.hebi.dualtouch.core.SafetyIssue
import com.hebi.dualtouch.core.CoordinateMapper
import com.hebi.dualtouch.core.ScreenRect
import com.hebi.dualtouch.core.SplitOrientation
import com.hebi.dualtouch.core.TapPoint
import com.hebi.dualtouch.core.TestPackages
import com.hebi.dualtouch.core.TouchSide
import com.hebi.dualtouch.core.source
import com.hebi.dualtouch.core.target
import com.hebi.dualtouch.core.WindowPairResolver
import com.hebi.dualtouch.core.WindowSnapshot
import kotlin.math.abs

enum class MirrorMode {
    THUMB,
    DUAL_STROKE,
    SEQUENTIAL,
    NODE_CLICK,
}

class TouchMirrorService : AccessibilityService() {
    companion object {
        var current: TouchMirrorService? = null
            private set

        private const val ACTIVE_MS = 20_000L
        private const val READY_WAIT_MS = 60_000L
        private const val TAP_MAX_MS = 500L
        private const val STROKE_MS = 75L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val readyTimeout = Runnable { stop("테스트 창 준비 시간이 초과되었습니다.") }
    private val activeTimeout = Runnable {
        if (mode == MirrorMode.THUMB) {
            thumbRunning = false
            dispatchGeneration.invalidate()
            dispatching = false
            setCapture(false)
            downPoint = null
            overlay?.restoreAfterDispatch()
            statusText = "20초 자동 중지 · Thumb는 표시 중"
        } else {
            stop("20초 실험 시간이 끝났습니다.")
        }
    }

    private var armed = false
    private var capturing = false
    private var dispatching = false
    private var activeStarted = false
    private var mode = MirrorMode.DUAL_STROKE
    private var downPoint: TapPoint? = null
    private var downTime: Long = 0L
    private var tapRequests = 0
    private var activeWindowPair: com.hebi.dualtouch.core.WindowPair? = null
    private var overlay: ThumbOverlayController? = null
    private var sourceSide = TouchSide.LEFT
    private var approvedAppPair: AppPairApproval? = null
    private var thumbRunning = false
    private var menuPaused = false
    private val dispatchGeneration = DispatchGeneration()
    private var receiverRegistered = false
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF && armed) {
                stop("화면이 꺼져 터치 입력을 중단했습니다.")
            }
        }
    }
    private val calibrationPrefs by lazy { getSharedPreferences("thumb_zero_v1", MODE_PRIVATE) }

    private fun calibrationKey(side: TouchSide): String {
        val orientation = activeWindowPair?.orientation ?: SplitOrientation.HORIZONTAL
        return if (orientation == SplitOrientation.VERTICAL) {
            if (side == TouchSide.LEFT) "upper" else "lower"
        } else {
            if (side == TouchSide.LEFT) "left" else "right"
        }
    }

    private fun currentCalibration(side: TouchSide = sourceSide): CalibrationOffset {
        val prefix = calibrationKey(side)
        val offset = CalibrationOffset(
            calibrationPrefs.getFloat("${prefix}_dx", 0f),
            calibrationPrefs.getFloat("${prefix}_dy", 0f)
        )
        return if (offset.isSafe()) offset else CalibrationOffset.ZERO
    }

    private fun saveCalibration(offset: CalibrationOffset) {
        if (!offset.isSafe()) return
        val prefix = calibrationKey(sourceSide)
        calibrationPrefs.edit()
            .putFloat("${prefix}_dx", offset.dx)
            .putFloat("${prefix}_dy", offset.dy)
            .apply()
        statusText = "영점 저장됨 (선택 방향에만 적용)."
    }

    private fun resetCalibration() {
        val prefix = calibrationKey(sourceSide)
        calibrationPrefs.edit()
            .remove("${prefix}_dx")
            .remove("${prefix}_dy")
            .apply()
        statusText = "선택 방향의 영점이 초기화되었습니다."
    }

    private fun setThumbRunning(enabled: Boolean): SafetyIssue {
        if (mode != MirrorMode.THUMB || !armed) return SafetyIssue.UNAPPROVED_PAIR
        val assessment = inspectTestWindows()
        val pair = assessment.pair
        if (enabled && (!assessment.allowed ||
                pair == null || (activeStarted && pair != activeWindowPair))) {
            dispatchGeneration.invalidate()
            thumbRunning = false
            setCapture(false)
            val issue = if (assessment.allowed) SafetyIssue.PAIR_CHANGED else assessment.issue
            statusText = "실행 불가: " + WindowSafetyPolicy.message(issue)
            return issue
        }
        dispatchGeneration.invalidate()
        dispatching = false
        thumbRunning = enabled
        handler.removeCallbacks(activeTimeout)
        if (enabled) {
            handler.postDelayed(activeTimeout, ACTIVE_MS)
            statusText = "실행 중 · 20초 자동 중지 · 긴급 중지는 볼륨 아래"
        } else {
            setCapture(false)
            downPoint = null
            overlay?.restoreAfterDispatch()
            statusText = "입력 중지 · Thumb는 계속 표시"
        }
        refreshCapture()
        return SafetyIssue.NONE
    }

    private fun toggleThumbRunning(): SafetyIssue = setThumbRunning(!thumbRunning)

    private fun candidateAppPair(): SafetyAssessment =
        AppPairPolicy.select(observeWindows(), packageName)

    private fun connectVisibleApps(side: TouchSide, displayedPair: AppPairApproval): SafetyIssue {
        if (!armed || mode != MirrorMode.THUMB) return SafetyIssue.UNAPPROVED_PAIR
        val assessment = AppPairPolicy.verify(observeWindows(), packageName, displayedPair)
        val pair = assessment.pair
        if (!assessment.allowed || pair == null) {
            statusText = "연결 불가: " + WindowSafetyPolicy.message(assessment.issue)
            return assessment.issue
        }
        dispatchGeneration.invalidate()
        dispatching = false
        thumbRunning = false
        downPoint = null
        setCapture(false)
        handler.removeCallbacks(activeTimeout)
        approvedAppPair = displayedPair
        activeWindowPair = pair
        activeStarted = true
        sourceSide = side
        calibrationPrefs.edit().putString("source_side", side.name).apply()
        val arrangement = if (pair.orientation == SplitOrientation.HORIZONTAL) "좌·우" else "상·하"
        statusText = "$arrangement 연결 완료: ${pair.left.packageName} / ${pair.right.packageName}. Thumb에서 실행하세요."
        return SafetyIssue.NONE
    }

    private fun prepareCalibration() {
        setThumbRunning(false)
        setCapture(false)
    }

    private fun setMenuVisible(visible: Boolean) {
        menuPaused = visible
        if (visible) {
            dispatchGeneration.invalidate()
            dispatching = false
            downPoint = null
            setCapture(false)
        } else if (armed) {
            refreshCapture()
        }
    }

    var statusText: String = "서비스 대기"
        private set

    val phase: com.hebi.dualtouch.core.InteractionPhase
        get() = com.hebi.dualtouch.core.InteractionPhaseResolver.resolve(
            armed = armed,
            pairAvailable = activeStarted && activeWindowPair != null,
            running = if (mode == MirrorMode.THUMB) thumbRunning else activeStarted,
            captureEnabled = capturing,
            modalOpen = menuPaused || overlay?.isCalibrating == true,
        )

    val isArmed: Boolean get() = armed
    val isFloatingVisible: Boolean get() =
        armed && mode == MirrorMode.THUMB && overlay != null

    fun refreshFloatingAppearance() {
        overlay?.updateAppearance()
    }

    fun hideFloatingControl() {
        stop("사용자가 플로팅 숨김")
    }

    fun showFloatingControl(): Boolean {
        if (!AppearanceSettings(this).accessibilityDisclosureAccepted) {
            statusText = "접근성 사용 고지 확인과 명시적 동의가 필요합니다."
            return false
        }
        if (isFloatingVisible) return true
        if (armed) stop("이전 기술 실험 종료")
        dispatchGeneration.invalidate()
        mode = MirrorMode.THUMB
        sourceSide = runCatching {
            TouchSide.valueOf(
                calibrationPrefs.getString("source_side", TouchSide.LEFT.name)
                    ?: TouchSide.LEFT.name
            )
        }.getOrDefault(TouchSide.LEFT)
        armed = true
        thumbRunning = false
        menuPaused = false
        dispatching = false
        activeStarted = false
        activeWindowPair = null
        approvedAppPair = null
        downPoint = null
        tapRequests = 0
        setCapture(false)
        handler.removeCallbacks(activeTimeout)
        handler.removeCallbacks(readyTimeout)
        val shown = ensureThumbOverlay()
        if (!shown) {
            stop("플로팅 Thumb 생성 실패")
            return false
        }
        statusText = "플로팅 표시됨 · Thumb를 눌러 실행·중지 / 좌·우 / 영점 조절"
        refreshCapture()
        return true
    }

    private fun ensureThumbOverlay(): Boolean {
        if (overlay != null) return true
        overlay = ThumbOverlayController(
            context = this,
            pairProvider = ::resolveTestWindows,
            sideProvider = { sourceSide },
            runningProvider = { thumbRunning },
            calibrationProvider = { currentCalibration() },
            onRunToggle = ::toggleThumbRunning,
            candidateProvider = ::candidateAppPair,
            onConnectApps = ::connectVisibleApps,
            onCalibrationStart = ::prepareCalibration,
            onCalibrationSaved = ::saveCalibration,
            onCalibrationReset = ::resetCalibration,
            onMenuVisibilityChanged = ::setMenuVisible,
        )
        if (overlay?.show(resolveTestWindows()) == true) return true
        overlay?.hide()
        overlay = null
        return false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        stop("접근성 서비스 연결됨. 기본 OFF")
        if (!receiverRegistered) {
            registerReceiver(
                screenOffReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                Context.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        val info = serviceInfo
        if (info != null) {
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            info.setMotionEventSources(0)
            serviceInfo = info
        }
        capturing = false
        statusText = "접근성 서비스 연결됨. 기본 OFF."
    }

    fun startExperiment(selectedMode: MirrorMode): Boolean {
        if (!AppearanceSettings(this).accessibilityDisclosureAccepted) {
            statusText = "접근성 사용 고지 확인과 명시적 동의가 필요합니다."
            return false
        }
        if (selectedMode == MirrorMode.THUMB) return showFloatingControl()
        if (armed) return false
        dispatchGeneration.invalidate()
        mode = selectedMode
        sourceSide = runCatching {
            TouchSide.valueOf(calibrationPrefs.getString("source_side", TouchSide.LEFT.name) ?: TouchSide.LEFT.name)
        }.getOrDefault(TouchSide.LEFT)
        thumbRunning = false
        menuPaused = false
        armed = true
        dispatching = false
        activeStarted = false
        activeWindowPair = null
        approvedAppPair = null
        overlay?.hide()
        overlay = null
        tapRequests = 0
        downPoint = null
        statusText = "왼쪽·오른쪽 테스트 앱 창을 기다리는 중 (60초 제한)"
        handler.removeCallbacks(readyTimeout)
        handler.removeCallbacks(activeTimeout)
        handler.postDelayed(readyTimeout, READY_WAIT_MS)
        refreshCapture()
        return armed
    }

    fun stop(message: String = "사용자 중지") {
        dispatchGeneration.invalidate()
        armed = false
        activeStarted = false
        activeWindowPair = null
        approvedAppPair = null
        dispatching = false
        thumbRunning = false
        menuPaused = false
        overlay?.hide()
        overlay = null
        downPoint = null
        handler.removeCallbacks(readyTimeout)
        handler.removeCallbacks(activeTimeout)
        setCapture(false)
        statusText = "OFF: $message"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!armed) return
        if (!environmentAllowsInput()) {
            stop("잠금·화면 꺼짐·동의 철회·터치 탐색 상태로 입력 중지")
            return
        }
        if (dispatching) {
            val assessment = inspectTestWindows()
            if (!assessment.allowed || assessment.pair != activeWindowPair) {
                stop("입력 중 창 또는 민감 화면이 변경되어 중지")
            }
        } else {
            refreshCapture()
        }
    }

    override fun onInterrupt() {
        stop("서비스가 중단되었습니다.")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (armed) stop("회전·폴드·창 환경 변경으로 터치 중지")
    }

    override fun onUnbind(intent: Intent): Boolean {
        stop("접근성 권한 또는 서비스 종료")
        removeScreenOffReceiver()
        current = null
        return super.onUnbind(intent)
    }

    private fun removeScreenOffReceiver() {
        if (receiverRegistered) {
            unregisterReceiver(screenOffReceiver)
            receiverRegistered = false
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (armed && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) stop("볼륨 아래 긴급 중지")
            return true
        }
        return false
    }

    override fun onMotionEvent(event: MotionEvent) {
        if (!armed || !capturing || dispatching) return
        if (mode == MirrorMode.THUMB && approvedAppPair == null) {
            stop("연결된 앱이 없어 입력을 차단했습니다.")
            return
        }
        if (event.pointerCount != 1) {
            stop("여러 손가락 입력은 지원하지 않습니다.")
            return
        }

        val point = TapPoint(event.rawX, event.rawY)
        if (!point.x.isFinite() || !point.y.isFinite()) {
            stop("잘못된 터치 좌표로 중단")
            return
        }
        if (inspectTestWindows().allowed.not()) {
            stop("허용되지 않은 앱·키보드·팝업 창이 감지되어 중지")
            return
        }
        if (mode == MirrorMode.THUMB && event.actionMasked == MotionEvent.ACTION_DOWN &&
            overlay?.isControlAt(point.x, point.y) == true
        ) {
            downPoint = null
            setCapture(false)
            overlay?.openMenuFromCapturedTouch()
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downPoint = point
                downTime = SystemClock.uptimeMillis()
                val pair = resolveTestWindows()
                if (pair == null ||
                    (!pair.left.bounds.contains(point.x, point.y, 24f) &&
                        !pair.right.bounds.contains(point.x, point.y, 24f))
                ) {
                    stop("테스트 창 바깥 터치로 안전 중지")
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val down = downPoint ?: return
                if (abs(down.x - point.x) > 24f || abs(down.y - point.y) > 24f) {
                    stop("드래그/스크롤은 지원하지 않아 중지했습니다.")
                }
            }
            MotionEvent.ACTION_UP -> {
                val down = downPoint ?: return
                downPoint = null
                val elapsed = SystemClock.uptimeMillis() - downTime
                if (elapsed > TAP_MAX_MS ||
                    abs(down.x - point.x) > 24f ||
                    abs(down.y - point.y) > 24f
                ) {
                    stop("길게 누르기 또는 이동 입력으로 중지했습니다.")
                    return
                }
                handleTap(point)
            }
            MotionEvent.ACTION_CANCEL -> stop("원본 터치 취소 감지")
        }
    }

    private fun handleTap(touch: TapPoint) {
        val assessment = inspectTestWindows()
        val pair = assessment.pair
        if (!assessment.allowed || pair == null) {
            stop("안전 확인 실패: " + WindowSafetyPolicy.message(assessment.issue))
            return
        }
        val safeInset = 24f
        if (mode == MirrorMode.THUMB) {
            if (!thumbRunning || menuPaused || overlay?.isCalibrating == true) return
            val input = pair.source(sourceSide).bounds
            val output = pair.target(sourceSide).bounds
            if (output.contains(touch.x, touch.y, safeInset)) {
                // The user still needs to be able to tap the opposite test app normally.
                overlay?.hideForDispatch()
                replayGesture(listOf(touch), false)
                return
            }
            if (!input.contains(touch.x, touch.y, safeInset)) {
                stop("터치 범위 이탈")
                return
            }
            val automatic = CoordinateMapper.map(
                input, output, touch.x, touch.y, safeInset, currentCalibration()
            ) ?: run {
                stop("영점 보정이 창 밖을 가리킵니다.")
                return
            }
            tapRequests += 1
            overlay?.hideForDispatch()
            replayGesture(listOf(touch, automatic), true)
            return
        }
        val source = when {
            pair.left.bounds.contains(touch.x, touch.y, safeInset) -> pair.left
            pair.right.bounds.contains(touch.x, touch.y, safeInset) -> pair.right
            else -> {
                stop("테스트 입력 범위 이탈")
                return
            }
        }
        tapRequests += 1

        if (source.packageName == TestPackages.RIGHT) {
            if (mode == MirrorMode.THUMB) {
                stop("썸 모드는 왼쪽 창 안에서만 실행할 수 있습니다.")
            } else {
                replayGesture(listOf(touch), false)
            }
            return
        }

        val transformed = CoordinateMapper.map(
            pair.left.bounds,
            pair.right.bounds,
            touch.x,
            touch.y,
            safeInset,
            currentCalibration(TouchSide.LEFT),
        ) ?: run {
            stop("좌표 변환이 안전 영역을 벗어났습니다.")
            return
        }

        when (mode) {
            MirrorMode.THUMB -> return
            MirrorMode.DUAL_STROKE -> replayGesture(listOf(touch, transformed), false)
            MirrorMode.SEQUENTIAL -> replayGesture(listOf(touch, transformed), true)
            MirrorMode.NODE_CLICK -> {
                setCapture(false)
                val matched = findTestWindows()
                val leftClicked = matched?.first?.root?.let {
                    clickNodeAt(it, touch.x.toInt(), touch.y.toInt())
                } ?: false
                val rightClicked = matched?.second?.root?.let {
                    clickNodeAt(it, transformed.x.toInt(), transformed.y.toInt())
                } ?: false
                if (!leftClicked || !rightClicked) {
                    stop("양쪽 노드 클릭 실행에 실패했습니다.")
                } else {
                    statusText = "접근성 클릭 요청 $tapRequests 회. 실제 카운터 확인 필요."
                    refreshCapture()
                }
            }
        }
    }

    private fun canDispatch(token: Long): Boolean {
        if (!dispatchGeneration.valid(token) || !armed || !environmentAllowsInput()) return false
        if (mode == MirrorMode.THUMB &&
            (!thumbRunning || menuPaused || overlay?.isCalibrating == true)
        ) return false
        val assessment = inspectTestWindows()
        return assessment.allowed && assessment.pair == activeWindowPair
    }

    private fun replayGesture(points: List<TapPoint>, sequential: Boolean) {
        if (dispatching) return
        val token = dispatchGeneration.current()
        if (!canDispatch(token)) {
            stop("터치 입력 전 창·상태 안전 검증 실패")
            return
        }
        setCapture(false)
        dispatching = true
        if (sequential && points.size == 2) {
            dispatchPoints(listOf(points[0]), token) { firstSuccess ->
                if (!firstSuccess) {
                    completeDispatch(false, token)
                } else if (canDispatch(token)) {
                    dispatchPoints(listOf(points[1]), token) {
                        completeDispatch(it, token)
                    }
                } else {
                    completeDispatch(false, token)
                }
            }
        } else {
            dispatchPoints(points, token) { completeDispatch(it, token) }
        }
    }

    private fun dispatchPoints(
        points: List<TapPoint>,
        token: Long,
        result: (Boolean) -> Unit,
    ) {
        if (!canDispatch(token)) {
            result(false)
            return
        }
        val description = GestureDescription.Builder()
        points.forEach { point ->
            val path = Path().apply { moveTo(point.x, point.y) }
            description.addStroke(
                GestureDescription.StrokeDescription(path, 0L, STROKE_MS)
            )
        }
        val accepted = dispatchGesture(
            description.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (dispatchGeneration.valid(token)) result(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (dispatchGeneration.valid(token)) result(false)
                }
            },
            handler,
        )
        if (!accepted) result(false)
    }

    private fun completeDispatch(success: Boolean, token: Long) {
        if (!dispatchGeneration.valid(token)) return
        dispatching = false
        if (!armed) return
        if (!success) {
            stop("Android 제스처 취소 또는 주입 실패")
        } else {
            statusText = "제스처 전송 $tapRequests 회. 양쪽 카운터로 실제 반응 확인."
            overlay?.restoreAfterDispatch()
            refreshCapture()
        }
    }

    private fun clickNodeAt(node: AccessibilityNodeInfo, x: Int, y: Int): Boolean {
        return findClickableNode(node, x, y, 0)?.performAction(
            AccessibilityNodeInfo.ACTION_CLICK
        ) ?: false
    }

    private fun findClickableNode(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        depth: Int,
    ): AccessibilityNodeInfo? {
        if (depth > 20) return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findClickableNode(child, x, y, depth + 1)
            if (found != null) return found
        }
        return node.takeIf { it.isClickable && it.isEnabled && it.isVisibleToUser }
    }

    private fun refreshCapture() {
        if (!armed || dispatching) return
        if (!environmentAllowsInput()) {
            stop("잠금·화면 꺼짐·동의 철회·터치 탐색 상태로 입력 중지")
            return
        }
        val assessment = inspectTestWindows()
        val pair = assessment.pair
        if (mode == MirrorMode.THUMB) {
            if (!ensureThumbOverlay()) {
                stop("플로팅 Thumb 생성 실패")
                return
            }
            if (!assessment.allowed || pair == null) {
                if (thumbRunning) {
                    thumbRunning = false
                    dispatchGeneration.invalidate()
                    dispatching = false
                    handler.removeCallbacks(activeTimeout)
                }
                if (approvedAppPair != null &&
                    assessment.issue != SafetyIssue.NONE &&
                    assessment.issue != SafetyIssue.UNAPPROVED_PAIR
                ) {
                    approvedAppPair = null
                }
                activeStarted = false
                activeWindowPair = null
                downPoint = null
                setCapture(false)
                overlay?.restoreAfterDispatch()
                statusText = "Thumb 대기: " + WindowSafetyPolicy.message(assessment.issue)
                return
            }

            if (activeStarted && activeWindowPair != pair) {
                if (thumbRunning) {
                    stop("분할 창 위치·크기 변경으로 터치 입력 중지")
                    return
                }
                overlay?.updateSourceSide()
            }
            if (!activeStarted) overlay?.updateSourceSide()
            activeStarted = true
            activeWindowPair = pair
            setCapture(thumbRunning && !menuPaused && overlay?.isCalibrating != true)
            statusText = when {
                overlay?.isCalibrating == true ->
                    "영점 편집 중 · 직접 기준점과 자동 기준점 정렬"
                thumbRunning -> {
                    val direction = if (pair.orientation == SplitOrientation.HORIZONTAL) {
                        if (sourceSide == TouchSide.LEFT) "좌 → 우" else "우 → 좌"
                    } else {
                        if (sourceSide == TouchSide.LEFT) "상 → 하" else "하 → 상"
                    }
                    "실행 중: $direction · 20초 제한"
                }
                else -> "Thumb 대기 · 메뉴에서 실행·중지 / 좌·우 / 영점"
            }
            return
        }

        if (!assessment.allowed || pair == null) {
            if (activeStarted) {
                stop("다른 창 감지: " + WindowSafetyPolicy.message(assessment.issue))
            } else {
                setCapture(false)
                statusText = "화면 준비 대기: " + WindowSafetyPolicy.message(assessment.issue)
            }
            return
        }
        if (activeStarted && activeWindowPair != pair) {
            stop("분할 창의 위치·크기가 변경되어 자동 중지")
            return
        }
        if (!activeStarted) {
            activeWindowPair = pair
            activeStarted = true
            handler.removeCallbacks(readyTimeout)
            handler.postDelayed(activeTimeout, ACTIVE_MS)
        }
        setCapture(true)
        statusText = "고급 실험 ACTIVE (최대 20초) · 볼륨 아래 긴급 중지"
    }

    private fun observeWindows(): List<ObservedWindow> {
        return windows.map { window ->
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            val layer = when (window.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> WindowLayer.APPLICATION
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowLayer.INPUT_METHOD
                AccessibilityWindowInfo.TYPE_SYSTEM -> WindowLayer.SYSTEM
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> WindowLayer.ACCESSIBILITY
                AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> WindowLayer.SPLIT_DIVIDER
                AccessibilityWindowInfo.TYPE_WINDOW_CONTROL -> WindowLayer.WINDOW_CONTROL
                else -> WindowLayer.OTHER
            }
            val root = window.root
            ObservedWindow(
                layer = layer,
                packageName = root?.packageName?.toString(),
                bounds = ScreenRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                displayId = window.displayId,
                focused = window.isFocused,
                windowId = window.id,
                sensitiveInput = if (layer == WindowLayer.APPLICATION) {
                    containsSensitiveInput(root)
                } else false,
            )
        }
    }

    private fun containsSensitiveInput(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return true
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            visited++
            if (node.isPassword || (node.isEditable && node.isFocused)) return true
            if (visited >= 256) return true
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return false
    }

    private fun environmentAllowsInput(): Boolean =
        AppearanceSettings(this).accessibilityDisclosureAccepted &&
            getSystemService(PowerManager::class.java)?.isInteractive == true &&
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false &&
            getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == false

    private fun inspectTestWindows(): SafetyAssessment {
        val visible = observeWindows()
        return if (mode == MirrorMode.THUMB) {
            AppPairPolicy.verify(visible, packageName, approvedAppPair)
        } else {
            WindowSafetyPolicy.inspect(visible, packageName)
        }
    }

    private fun resolveTestWindows(): com.hebi.dualtouch.core.WindowPair? =
        inspectTestWindows().pair

    private fun findTestWindows(): Pair<AccessibilityWindowInfo, AccessibilityWindowInfo>? {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val left = apps.singleOrNull {
            it.root?.packageName?.toString() == TestPackages.LEFT
        } ?: return null
        val right = apps.singleOrNull {
            it.root?.packageName?.toString() == TestPackages.RIGHT
        } ?: return null
        return left to right
    }

    private fun setCapture(enabled: Boolean) {
        if (capturing == enabled) return
        val info = serviceInfo ?: return
        info.setMotionEventSources(if (enabled) InputDevice.SOURCE_TOUCHSCREEN else 0)
        serviceInfo = info
        capturing = enabled
    }

    override fun onDestroy() {
        stop("서비스 해제")
        removeScreenOffReceiver()
        current = null
        super.onDestroy()
    }
}

