package com.hebi.dualtouch.core

import org.junit.Assert.*
import org.junit.Test

class ThumbUiStateTest {
    private fun state(connected: Boolean = true, running: Boolean = true,
                      processing: Boolean = false, paused: Boolean = false,
                      calibrating: Boolean = false, issue: SafetyIssue? = null,
                      now: Long = 1000L) =
        ThumbUiState.resolve(connected, running, processing, paused, calibrating, issue, 21000L, now)

    @Test fun countdownUsesDeadlineAndNeverDisplaysNegativeTime() {
        assertEquals(20, ThumbUiState.remainingSeconds(21000L, 1000L))
        assertEquals(1, ThumbUiState.remainingSeconds(21000L, 20999L))
        assertEquals(0, ThumbUiState.remainingSeconds(21000L, 21000L))
        assertEquals(0, ThumbUiState.remainingSeconds(21000L, 22000L))
    }

    @Test fun distinguishesConnectionAndRunningWithoutMisleadingAtExpiry() {
        assertEquals("연결", state(connected = false, running = false).label)
        assertEquals("대기", state(running = false).label)
        assertEquals("20초", state().label)
        assertEquals("대기", state(now = 21000L).label)
    }

    @Test fun indicatesPausedProcessingCalibrationAndSafetyError() {
        assertTrue(state(paused = true).description.contains("일시 중지"))
        assertTrue(state(processing = true).description.contains("터치 처리"))
        assertTrue(state(running = false, processing = true).description.contains("종료 대기"))
        assertEquals("보정", state(calibrating = true, running = false).label)
        assertEquals("오류", state(running = false, issue = SafetyIssue.PAIR_CHANGED).label)
    }

    @Test fun explicitZeroCalibrationIsStillSavedOnlyForCurrentPairAndDirection() {
        val pair = WindowPair(
            WindowSnapshot("a", ScreenRect(0, 0, 100, 100), windowId = 1),
            WindowSnapshot("b", ScreenRect(110, 0, 210, 100), windowId = 2),
        )
        val calibration = PairCalibration()
        calibration.setPair(pair)
        assertFalse(calibration.hasSaved(pair, TouchSide.LEFT))
        assertTrue(calibration.save(pair, TouchSide.LEFT, CalibrationOffset.ZERO))
        assertTrue(calibration.hasSaved(pair, TouchSide.LEFT))
        assertFalse(calibration.hasSaved(pair, TouchSide.RIGHT))
        calibration.reset(pair, TouchSide.LEFT)
        assertFalse(calibration.hasSaved(pair, TouchSide.LEFT))
        calibration.save(pair, TouchSide.LEFT, CalibrationOffset.ZERO)
        calibration.setPair(null)
        assertFalse(calibration.hasSaved(pair, TouchSide.LEFT))
    }
    @Test fun homeGuidanceDoesNotSuggestStartingWhenRunningOrBlocked() {
        fun guidance(phase: InteractionPhase?, state: ThumbUiState?) =
            homeNextStep(true, true, true, phase, state)
        assertTrue(guidance(InteractionPhase.ACTIVE, state()).contains("중지"))
        assertTrue(guidance(InteractionPhase.PAUSED, state(paused = true)).contains("일시 중지"))
        assertTrue(guidance(InteractionPhase.READY, state(running = false, issue = SafetyIssue.PAIR_CHANGED)).contains("중지 사유"))
        assertTrue(guidance(InteractionPhase.PAUSED, state(calibrating = true, running = false)).contains("저장하거나 취소"))
        assertTrue(guidance(InteractionPhase.READY, state(running = false, processing = true)).contains("끝날 때까지"))
    }

    @Test fun homeGuidancePrioritizesPermissionAndRealConnection() {
        assertEquals("접근성 권한을 설정해 주세요.",
            homeNextStep(false, true, true, InteractionPhase.ACTIVE, state()))
        assertEquals("플로팅 표시를 눌러 Thumb를 열어 주세요.",
            homeNextStep(true, false, false, InteractionPhase.OFF, null))
        assertEquals("두 앱을 분할한 뒤 Thumb에서 연결하세요.",
            homeNextStep(true, true, false, InteractionPhase.WAITING, state(connected = false, running = false)))
        assertEquals("Thumb에서 실행 또는 영점 조절을 선택하세요.",
            homeNextStep(true, true, true, InteractionPhase.READY, state(running = false)))
    }
}
