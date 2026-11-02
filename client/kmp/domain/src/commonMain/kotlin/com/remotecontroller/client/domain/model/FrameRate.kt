package com.remotecontroller.client.domain.model

import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Counts the frames a viewer shows. */
class FrameRate {
    private val clock = TimeSource.Monotonic
    private var windowStart = clock.markNow()
    private var frames = 0

    /** Notes one more frame. The new rate once a second has passed, null until then. */
    fun count(): Int? {
        frames++
        if (windowStart.elapsedNow() < 1.seconds) return null
        val rate = frames
        frames = 0
        windowStart = clock.markNow()
        return rate
    }
}
