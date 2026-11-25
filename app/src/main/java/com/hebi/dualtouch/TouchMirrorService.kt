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
import com.hebi.dualtouch.core.GestureFlight
import com.hebi.dualtouch.core.TapStart
import com.hebi.dualtouch.core.ThumbUiState
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
import com.hebi.dualtouch.core.requiresPackageIdentity

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
        private const val INSPECTION_BUDGET_MS = 500L
        private const val CONTENT_REFRESH_MS = 32L
        private const val GESTURE_CALLBACK_TIMEOUT_MS = 2_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val contentRefresh = Runnable { checkActiveWindows() }
    private val readyTimeout = Runnable { stop("테스트 창 준비 시간이 초과되었습니다.") }
    private val activeTimeout = Runnable {
        if (mode == MirrorMode.THUMB) {
            thumbRunning = false
            dispatchGeneration.invalidate()
            dispatching = false
            inputDeadline = 0L
            if (setCapture(false)) statusText = "20초 자동 중지 · Thumb는 표시 중"
            tapStart = null
        } else {
            stop("20초 실험 시간이 끝났습니다.")
        }
    }

    private var armed = false
    private var capturing = false
    private var dispatching = false
    private val gestureFlight = GestureFlight()
    private var gestureWatchdog: Runnable? = null
    private var captureFaulted = false
    private var captureStartedAt = 0L
    private var lastThumbIssue: SafetyIssue? = null
    private var inputDeadline = 0L
    private var interceptedVolumeDown: Long? = null
    private var activeStarted = false
    private var mode = MirrorMode.DUAL_STROKE
    private var tapStart: TapStart? = null
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
        if (captureFaulted) return SafetyIssue.CAPTURE_UNAVAILABLE
        if (enabled) {
            if (gestureFlight.pending) return if (gestureFlight.expired) SafetyIssue.INPUT_RECOVERY_REQUIRED else SafetyIssue.INPUT_FINISHING
            if (!environmentAllowsInput()) return SafetyIssue.CAPTURE_UNAVAILABLE
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
            inputDeadline = 0L
            if (!setCapture(false)) return SafetyIssue.CAPTURE_UNAVAILABLE
            tapStart = null
            statusText = "입력 중지 · Thumb는 계속 표시"
        }
        return SafetyIssue.NONE
    }

    private fun requestThumbRunning(enabled: Boolean): SafetyIssue {
        // A stale menu action must never toggle or extend a running session.
        if (enabled == thumbRunning) return SafetyIssue.NONE
        val issue = setThumbRunning(enabled)
        lastThumbIssue = issue.takeUnless { it == SafetyIssue.NONE }
        return issue
    }

    private fun candidateAppPair(): SafetyAssessment {
        val environment = readWindowEnvironment()
            ?: return SafetyAssessment(null, SafetyIssue.INPUT_INSPECTION_INCOMPLETE)
        return AppPairPolicy.select(environment.first.map { it.metadata }, packageName, environment.second)
    }

    private fun connectVisibleApps(side: TouchSide, displayedPair: AppPairApproval): SafetyIssue {
        if (!armed || mode != MirrorMode.THUMB) return SafetyIssue.UNAPPROVED_PAIR
        if (captureFaulted || !environmentAllowsInput()) return SafetyIssue.CAPTURE_UNAVAILABLE
        if (gestureFlight.pending) return if (gestureFlight.expired) SafetyIssue.INPUT_RECOVERY_REQUIRED else SafetyIssue.INPUT_FINISHING
        val environment = readWindowEnvironment()
            ?: return SafetyIssue.INPUT_INSPECTION_INCOMPLETE
        val assessment = AppPairPolicy.verify(
            environment.first.map { it.metadata }, packageName, environment.second, displayedPair,
        )
        val pair = assessment.pair
        if (!assessment.allowed || pair == null) {
            lastThumbIssue = assessment.issue
            statusText = "연결 불가: " + WindowSafetyPolicy.message(assessment.issue)
            return assessment.issue
        }
        if (!armed || captureFaulted || !environmentAllowsInput()) return SafetyIssue.CAPTURE_UNAVAILABLE
        dispatchGeneration.invalidate()
        dispatching = false
        thumbRunning = false
        tapStart = null
        if (!setCapture(false)) return SafetyIssue.CAPTURE_UNAVAILABLE
        inputDeadline = 0L
        handler.removeCallbacks(activeTimeout)
        approvedAppPair = displayedPair
        lastThumbIssue = null
        activeWindowPair = pair
        calibration.setPair(pair)
        activeStarted = true
        sourceSide = side
        calibrationPrefs.edit().putString("source_side", side.name).apply()
        val arrangement = if (pair.orientation == SplitOrientation.HORIZONTAL) "좌·우" else "상·하"
        statusText = "$arrangement 연결 완료: ${pair.left.packageName} / ${pair.right.packageName}. Thumb에서 실행하세요."
        return SafetyIssue.NONE
    }

    private fun prepareCalibration(): SafetyIssue {
        if (!armed || mode != MirrorMode.THUMB) return SafetyIssue.UNAPPROVED_PAIR
        if (captureFaulted || !environmentAllowsInput()) return SafetyIssue.CAPTURE_UNAVAILABLE
        val stopped = setThumbRunning(false)
        if (stopped != SafetyIssue.NONE) return stopped
        if (!setCapture(false)) return SafetyIssue.CAPTURE_UNAVAILABLE
        if (gestureFlight.pending) return if (gestureFlight.expired)
            SafetyIssue.INPUT_RECOVERY_REQUIRED else SafetyIssue.INPUT_FINISHING
        return SafetyIssue.NONE
    }

    private fun setMenuVisible(visible: Boolean) {
        menuPaused = visible
        if (visible) {
            if (dispatching || gestureFlight.pending) {
                thumbRunning = false
                inputDeadline = 0L
                handler.removeCallbacks(activeTimeout)
                statusText = "터치 처리 중 메뉴를 열어 실행을 중지했습니다. 다시 실행해 주세요."
            }
            dispatchGeneration.invalidate()
            dispatching = false
            tapStart = null
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

    internal val connectedPair: WindowPair?
        get() = activeWindowPair?.takeIf { isFloatingVisible && approvedAppPair?.matches(it) == true }

    internal val connectedSourceSide: TouchSide get() = sourceSide
    internal val displayedThumbState: ThumbUiState get() = thumbUiState()

    private fun thumbUiState(): ThumbUiState = ThumbUiState.resolve(
        connected = connectedPair != null,
        running = thumbRunning,
        processing = dispatching || gestureFlight.pending,
        paused = menuPaused || !capturing,
        calibrating = overlay?.isCalibrating == true,
        issue = lastThumbIssue,
        deadline = inputDeadline,
        now = SystemClock.uptimeMillis(),
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
        if (captureFaulted) return false
        if (!AppearanceSettings(this).accessibilityDisclosureAccepted) {
            statusText = "접근성 사용 고지 확인과 명시적 동의가 필요합니다."
            return false
        }
        if (isFloatingVisible) return true
        if (armed) stop("이전 기술 실험 종료")
        dispatchGeneration.invalidate()
        mode = MirrorMode.THUMB
        lastThumbIssue = null
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
        tapStart = null
        if (!setCapture(false)) return false
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
            stateProvider = ::thumbUiState,
            calibrationProvider = calibration::offsetFor,
            calibrationSavedProvider = calibration::hasSaved,
            onRunRequest = ::requestThumbRunning,
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
        clearGestureWatchdog()
        gestureFlight.reset()
        captureFaulted = false
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
        if (captureFaulted) return
        try {
            val info = checkNotNull(serviceInfo)
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            info.setMotionEventSources(0)
            serviceInfo = info
            capturing = false
            captureStartedAt = 0L
            statusText = "접근성 서비스 연결됨. 기본 OFF."
        } catch (_: RuntimeException) {
            failCapture()
        }
    }

    fun startExperiment(selectedMode: MirrorMode): Boolean {
        if (!AppearanceSettings(this).accessibilityDisclosureAccepted) {
            statusText = "접근성 사용 고지 확인과 명시적 동의가 필요합니다."
            return false
        }
        if (selectedMode == MirrorMode.THUMB) return showFloatingControl()
        if (armed || gestureFlight.pending || captureFaulted) return false
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
        tapStart = null
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
        tapStart = null
        handler.removeCallbacks(contentRefresh)
        handler.removeCallbacks(readyTimeout)
        handler.removeCallbacks(activeTimeout)
        inputDeadline = 0L
        if (setCapture(false)) statusText = "OFF: $message"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!armed) return
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Our display-only countdown must not trigger repeated app-tree inspections.
            if (event.packageName?.toString() == packageName) return
            if (!handler.hasCallbacks(contentRefresh)) handler.postDelayed(contentRefresh, CONTENT_REFRESH_MS)
        } else {
            handler.removeCallbacks(contentRefresh)
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
        clearGestureWatchdog()
        gestureFlight.reset()
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
        if (!armed || !capturing || dispatching || gestureFlight.pending) return
        if (SystemClock.uptimeMillis() >= inputDeadline) {
            stop("입력 시간이 만료되어 중지했습니다.")
            return
        }
        if (event.pointerCount != 1 || !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            stop("단일 손가락 터치만 지원합니다.")
            return
        }
        val point = TapPoint(event.rawX, event.rawY)
        if (!point.x.isFinite() || !point.y.isFinite()) {
            stop("잘못된 터치 좌표로 중단")
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (tapStart != null || event.downTime < captureStartedAt || event.eventTime < event.downTime) {
                    stop("이전 입력과 겹치는 터치로 중지했습니다. 다시 실행해 주세요.")
                    return
                }
                if (mode == MirrorMode.THUMB && overlay?.isControlAt(point.x, point.y) == true) {
                    tapStart = null
                    if (setCapture(false)) overlay?.openMenuFromCapturedTouch()
                    return
                }
                val pair = activeWindowPair
                if (pair == null || listOf(pair.left, pair.right).none {
                        it.bounds.contains(point.x, point.y, safeTapInset)
                    }) {
                    stop("분할 앱의 안전 입력 영역 밖에서 터치해 중지했습니다.")
                    return
                }
                tapStart = TapStart(point, event.downTime, event.eventTime)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val start = tapStart
                if (start == null || !start.accepts(point, event.downTime, event.eventTime, touchSlop, TAP_MAX_MS)) {
                    stop("불완전한 터치·스크롤·길게 누르기 또는 연속 입력을 감지해 중지했습니다.")
                    return
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    tapStart = null
                    handleTap(start.point, point)
                }
            }
            MotionEvent.ACTION_CANCEL -> stop("원본 터치 취소 감지")
            else -> stop("지원하지 않는 터치 입력으로 중지했습니다.")
        }
    }

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
            if (!setCapture(false)) return
            val first = checkedTarget(original, token)?.getRoot(0)
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
        dispatchGeneration.valid(token) && armed && !gestureFlight.pending && !captureFaulted &&
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

    // Recheck the target after capture changes and again after the first Android callback.
    private fun replayGesture(
        direct: WindowTap,
        replica: WindowTap?,
        token: Long,
        blockedReason: String? = null,
    ) {
        if (!setCapture(false)) return
        dispatching = true
        val latest = inspectWindows()
        if (!canDispatch(token) || !latest.assessment.allowed || latest.assessment.pair != activeWindowPair || !latest.canReach(direct)) {
            stop("원래 탭을 전송하기 전 창 또는 입력 상태가 바뀌어 중지했습니다.")
            return
        }
        val verifiedReplica = replica?.takeIf { latest.canReach(it) }
        val finalBlockedReason = blockedReason ?: if (replica != null && verifiedReplica == null) {
            WindowSafetyPolicy.message(SafetyIssue.TAP_OBSTRUCTED)
        } else null
        if (verifiedReplica != null && mode == MirrorMode.DUAL_STROKE) {
            dispatchPoints(listOf(direct.point, verifiedReplica.point), token) { success ->
                completeDispatch(success, token)
            }
        } else {
            dispatchPoints(listOf(direct.point), token) { success ->
                when {
                    !success -> completeDispatch(false, token)
                    finalBlockedReason != null -> stop(finalBlockedReason)
                    verifiedReplica == null -> completeDispatch(true, token)
                    checkedTarget(verifiedReplica, token) == null ->
                        stop("원래 탭을 보낸 후 창 또는 입력 상태가 바뀌어 자동 탭을 생략했습니다.")
                    else -> dispatchPoints(listOf(verifiedReplica.point), token) {
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
        if (capturing || !canDispatch(token)) {
            result(false)
            return
        }
        val description = GestureDescription.Builder()
        points.forEach { point ->
            val path = Path().apply { moveTo(point.x, point.y) }
            description.addStroke(GestureDescription.StrokeDescription(path, 0L, STROKE_MS))
        }
        val flightId = gestureFlight.begin() ?: run {
            result(false)
            return
        }
        fun finish(success: Boolean) {
            if (!gestureFlight.complete(flightId)) return
            clearGestureWatchdog()
            if (dispatchGeneration.valid(token)) result(success)
            // A stopped session never schedules its remaining tap or restarts capture here.
        }
        val watchdog = Runnable {
            if (gestureFlight.expire(flightId)) {
                stop("Android 터치 완료 응답이 없습니다. 접근성 서비스를 껐다 켠 뒤 다시 실행해 주세요.")
            }
        }
        gestureWatchdog = watchdog
        handler.postDelayed(watchdog, GESTURE_CALLBACK_TIMEOUT_MS)
        val accepted = try {
            dispatchGesture(
                description.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) = finish(true)
                    override fun onCancelled(gestureDescription: GestureDescription?) = finish(false)
                },
                handler,
            )
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted) finish(false)
    }

    private fun clearGestureWatchdog() {
        gestureWatchdog?.let { handler.removeCallbacks(it) }
        gestureWatchdog = null
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
        if (gestureFlight.pending) {
            setCapture(false)
            return
        }
        if ((thumbRunning || (mode != MirrorMode.THUMB && activeStarted)) && SystemClock.uptimeMillis() >= inputDeadline) {
            stop("20초 실행 시간이 만료되었습니다.")
            return
        }
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
                    inputDeadline = 0L
                }
                if (approvedAppPair != null &&
                    assessment.issue != SafetyIssue.NONE &&
                    assessment.issue != SafetyIssue.UNAPPROVED_PAIR
                ) {
                    lastThumbIssue = assessment.issue
                    approvedAppPair = null
                }
                activeStarted = false
                activeWindowPair = null
                calibration.setPair(null)
                tapStart = null
                if (!setCapture(false)) return
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
            if (!setCapture(thumbRunning && !menuPaused && overlay?.isCalibrating != true)) return
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
        if (!setCapture(true)) return
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
            return try {
                target.getRegionInScreen(region)
                region.contains(x.toInt(), y.toInt())
            } catch (_: RuntimeException) {
                false
            }
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

    // Numeric timing only, restricted to debuggable emulator builds. No
    // packages, window identifiers, bounds, text, nodes, or touch data.
    private val inspectionDiagnosticsEnabled by lazy {
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            android.os.Build.HARDWARE in setOf("ranchu", "goldfish")
    }
    private var lastInspectionTimingReportAt = -1_000L

    private enum class InspectionPhase { WINDOWS, APP_ROOT, OVERLAY_ROOT, TREE, WORK_AREA }

    private class InspectionTiming {
        private val elapsed = LongArray(InspectionPhase.values().size)
        fun add(phase: InspectionPhase, duration: Long) {
            elapsed[phase.ordinal] += duration
        }
        fun get(phase: InspectionPhase): Long = elapsed[phase.ordinal]
    }

    private inline fun <T> InspectionTiming?.measure(phase: InspectionPhase, block: () -> T): T {
        if (this == null) return block()
        val start = SystemClock.uptimeMillis()
        return try { block() } finally { add(phase, SystemClock.uptimeMillis() - start) }
    }

    private fun readWindowEnvironment(): Pair<List<ReadWindow>, SplitWorkArea>? {
        val started = SystemClock.uptimeMillis()
        val deadline = started + INSPECTION_BUDGET_MS
        val timing = if (inspectionDiagnosticsEnabled) InspectionTiming() else null
        var outcome = "exception"
        return try {
            val snapshot = observeWindows(deadline, timing)
            val workArea = timing.measure(InspectionPhase.WORK_AREA) { splitWorkArea() }
            val treeTimedOut = snapshot.any { it.metadata.inputInspection == InputInspection.TIME_BUDGET_EXCEEDED }
            val overBudget = SystemClock.uptimeMillis() >= deadline
            outcome = if (treeTimedOut || overBudget) "timeout" else "complete"
            if (overBudget && !treeTimedOut) null else snapshot to workArea
        } catch (_: RuntimeException) {
            null
        } finally {
            if (timing != null) {
                val now = SystemClock.uptimeMillis()
                val total = now - started
                if ((total >= INSPECTION_BUDGET_MS || outcome == "timeout") &&
                    now - lastInspectionTimingReportAt >= 250L) {
                    lastInspectionTimingReportAt = now
                    val windowsMs = timing.get(InspectionPhase.WINDOWS)
                    val appRootMs = timing.get(InspectionPhase.APP_ROOT)
                    val overlayRootMs = timing.get(InspectionPhase.OVERLAY_ROOT)
                    val treeMs = timing.get(InspectionPhase.TREE)
                    val workAreaMs = timing.get(InspectionPhase.WORK_AREA)
                    val otherMs = (total - windowsMs - appRootMs - overlayRootMs - treeMs - workAreaMs).coerceAtLeast(0L)
                    android.util.Log.i("DualTouchInspection",
                        "inspection total_ms=$total windows_ms=$windowsMs app_root_ms=$appRootMs overlay_root_ms=$overlayRootMs tree_ms=$treeMs work_area_ms=$workAreaMs other_ms=$otherMs outcome=$outcome")
                }
            }
        }
    }

    private fun observeWindows(deadline: Long, timing: InspectionTiming?): List<ReadWindow> {
        return timing.measure(InspectionPhase.WINDOWS) { windows }.map { window ->
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
            // Avoid IPC for package identities never used by either policy.
            // Keep every window in the geometry/obstruction snapshot.
            val budgetAvailable = SystemClock.uptimeMillis() < deadline
            val root = if (layer.requiresPackageIdentity() && budgetAvailable) {
                timing.measure(if (layer == WindowLayer.APPLICATION) InspectionPhase.APP_ROOT else InspectionPhase.OVERLAY_ROOT) {
                    window.getRoot(
                        if (layer == WindowLayer.APPLICATION)
                            AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID
                        else 0
                    )
                }
            } else null
            val input = if (layer == WindowLayer.APPLICATION) {
                if (!budgetAvailable || SystemClock.uptimeMillis() >= deadline) {
                    InputInspection.TIME_BUDGET_EXCEEDED
                } else {
                    // Prefetch reduces individual remote node lookups. Still inspect
                    // every reachable node within the same limit and deadline.
                    timing.measure(InspectionPhase.TREE) {
                        inspectInputTree(
                            root, INPUT_NODE_LIMIT,
                            isSensitive = { it.isPassword || (it.isEditable && it.isFocused) },
                            shouldContinue = { SystemClock.uptimeMillis() < deadline },
                            children = { node ->
                                (0 until node.childCount).asSequence().map {
                                    node.getChild(it, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
                                }.iterator()
                            },
                        )
                    }
                }
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
        val environment = readWindowEnvironment()
            ?: return WindowInspection(SafetyAssessment(null, SafetyIssue.INPUT_INSPECTION_INCOMPLETE), emptyList(), emptyList())
        val (snapshots, workArea) = environment
        val visible = snapshots.map { it.metadata }
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

    private fun setCapture(enabled: Boolean): Boolean {
        if (captureFaulted) return false
        if (capturing == enabled) return true
        if (enabled && (gestureFlight.pending || !armed)) return false
        return try {
            val info = checkNotNull(serviceInfo)
            info.setMotionEventSources(if (enabled) InputDevice.SOURCE_TOUCHSCREEN else 0)
            serviceInfo = info
            capturing = enabled
            tapStart = null
            captureStartedAt = if (enabled) SystemClock.uptimeMillis() else 0L
            true
        } catch (_: RuntimeException) {
            failCapture()
            false
        }
    }

    private fun failCapture() {
        captureFaulted = true
        capturing = false
        stop("접근성 입력 상태를 변경할 수 없습니다.")
        statusText = "OFF: 접근성 입력 오류. 시스템 설정에서 서비스를 다시 활성화해 주세요."
        // If the framework still owns touch capture, disabling the service releases it.
        try { disableSelf() } catch (_: RuntimeException) { /* User can disable it in Settings. */ }
    }

    override fun onDestroy() {
        stop("서비스 해제")
        clearGestureWatchdog()
        gestureFlight.reset()
        removeScreenOffReceiver()
        current = null
        super.onDestroy()
    }
}

