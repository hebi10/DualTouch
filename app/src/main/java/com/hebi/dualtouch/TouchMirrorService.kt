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
import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.hebi.dualtouch.core.AppPairApproval
import com.hebi.dualtouch.core.AppPairPolicy
import com.hebi.dualtouch.core.CalibrationOffset
import com.hebi.dualtouch.core.InputInspection
import com.hebi.dualtouch.core.DispatchGeneration
import com.hebi.dualtouch.core.PairCalibration
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
import com.hebi.dualtouch.core.SplitWorkArea
import com.hebi.dualtouch.core.WindowPair
import com.hebi.dualtouch.core.WindowTap
import com.hebi.dualtouch.core.inspectInputTree
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
        private const val INPUT_NODE_LIMIT = 256
    }

    private val handler = Handler(Looper.getMainLooper())
    private val contentRefresh = Runnable { checkActiveWindows() }
    private val readyTimeout = Runnable { stop("테스트 창 준비 시간이 초과되었습니다.") }
    private val activeTimeout = Runnable {
        if (mode == MirrorMode.THUMB) {
            thumbRunning = false
            dispatchGeneration.invalidate()
            dispatching = false
            setCapture(false)
            downPoint = null
            statusText = "20초 자동 중지 · Thumb는 표시 중"
        } else {
            stop("20초 실험 시간이 끝났습니다.")
        }
    }

    private var armed = false
    private var capturing = false
    private var dispatching = false
    private var gestureInFlight = false
    private var inputDeadline = 0L
    private var interceptedVolumeDown: Long? = null
    private var activeStarted = false
    private var mode = MirrorMode.DUAL_STROKE
    private var downPoint: TapPoint? = null
    private var downTime: Long = 0L
    private var activeWindowPair: WindowPair? = null
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

    private val calibration = PairCalibration()
    private val safeTapInset: Float get() = 24f * resources.displayMetrics.density
    private val touchSlop: Int get() = ViewConfiguration.get(this).scaledTouchSlop

    private fun saveCalibration(pair: WindowPair, side: TouchSide, offset: CalibrationOffset): Boolean {
        if (side != sourceSide || pair != resolveTestWindows()) return false
        return calibration.save(pair, side, offset)
    }

    private fun resetCalibration(pair: WindowPair, side: TouchSide): Boolean {
        if (side != sourceSide || pair != resolveTestWindows()) return false
        return calibration.reset(pair, side)
    }

    private fun startInputTimeout() {
        inputDeadline = SystemClock.uptimeMillis() + ACTIVE_MS
        handler.postAtTime(activeTimeout, inputDeadline)
    }

    private fun setThumbRunning(enabled: Boolean): SafetyIssue {
        if (mode != MirrorMode.THUMB || !armed) return SafetyIssue.UNAPPROVED_PAIR
        if (enabled) {
            if (gestureInFlight) return SafetyIssue.INPUT_FINISHING
            val assessment = inspectTestWindows()
            if (!assessment.allowed || assessment.pair != activeWindowPair) {
                return if (assessment.allowed) SafetyIssue.PAIR_CHANGED else assessment.issue
            }
        }
        dispatchGeneration.invalidate()
        dispatching = false
        thumbRunning = enabled
        handler.removeCallbacks(activeTimeout)
        if (enabled) {
            startInputTimeout()
            statusText = "실행 중 · 20초 자동 중지 · 긴급 중지는 볼륨 아래"
        } else {
            setCapture(false)
            downPoint = null
            statusText = "입력 중지 · Thumb는 계속 표시"
        }
        return SafetyIssue.NONE
    }

    private fun toggleThumbRunning(): SafetyIssue = setThumbRunning(!thumbRunning)

    private fun candidateAppPair(): SafetyAssessment =
        AppPairPolicy.select(observeWindows().map { it.metadata }, packageName, splitWorkArea())

    private fun connectVisibleApps(side: TouchSide, displayedPair: AppPairApproval): SafetyIssue {
        if (!armed || mode != MirrorMode.THUMB) return SafetyIssue.UNAPPROVED_PAIR
        val assessment = AppPairPolicy.verify(
            observeWindows().map { it.metadata }, packageName, splitWorkArea(), displayedPair,
        )
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
        calibration.setPair(pair)
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
            pairProvider = ::inspectTestWindows,
            sideProvider = { sourceSide },
            runningProvider = { thumbRunning },
            calibrationProvider = calibration::offsetFor,
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
        if (armed || gestureInFlight) return false
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
        calibration.setPair(null)
        approvedAppPair = null
        dispatching = false
        thumbRunning = false
        menuPaused = false
        overlay?.hide()
        overlay = null
        downPoint = null
        handler.removeCallbacks(contentRefresh)
        handler.removeCallbacks(readyTimeout)
        handler.removeCallbacks(activeTimeout)
        setCapture(false)
        statusText = "OFF: $message"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!armed) return
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (!handler.hasCallbacks(contentRefresh)) handler.post(contentRefresh)
        } else {
            checkActiveWindows()
        }
    }

    private fun checkActiveWindows() {
        if (!armed) return
        if (!environmentAllowsInput()) {
            stop("잠금·화면 꺼짐·동의 철회·터치 탐색 상태로 입력 중지")
            return
        }
        if (dispatching) {
            val assessment = inspectTestWindows()
            if (!assessment.allowed || assessment.pair != activeWindowPair) {
                val issue = if (assessment.allowed) SafetyIssue.PAIR_CHANGED else assessment.issue
                stop(WindowSafetyPolicy.message(issue))
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
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (interceptedVolumeDown == event.downTime) {
            if (event.action == KeyEvent.ACTION_UP) interceptedVolumeDown = null
            return true
        }
        if (!armed || event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        interceptedVolumeDown = event.downTime
        stop("볼륨 아래 긴급 중지")
        return true
    }

    override fun onMotionEvent(event: MotionEvent) {
        if (!armed || !capturing || dispatching) return
        if (event.pointerCount != 1) {
            stop("여러 손가락 입력은 지원하지 않습니다.")
            return
        }
        val point = TapPoint(event.rawX, event.rawY)
        if (!point.x.isFinite() || !point.y.isFinite()) {
            stop("잘못된 터치 좌표로 중단")
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (mode == MirrorMode.THUMB && overlay?.isControlAt(point.x, point.y) == true) {
                    downPoint = null
                    setCapture(false)
                    overlay?.openMenuFromCapturedTouch()
                    return
                }
                val pair = activeWindowPair
                if (pair == null || listOf(pair.left, pair.right).none {
                        it.bounds.contains(point.x, point.y, safeTapInset)
                    }) {
                    stop("분할 앱의 안전 입력 영역 밖에서 터치해 중지했습니다.")
                    return
                }
                downPoint = point
                downTime = event.eventTime
            }
            MotionEvent.ACTION_MOVE -> {
                val down = downPoint ?: return
                if (movedBeyondTap(down, point)) stop("스크롤 또는 드래그를 감지해 중지했습니다.")
            }
            MotionEvent.ACTION_UP -> {
                val down = downPoint ?: return
                downPoint = null
                if (event.eventTime - downTime > TAP_MAX_MS || movedBeyondTap(down, point)) {
                    stop("길게 누르기 또는 이동 입력을 감지해 중지했습니다.")
                    return
                }
                handleTap(down, point)
            }
            MotionEvent.ACTION_CANCEL -> stop("원본 터치 취소 감지")
        }
    }

    private fun movedBeyondTap(start: TapPoint, current: TapPoint): Boolean =
        abs(start.x - current.x) > touchSlop || abs(start.y - current.y) > touchSlop

    private fun handleTap(down: TapPoint, touch: TapPoint) {
        val inspection = inspectWindows()
        val pair = inspection.assessment.pair
        if (!inspection.assessment.allowed || pair == null || pair != activeWindowPair) {
            val issue = if (inspection.assessment.allowed) SafetyIssue.PAIR_CHANGED else inspection.assessment.issue
            stop(WindowSafetyPolicy.message(issue))
            return
        }

        val side = if (mode == MirrorMode.THUMB) sourceSide else TouchSide.LEFT
        val source = pair.source(side)
        val target = pair.target(side)
        val inset = safeTapInset
        val original = when {
            source.bounds.contains(down.x, down.y, inset) && source.bounds.contains(touch.x, touch.y, inset) ->
                WindowTap(source, touch)
            target.bounds.contains(down.x, down.y, inset) && target.bounds.contains(touch.x, touch.y, inset) ->
                WindowTap(target, touch)
            else -> {
                stop("터치가 같은 창의 안전 입력 영역 안에 있지 않아 중지했습니다.")
                return
            }
        }
        val token = dispatchGeneration.current()
        if (!canDispatch(token)) {
            stop("실행 시간이 지났거나 입력 조건이 바뀌어 중지했습니다.")
            return
        }
        if (!inspection.canReach(original)) {
            stop(WindowSafetyPolicy.message(SafetyIssue.TAP_OBSTRUCTED))
            return
        }

        if (original.window == target) {
            replayGesture(original, null, token)
            return
        }
        val mapped = CoordinateMapper.map(source.bounds, target.bounds, touch.x, touch.y,
            inset, calibration.offsetFor(pair, side))
        val replica = mapped?.let { WindowTap(target, it) }
        val blockedReason = when {
            replica == null -> "영점 보정 뒤 자동 터치가 앱의 안전 입력 영역을 벗어났습니다."
            !inspection.canReach(replica) -> WindowSafetyPolicy.message(SafetyIssue.TAP_OBSTRUCTED)
            else -> null
        }
        if (blockedReason != null) {
            replayGesture(original, null, token, blockedReason)
            return
        }

        val checkedReplica = requireNotNull(replica)
        if (mode == MirrorMode.NODE_CLICK) {
            setCapture(false)
            val first = inspection.windowFor(original)?.getRoot(0)
            val directClicked = first != null && canDispatch(token) &&
                clickNodeAt(first, touch.x.toInt(), touch.y.toInt())
            val targetWindow = if (directClicked) checkedTarget(checkedReplica, token) else null
            val second = targetWindow?.getRoot(0)
            val (x, y) = checkedReplica.point
            if (second == null || !canDispatch(token) || !clickNodeAt(second, x.toInt(), y.toInt())) {
                stop("양쪽 노드 클릭을 완료할 수 없어 중지했습니다.")
            } else {
                refreshCapture()
            }
            return
        }
        replayGesture(original, checkedReplica, token)
    }

    private fun canDispatch(token: Long): Boolean =
        dispatchGeneration.valid(token) && armed && !gestureInFlight &&
            SystemClock.uptimeMillis() < inputDeadline && environmentAllowsInput() &&
            (mode != MirrorMode.THUMB ||
                (thumbRunning && !menuPaused && overlay?.isCalibrating != true))

    private fun checkedTarget(tap: WindowTap, token: Long): AccessibilityWindowInfo? {
        if (!canDispatch(token)) return null
        val latest = inspectWindows()
        if (!latest.assessment.allowed || latest.assessment.pair != activeWindowPair || !latest.canReach(tap)) {
            return null
        }
        return latest.windowFor(tap)
    }

    // The initial points were validated on this call stack. After the Android callback, validate
    // the destination again before submitting another gesture.
    private fun replayGesture(
        direct: WindowTap,
        replica: WindowTap?,
        token: Long,
        blockedReason: String? = null,
    ) {
        setCapture(false)
        dispatching = true
        if (replica != null && mode == MirrorMode.DUAL_STROKE) {
            dispatchPoints(listOf(direct.point, replica.point), token) { success ->
                completeDispatch(success, token)
            }
        } else {
            dispatchPoints(listOf(direct.point), token) { success ->
                when {
                    !success -> completeDispatch(false, token)
                    blockedReason != null -> stop(blockedReason)
                    replica == null -> completeDispatch(true, token)
                    checkedTarget(replica, token) == null ->
                        stop("원래 탭을 보낸 후 창 또는 입력 상태가 바뀌어 자동 탭을 생략했습니다.")
                    else -> dispatchPoints(listOf(replica.point), token) {
                        completeDispatch(it, token)
                    }
                }
            }
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
            description.addStroke(GestureDescription.StrokeDescription(path, 0L, STROKE_MS))
        }
        gestureInFlight = true
        val accepted = dispatchGesture(
            description.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureInFlight = false
                    if (dispatchGeneration.valid(token)) result(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureInFlight = false
                    if (dispatchGeneration.valid(token)) result(false)
                }
            },
            handler,
        )
        if (!accepted) {
            gestureInFlight = false
            result(false)
        }
    }

    private fun completeDispatch(success: Boolean, token: Long) {
        if (!dispatchGeneration.valid(token)) return
        dispatching = false
        if (!success) {
            stop("Android에서 터치 전송을 취소했거나 전송할 수 없어 중지했습니다.")
        } else {
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
                calibration.setPair(null)
                downPoint = null
                setCapture(false)
                overlay?.cancelCalibration()
                statusText = "Thumb 대기: " + WindowSafetyPolicy.message(assessment.issue)
                return
            }

            if (activeStarted && activeWindowPair != pair) {
                if (thumbRunning) {
                    stop("분할 창 위치·크기 변경으로 터치 입력 중지")
                    return
                }
                overlay?.cancelCalibration()
                overlay?.updateSourceSide(pair)
            }
            if (!activeStarted) overlay?.updateSourceSide(pair)
            activeStarted = true
            activeWindowPair = pair
            calibration.setPair(pair)
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
            startInputTimeout()
        }
        setCapture(true)
        statusText = "고급 실험 ACTIVE (최대 20초) · 볼륨 아래 긴급 중지"
    }

    private data class ReadWindow(val native: AccessibilityWindowInfo, val metadata: ObservedWindow)

    private inner class WindowInspection(
        val assessment: SafetyAssessment,
        private val windows: List<ReadWindow>,
        private val metadata: List<ObservedWindow>,
    ) {
        fun windowFor(tap: WindowTap): AccessibilityWindowInfo? = windows.singleOrNull {
            it.metadata.layer == WindowLayer.APPLICATION &&
                it.metadata.windowId == tap.window.windowId &&
                it.metadata.displayId == tap.window.displayId &&
                it.metadata.packageName == tap.window.packageName &&
                it.metadata.bounds == tap.window.bounds
        }?.native

        fun canReach(tap: WindowTap): Boolean {
            val (x, y) = tap.point
            if (overlay?.isControlAt(x, y) == true ||
                !WindowSafetyPolicy.isTapUnobstructed(tap, metadata)
            ) return false
            val target = windowFor(tap) ?: return false
            val region = Region()
            target.getRegionInScreen(region)
            return region.contains(x.toInt(), y.toInt())
        }
    }

    private fun splitWorkArea(): SplitWorkArea {
        val metrics = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).maximumWindowMetrics
        val bounds = metrics.bounds
        val insets = metrics.windowInsets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        return SplitWorkArea(
            ScreenRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
            ScreenRect(bounds.left + insets.left, bounds.top + insets.top,
                bounds.right - insets.right, bounds.bottom - insets.bottom),
            (24 * resources.displayMetrics.density).toInt(),
        )
    }

    private fun observeWindows(): List<ReadWindow> {
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
            val root = window.getRoot(0)
            val input = if (layer == WindowLayer.APPLICATION) {
                inspectInputTree(
                    root, INPUT_NODE_LIMIT,
                    isSensitive = { it.isPassword || (it.isEditable && it.isFocused) },
                    children = { node ->
                        (0 until node.childCount).asSequence().map { node.getChild(it, 0) }.iterator()
                    },
                )
            } else InputInspection.CLEAR
            ReadWindow(window, ObservedWindow(
                layer = layer,
                packageName = root?.packageName?.toString(),
                bounds = ScreenRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                displayId = window.displayId,
                focused = window.isFocused,
                windowId = window.id,
                inputInspection = input,
                zOrder = window.layer,
                pictureInPicture = window.isInPictureInPictureMode,
            ))
        }
    }

    private fun environmentAllowsInput(): Boolean =
        AppearanceSettings(this).accessibilityDisclosureAccepted &&
            getSystemService(PowerManager::class.java)?.isInteractive == true &&
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false &&
            getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == false

    private fun inspectWindows(): WindowInspection {
        if (mode == MirrorMode.THUMB && approvedAppPair == null) {
            return WindowInspection(SafetyAssessment(null, SafetyIssue.UNAPPROVED_PAIR), emptyList(), emptyList())
        }
        val snapshots = observeWindows()
        val visible = snapshots.map { it.metadata }
        val workArea = splitWorkArea()
        val assessment = if (mode == MirrorMode.THUMB) {
            AppPairPolicy.verify(visible, packageName, workArea, approvedAppPair)
        } else {
            WindowSafetyPolicy.inspect(visible, packageName, workArea)
        }
        return WindowInspection(assessment, snapshots, visible)
    }

    private fun inspectTestWindows(): SafetyAssessment =
        inspectWindows().assessment

    private fun resolveTestWindows(): WindowPair? = inspectTestWindows().pair

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

