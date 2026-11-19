package com.hebi.dualtouch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.hebi.dualtouch.core.CalibrationOffset
import com.hebi.dualtouch.core.CoordinateMapper
import com.hebi.dualtouch.core.ScreenRect
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
    private val activeTimeout = Runnable { stop("20초 실험 시간이 끝났습니다.") }

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
    private var thumbRunning = false
    private var menuPaused = false
    private val calibrationPrefs by lazy { getSharedPreferences("thumb_zero_v1", MODE_PRIVATE) }

    private fun calibrationKey(side: TouchSide): String =
        if (side == TouchSide.LEFT) "left" else "right"

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

    private fun setThumbRunning(enabled: Boolean) {
        if (mode != MirrorMode.THUMB || !armed || !activeStarted) return
        val pair = resolveTestWindows()
        if (pair == null || pair != activeWindowPair) {
            stop("분할 창 변경으로 중단")
            return
        }
        thumbRunning = enabled
        handler.removeCallbacks(activeTimeout)
        handler.removeCallbacks(readyTimeout)
        if (enabled) {
            handler.postDelayed(activeTimeout, ACTIVE_MS)
            statusText = "실행 중: ${if (sourceSide == TouchSide.LEFT) "좌 → 우" else "우 → 좌"} / 20초 자동 중지"
        } else {
            handler.postDelayed(readyTimeout, READY_WAIT_MS)
            statusText = "중지: Thumb에서 실행 또는 방향·영점 조절 선택"
        }
        refreshCapture()
    }

    private fun toggleThumbRunning() {
        setThumbRunning(!thumbRunning)
    }

    private fun toggleTouchSide() {
        if (mode != MirrorMode.THUMB || !armed) return
        setThumbRunning(false)
        sourceSide = if (sourceSide == TouchSide.LEFT) TouchSide.RIGHT else TouchSide.LEFT
        calibrationPrefs.edit().putString("source_side", sourceSide.name).apply()
        statusText = if (sourceSide == TouchSide.LEFT) {
            "기준: 왼쪽 직접 터치 → 오른쪽 자동 터치"
        } else {
            "기준: 오른쪽 직접 터치 → 왼쪽 자동 터치"
        }
    }

    private fun prepareCalibration() {
        setThumbRunning(false)
        setCapture(false)
    }

    private fun setMenuVisible(visible: Boolean) {
        menuPaused = visible
        if (visible) {
            downPoint = null
            setCapture(false)
        } else if (armed) {
            refreshCapture()
        }
    }

    var statusText: String = "서비스 대기"
        private set

    val isArmed: Boolean get() = armed

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
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
        if (armed) return false
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
        armed = false
        activeStarted = false
        activeWindowPair = null
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
        if (armed && !dispatching) refreshCapture()
    }

    override fun onInterrupt() {
        stop("서비스가 중단되었습니다.")
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
        if (event.pointerCount != 1) {
            stop("여러 손가락 입력은 지원하지 않습니다.")
            return
        }

        val point = TapPoint(event.rawX, event.rawY)
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
        val pair = resolveTestWindows() ?: run {
            stop("대상 테스트 창이 사라졌습니다.")
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

    private fun replayGesture(points: List<TapPoint>, sequential: Boolean) {
        setCapture(false)
        dispatching = true
        if (sequential && points.size == 2) {
            dispatchPoints(listOf(points[0])) { firstSuccess ->
                if (!firstSuccess || !armed) {
                    completeDispatch(firstSuccess)
                } else {
                    dispatchPoints(listOf(points[1])) { completeDispatch(it) }
                }
            }
        } else {
            dispatchPoints(points) { completeDispatch(it) }
        }
    }

    private fun dispatchPoints(points: List<TapPoint>, result: (Boolean) -> Unit) {
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
                    result(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    result(false)
                }
            },
            handler,
        )
        if (!accepted) result(false)
    }

    private fun completeDispatch(success: Boolean) {
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
        val pair = resolveTestWindows()
        if (pair == null) {
            if (activeStarted) {
                stop("화면 분할 또는 대상 앱 변경")
            } else {
                setCapture(false)
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
            if (mode == MirrorMode.THUMB) {
                handler.postDelayed(readyTimeout, READY_WAIT_MS)
            } else {
                handler.postDelayed(activeTimeout, ACTIVE_MS)
            }
        }

        if (mode == MirrorMode.THUMB) {
            if (overlay == null) {
                setCapture(false)
                overlay = ThumbOverlayController(
                    context = this,
                    pairProvider = ::resolveTestWindows,
                    sideProvider = { sourceSide },
                    runningProvider = { thumbRunning },
                    calibrationProvider = { currentCalibration() },
                    onRunToggle = ::toggleThumbRunning,
                    onSideToggle = ::toggleTouchSide,
                    onCalibrationStart = ::prepareCalibration,
                    onCalibrationSaved = ::saveCalibration,
                    onCalibrationReset = ::resetCalibration,
                    onMenuVisibilityChanged = ::setMenuVisible,
                )
                if (overlay?.show(pair) != true) {
                    stop("플로팅 Thumb 표시 실패")
                    return
                }
            }
            setCapture(thumbRunning && !menuPaused && overlay?.isCalibrating != true)
            statusText = when {
                overlay?.isCalibrating == true -> "영점 조절 중: 빨강은 두 점 이동, 흰색은 단독 이동"
                thumbRunning -> "실행 중: ${if (sourceSide == TouchSide.LEFT) "좌 → 우" else "우 → 좌"} · 볼륨 아래 중지"
                else -> "Thumb를 눌러 실행·중지 / 좌·우 선택 / 영점 조절"
            }
        } else {
            setCapture(true)
            statusText = "ACTIVE (최대 20초): 원탭만. 긴급 중지는 볼륨 아래."
        }
    }

    private fun resolveTestWindows(): com.hebi.dualtouch.core.WindowPair? {
        val snapshots = windows.mapNotNull { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@mapNotNull null
            val root = window.root ?: return@mapNotNull null
            val packageName = root.packageName?.toString() ?: return@mapNotNull null
            if (packageName != TestPackages.LEFT && packageName != TestPackages.RIGHT) {
                return@mapNotNull null
            }
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            WindowSnapshot(
                packageName,
                ScreenRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
            )
        }
        return WindowPairResolver.resolve(snapshots)
    }

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
        current = null
        super.onDestroy()
    }
}
