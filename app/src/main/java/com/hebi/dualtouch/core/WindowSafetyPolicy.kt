package com.hebi.dualtouch.core

import kotlin.math.max
import kotlin.math.min

enum class WindowLayer {
    APPLICATION,
    INPUT_METHOD,
    SYSTEM,
    ACCESSIBILITY,
    SPLIT_DIVIDER,
    WINDOW_CONTROL,
    OTHER,
}

// Application identity and accessibility overlay ownership are the only
// package identities consumed by the window safety policies. All layers still
// participate in geometry, obstruction and layer checks.
internal fun WindowLayer.requiresPackageIdentity(): Boolean =
    this == WindowLayer.APPLICATION || this == WindowLayer.ACCESSIBILITY

data class ObservedWindow(
    val layer: WindowLayer,
    val packageName: String?,
    val bounds: ScreenRect,
    val displayId: Int = 0,
    val focused: Boolean = false,
    val windowId: Int = -1,
    val inputInspection: InputInspection = InputInspection.CLEAR,
    val zOrder: Int = 0,
    val pictureInPicture: Boolean = false,
)

enum class SafetyIssue {
    NONE,
    MISSING_PAIR,
    EXTRA_APPLICATION_WINDOWS,
    NOT_SPLIT_LAYOUT,
    SPLIT_DIVIDER_MISMATCH,
    PICTURE_IN_PICTURE,
    OTHER_APPLICATION,
    DIFFERENT_DISPLAY,
    UNSUPPORTED_DISPLAY,
    NO_FOCUSED_TARGET,
    KEYBOARD,
    ACCESSIBILITY_OVERLAY,
    COVERED_BY_SYSTEM,
    BLOCKING_WINDOW_CONTROL,
    UNKNOWN_WINDOW,
    UNAPPROVED_PAIR,
    PAIR_CHANGED,
    PROTECTED_APP,
    SENSITIVE_INPUT,
    INPUT_INSPECTION_INCOMPLETE,
    INPUT_INSPECTION_LIMIT,
    TAP_OBSTRUCTED,
    INPUT_FINISHING,
    INPUT_RECOVERY_REQUIRED,
    CAPTURE_UNAVAILABLE,
    INPUT_INSPECTION_TIMEOUT,
}

data class WindowTap(val window: WindowSnapshot, val point: TapPoint)

data class SafetyAssessment(
    val pair: WindowPair?,
    val issue: SafetyIssue,
) {
    val allowed: Boolean get() = issue == SafetyIssue.NONE && pair != null
}

object WindowSafetyPolicy {
    // A completed tree traversal does not authorize input after the complete
    // window-read budget has elapsed. Preserve actual unsafe/incomplete
    // reasons rather than labeling them as retryable timeout failures.
    internal fun enforceReadDeadline(assessment: SafetyAssessment, expired: Boolean): SafetyAssessment =
        if (expired && assessment.allowed) SafetyAssessment(null, SafetyIssue.INPUT_INSPECTION_TIMEOUT)
        else assessment

    private val allowedPackages = setOf(TestPackages.LEFT, TestPackages.RIGHT)

