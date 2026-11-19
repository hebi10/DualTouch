package com.hebi.dualtouch.core

enum class InteractionPhase(val description: String) {
    OFF("중지"),
    WAITING("준비 중"),
    READY("실행 대기"),
    ACTIVE("실행 중"),
    PAUSED("일시 중단"),
}

object InteractionPhaseResolver {
    fun resolve(
        armed: Boolean,
        pairAvailable: Boolean,
        running: Boolean,
        captureEnabled: Boolean,
        modalOpen: Boolean,
    ): InteractionPhase = when {
        !armed -> InteractionPhase.OFF
        !pairAvailable -> InteractionPhase.WAITING
        modalOpen -> InteractionPhase.PAUSED
        running && captureEnabled -> InteractionPhase.ACTIVE
        running && !captureEnabled -> InteractionPhase.PAUSED
        else -> InteractionPhase.READY
    }
}
