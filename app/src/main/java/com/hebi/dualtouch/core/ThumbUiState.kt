package com.hebi.dualtouch.core

internal data class ThumbUiState(
    val label: String,
    val description: String,
    val canRetry: Boolean = false,
) {
    companion object {
        fun remainingSeconds(deadline: Long, now: Long): Int {
            if (deadline <= now) return 0
            return ((deadline - now + 999L) / 1000L).coerceIn(0L, 20L).toInt()
        }

        fun resolve(connected: Boolean, running: Boolean, processing: Boolean,
                    paused: Boolean, calibrating: Boolean, issue: SafetyIssue?,
                    deadline: Long, now: Long): ThumbUiState {
            val seconds = remainingSeconds(deadline, now)
            return when {
                calibrating -> ThumbUiState("보정", "영점 조절 중 · 자동 입력 중지")
                processing && !running -> ThumbUiState("대기", "이전 Android 터치 처리 종료 대기")
                !running && connected && issue?.canRetrySamePair() == true ->
                    ThumbUiState("대기", retryDescription(issue), canRetry = true)
                !running && issue != null && issue != SafetyIssue.NONE ->
                    ThumbUiState("오류", WindowSafetyPolicy.message(issue))
                !connected -> ThumbUiState("연결", "분할 앱 연결 필요")
                !running || seconds == 0 -> ThumbUiState("대기", "연결됨 · 실행 대기")
                processing -> ThumbUiState("${seconds}초", "터치 처리 중 · 남은 실행 시간 ${seconds}초")
                paused -> ThumbUiState("${seconds}초", "입력 일시 중지 · 남은 실행 시간 ${seconds}초")
                else -> ThumbUiState("${seconds}초", "실행 중 · 남은 시간 ${seconds}초 · 볼륨 아래 긴급 중지")
            }
        }

        private fun retryDescription(issue: SafetyIssue): String {
            val guidance = when (issue) {
                SafetyIssue.KEYBOARD -> "키보드를 닫고 Thumb에서 실행하세요."
                SafetyIssue.INPUT_INSPECTION_INCOMPLETE ->
                    "앱 화면 일부를 확인할 수 없습니다. 화면이 안정되면 Thumb에서 실행하세요."
                SafetyIssue.INPUT_INSPECTION_LIMIT ->
                    "이 화면에는 확인할 항목이 너무 많습니다. 간단한 화면으로 바꾸고 Thumb에서 실행하세요."
                SafetyIssue.INPUT_INSPECTION_TIMEOUT ->
                    "화면 구조 검사 시간이 초과되었습니다. 화면이 안정되면 Thumb에서 실행하세요."
                else -> "중지 사유를 확인한 뒤 Thumb에서 실행하세요."
            }
            return "입력 일시 중지 · $guidance"
        }
    }
}

/** Presentation only: never authorizes a connection or dispatch. */
internal fun homeNextStep(permission: Boolean, floating: Boolean, connected: Boolean,
                          phase: InteractionPhase?, display: ThumbUiState?): String = when {
    !permission -> "접근성 권한을 설정해 주세요."
    !floating -> "플로팅 표시를 눌러 Thumb를 열어 주세요."
    display?.canRetry == true -> "중지 상태가 해소되면 Thumb에서 실행하세요."
    display?.label == "오류" -> "중지 사유를 확인하고 안전한 화면에서 Thumb로 다시 연결하세요."
    display?.label == "보정" -> "영점 조절을 저장하거나 취소한 뒤 실행할 수 있습니다."
    phase == InteractionPhase.ACTIVE -> "Thumb의 중지 또는 볼륨 아래 키로 중지할 수 있습니다."
    phase == InteractionPhase.PAUSED -> "입력이 일시 중지되었습니다. Thumb에서 상태를 확인하세요."
    display?.description?.contains("이전 Android 터치 처리 종료 대기") == true -> "이전 터치 처리가 끝날 때까지 기다려 주세요."
    !connected -> "두 앱을 분할한 뒤 Thumb에서 연결하세요."
    else -> "Thumb에서 실행 또는 영점 조절을 선택하세요."
}