    fun inspect(
        windows: List<ObservedWindow>,
        controllerPackage: String,
        workArea: SplitWorkArea,
    ): SafetyAssessment {
        val applications = windows.filter { it.layer == WindowLayer.APPLICATION }
        if (applications.size != 2 ||
            applications.any { it.packageName !in allowedPackages }
        ) {
            return SafetyAssessment(null, SafetyIssue.OTHER_APPLICATION)
        }

        if (applications.any { it.pictureInPicture }) {
            return SafetyAssessment(null, SafetyIssue.PICTURE_IN_PICTURE)
        }
        inputIssue(applications)?.let { return SafetyAssessment(null, it) }

        val candidates = applications.map {
            WindowSnapshot(
                packageName = it.packageName ?: return SafetyAssessment(
                    null, SafetyIssue.OTHER_APPLICATION
                ),
                bounds = it.bounds,
                displayId = it.displayId,
                windowId = it.windowId,
            )
        }
        val displays = applications.map { it.displayId }.toSet()
        if (displays.size != 1) return SafetyAssessment(null, SafetyIssue.DIFFERENT_DISPLAY)
        if (displays.single() != 0) {
            return SafetyAssessment(null, SafetyIssue.UNSUPPORTED_DISPLAY)
        }

        val dividers = windows.filter { it.layer == WindowLayer.SPLIT_DIVIDER && it.displayId == 0 }
        if (dividers.size > 1) return SafetyAssessment(null, SafetyIssue.SPLIT_DIVIDER_MISMATCH)
        val pair = WindowPairResolver.resolve(candidates, dividers.singleOrNull()?.bounds, workArea)
            ?: return SafetyAssessment(null, if (dividers.isEmpty()) {
                SafetyIssue.NOT_SPLIT_LAYOUT
            } else {
                SafetyIssue.SPLIT_DIVIDER_MISMATCH
            })

        if (applications.none { it.focused }) {
            return SafetyAssessment(null, SafetyIssue.NO_FOCUSED_TARGET)
        }
        if (windows.any { it.layer == WindowLayer.INPUT_METHOD }) {
            return SafetyAssessment(null, SafetyIssue.KEYBOARD)
        }
        if (windows.any {
                it.layer == WindowLayer.ACCESSIBILITY &&
                    it.packageName != controllerPackage
            }
        ) {
            return SafetyAssessment(null, SafetyIssue.ACCESSIBILITY_OVERLAY)
        }
        if (windows.any { it.layer == WindowLayer.OTHER || it.layer == WindowLayer.WINDOW_CONTROL }) {
            return SafetyAssessment(null, SafetyIssue.UNKNOWN_WINDOW)
        }
        if (windows.any { window ->
                window.layer == WindowLayer.SYSTEM &&
                    (coversWindow(window.bounds, pair.left.bounds) ||
                        coversWindow(window.bounds, pair.right.bounds))
            }
        ) {
            return SafetyAssessment(null, SafetyIssue.COVERED_BY_SYSTEM)
        }
        return SafetyAssessment(pair, SafetyIssue.NONE)
    }

    private fun coversWindow(overlay: ScreenRect, target: ScreenRect): Boolean {
        if (!overlay.isValid || !target.isValid) return false
        val width = (min(overlay.right, target.right) -
            max(overlay.left, target.left)).coerceAtLeast(0)
        val height = (min(overlay.bottom, target.bottom) -
            max(overlay.top, target.top)).coerceAtLeast(0)
        return width.toLong() * height >=
            target.width.toLong() * target.height / 5
    }

    fun inputIssue(applications: List<ObservedWindow>): SafetyIssue? = when {
        applications.any { it.inputInspection == InputInspection.SENSITIVE } -> SafetyIssue.SENSITIVE_INPUT
        applications.any { it.inputInspection == InputInspection.LIMIT_REACHED } -> SafetyIssue.INPUT_INSPECTION_LIMIT
        applications.any { it.inputInspection == InputInspection.TIME_BUDGET_EXCEEDED } -> SafetyIssue.INPUT_INSPECTION_TIMEOUT
        applications.any { it.inputInspection == InputInspection.UNAVAILABLE } -> SafetyIssue.INPUT_INSPECTION_INCOMPLETE
        else -> null
    }

    fun isTapUnobstructed(tap: WindowTap, windows: List<ObservedWindow>): Boolean {
        val target = windows.singleOrNull {
            it.layer == WindowLayer.APPLICATION && it.windowId == tap.window.windowId &&
                it.displayId == tap.window.displayId && it.packageName == tap.window.packageName &&
                it.bounds == tap.window.bounds
        } ?: return false
        val (x, y) = tap.point
        return target.bounds.contains(x, y) && windows.none {
            it !== target && it.displayId == target.displayId && it.zOrder >= target.zOrder &&
                (!it.bounds.isValid || it.bounds.contains(x, y))
        }
    }

