package com.hebi.dualtouch.core

import kotlin.math.abs

internal data class TapStart(
    val point: TapPoint,
    val downTime: Long,
    val eventTime: Long,
) {
    fun accepts(current: TapPoint, currentDownTime: Long, currentEventTime: Long,
                touchSlop: Int, maxDurationMs: Long): Boolean =
        currentDownTime == downTime && currentEventTime >= eventTime &&
            currentEventTime - downTime <= maxDurationMs &&
            abs(current.x - point.x) <= touchSlop && abs(current.y - point.y) <= touchSlop
}

/** A stopped logical session does not release an Android gesture still in flight. */
internal class GestureFlight {
    private var nextId = 0L
    private var currentId: Long? = null
    var expired = false
        private set
    val pending: Boolean get() = currentId != null

    fun begin(): Long? {
        if (pending) return null
        expired = false
        nextId += 1
        currentId = nextId
        return nextId
    }

    fun complete(id: Long): Boolean {
        if (id != currentId) return false
        currentId = null
        expired = false
        return true
    }

    fun expire(id: Long): Boolean {
        if (id != currentId || expired) return false
        expired = true
        return true
    }

    fun reset() {
        currentId = null
        expired = false
    }
}
