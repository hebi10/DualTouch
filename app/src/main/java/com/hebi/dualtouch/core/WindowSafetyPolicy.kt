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

data class ObservedWindow(
    val layer: WindowLayer,
    val packageName: String?,
    val bounds: ScreenRect,
    val displayId: Int = 0,
    val focused: Boolean = false,
    val windowId: Int = -1,
    val sensitiveInput: Boolean = false,
)

enum class SafetyIssue {
    NONE,
    MISSING_PAIR,
    EXTRA_APPLICATION_WINDOWS,
    NOT_SPLIT_LAYOUT,
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
}

data class SafetyAssessment(
    val pair: WindowPair?,
    val issue: SafetyIssue,
) {
    val allowed: Boolean get() = issue == SafetyIssue.NONE && pair != null
}

object WindowSafetyPolicy {
    private val allowedPackages = setOf(TestPackages.LEFT, TestPackages.RIGHT)

    fun inspect(
        windows: List<ObservedWindow>,
        controllerPackage: String,
    ): SafetyAssessment {
        val applications = windows.filter { it.layer == WindowLayer.APPLICATION }
        if (applications.size != 2 ||
            applications.any { it.packageName !in allowedPackages }
        ) {
            return SafetyAssessment(null, SafetyIssue.OTHER_APPLICATION)
        }

        val candidates = applications.map {
            WindowSnapshot(
                packageName = it.packageName ?: return SafetyAssessment(
                    null, SafetyIssue.OTHER_APPLICATION
                ),
                bounds = it.bounds,
                displayId = it.displayId,
            )
        }
        val displays = applications.map { it.displayId }.toSet()
        if (displays.size != 1) return SafetyAssessment(null, SafetyIssue.DIFFERENT_DISPLAY)
        if (displays.single() != 0) {
            return SafetyAssessment(null, SafetyIssue.UNSUPPORTED_DISPLAY)
        }

        val pair = WindowPairResolver.resolve(candidates)
            ?: return SafetyAssessment(null, SafetyIssue.MISSING_PAIR)

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

    fun message(issue: SafetyIssue): String = when (issue) {
        SafetyIssue.NONE -> "두 테스트 창을 확인했습니다."
        SafetyIssue.MISSING_PAIR -> "앱 창 두 개를 확인할 수 없습니다. Android 분할 화면에 일반 앱 두 개를 열어 주세요."
        SafetyIssue.EXTRA_APPLICATION_WINDOWS -> "추가 앱 창이 감지되었습니다. 팝업 창을 닫고 분할 앱 두 개만 남겨 주세요."
        SafetyIssue.NOT_SPLIT_LAYOUT -> "분할 배치를 확인할 수 없습니다. 분할선을 중앙에 맞추고 다시 검색해 주세요."
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
        SafetyIssue.SENSITIVE_INPUT -> "입력 필드·비밀번호 등이 감지되어 복제를 중단합니다."
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

