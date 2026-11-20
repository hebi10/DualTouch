package com.hebi.dualtouch.core

import org.junit.Assert.assertEquals
import org.junit.Test

class InteractionPhaseTest {
    @Test fun neverRestoresActiveWhenServiceIsNotArmed() {
        assertEquals(InteractionPhase.OFF,
            InteractionPhaseResolver.resolve(false, true, true, true, false))
    }

    @Test fun waitsForTargetBeforeActivating() {
        assertEquals(InteractionPhase.WAITING,
            InteractionPhaseResolver.resolve(true, false, false, false, false))
    }

    @Test fun readyAfterTwoWindowsWithoutUserStart() {
        assertEquals(InteractionPhase.READY,
            InteractionPhaseResolver.resolve(true, true, false, false, false))
    }

    @Test fun runningOnlyWithCaptureAndNoModal() {
        assertEquals(InteractionPhase.ACTIVE,
            InteractionPhaseResolver.resolve(true, true, true, true, false))
        assertEquals(InteractionPhase.PAUSED,
            InteractionPhaseResolver.resolve(true, true, true, true, true))
        assertEquals(InteractionPhase.PAUSED,
            InteractionPhaseResolver.resolve(true, true, true, false, false))
    }
}