    fun message(issue: SafetyIssue): String = when (issue) {
        SafetyIssue.NONE -> "두 테스트 창을 확인했습니다."
        SafetyIssue.MISSING_PAIR -> "앱 창 두 개를 확인할 수 없습니다. Android 분할 화면에 일반 앱 두 개를 열어 주세요."
        SafetyIssue.EXTRA_APPLICATION_WINDOWS -> "추가 앱 창이 감지되었습니다. 팝업 창을 닫고 분할 앱 두 개만 남겨 주세요."
        SafetyIssue.NOT_SPLIT_LAYOUT -> "시스템 분할선이 없고 두 창이 화면을 채우지 않습니다. 팝업 대신 Android 분할 화면으로 열어 주세요."
        SafetyIssue.SPLIT_DIVIDER_MISMATCH -> "Android가 보고한 분할선이 두 앱 사이에 없거나 여러 개입니다. 분할 화면을 다시 설정해 주세요."
        SafetyIssue.PICTURE_IN_PICTURE -> "작은 동영상 창(PiP)을 닫고 다시 검색해 주세요."
        SafetyIssue.OTHER_APPLICATION -> "Test Left와 Test Right 두 창만 열어주세요."
        SafetyIssue.DIFFERENT_DISPLAY -> "두 앱이 같은 디스플레이에 있어야 합니다."
        SafetyIssue.UNSUPPORTED_DISPLAY -> "기본 디스플레이의 좌우 분할 화면만 지원합니다."
        SafetyIssue.NO_FOCUSED_TARGET -> "대상 창에 포커스가 없어 입력을 중지합니다."
        SafetyIssue.KEYBOARD -> "키보드 입력 중에는 터치 복제를 중지합니다."
        SafetyIssue.ACCESSIBILITY_OVERLAY -> "다른 접근성 오버레이가 있어 중지합니다."
        SafetyIssue.COVERED_BY_SYSTEM -> "시스템 팝업이 표시되어 입력을 중지합니다."
        SafetyIssue.BLOCKING_WINDOW_CONTROL -> "창 조절 버튼이 앱 영역을 덮고 있습니다. 닫은 뒤 다시 시도해 주세요."
        SafetyIssue.UNKNOWN_WINDOW -> "알 수 없는 창이 감지되어 입력을 중지합니다."
        SafetyIssue.UNAPPROVED_PAIR -> "먼저 분할 화면의 앱 두 개를 직접 연결해 주세요."
        SafetyIssue.PAIR_CHANGED -> "연결한 앱 창이 변경되어 다시 선택해야 합니다."
        SafetyIssue.PROTECTED_APP -> "시스템·보안·금융 앱은 연결할 수 없습니다."
        SafetyIssue.SENSITIVE_INPUT -> "선택한 창에서 비밀번호나 활성 입력 칸이 감지되어 중지합니다."
        SafetyIssue.INPUT_INSPECTION_INCOMPLETE -> "앱 화면 일부를 확인할 수 없습니다. 화면이 열린 뒤 다시 검색해 주세요."
        SafetyIssue.INPUT_INSPECTION_LIMIT -> "이 화면에는 확인할 항목이 너무 많습니다. 간단한 화면에서 다시 검색해 주세요."
        SafetyIssue.TAP_OBSTRUCTED -> "터치 위치가 다른 창에 가려졌거나 앱의 입력 영역을 벗어났습니다."
        SafetyIssue.INPUT_FINISHING -> "Android가 이전 터치를 처리 중입니다. 잠시 뒤 다시 실행해 주세요."
        SafetyIssue.INPUT_RECOVERY_REQUIRED -> "이전 터치의 종료를 확인하지 못했습니다. 시스템 접근성 서비스를 껐다 켜고 다시 연결해 주세요."
        SafetyIssue.CAPTURE_UNAVAILABLE -> "접근성 입력을 준비할 수 없습니다. 서비스 권한·화면 잠금·다른 접근성 기능을 확인해 주세요."
        SafetyIssue.INPUT_INSPECTION_TIMEOUT -> "화면 구조 검사 시간이 초과되었습니다. 안전을 위해 입력을 중지합니다."
    }
}

enum class InputInspection { CLEAR, SENSITIVE, UNAVAILABLE, LIMIT_REACHED, TIME_BUDGET_EXCEEDED }

internal const val MAX_INPUT_NODES_PER_WINDOW = 512

internal fun <Node : Any> inspectInputTree(
    root: Node?,
    limit: Int,
    isSensitive: (Node) -> Boolean,
    shouldContinue: () -> Boolean = { true },
    children: (Node) -> Iterator<Node?>,
): InputInspection {
    if (root == null) return InputInspection.UNAVAILABLE
    return try {
        val stack = ArrayDeque<Iterator<Node?>>()
        stack.addLast(listOf(root).iterator())
        var inspected = 0
        var missingChild = false
        while (stack.isNotEmpty()) {
            if (!shouldContinue()) return InputInspection.TIME_BUDGET_EXCEEDED
            val branch = stack.last()
            if (!branch.hasNext()) {
                stack.removeLast()
                continue
            }
            if (inspected >= limit) return InputInspection.LIMIT_REACHED
            inspected++
            val node = branch.next()
            if (node == null) {
                missingChild = true
            } else {
                if (isSensitive(node)) return InputInspection.SENSITIVE
                stack.addLast(children(node))
            }
        }
        if (!shouldContinue()) InputInspection.TIME_BUDGET_EXCEEDED
        else if (missingChild) InputInspection.UNAVAILABLE else InputInspection.CLEAR
    } catch (_: RuntimeException) {
        InputInspection.UNAVAILABLE
    }
}

class DispatchGeneration {
    private var number = 0L

    fun current(): Long = number

    fun invalidate(): Long {
        number += 1
        return number
    }

    fun valid(token: Long): Boolean = token == number
}

