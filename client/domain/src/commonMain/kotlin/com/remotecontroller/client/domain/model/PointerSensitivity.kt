package com.remotecontroller.client.domain.model

/**
 * Pointer speed stored as DPI. 800 matches the original pad, where one finger
 * pixel moved the cursor by two pixels. Each setting step is 100 DPI.
 */
object PointerSensitivity {
    const val DEFAULT_DPI = 800
    const val STEP_DPI = 100
    const val MIN_DPI = 100
    const val MAX_DPI = 4_000
    private const val BASELINE_SCALE = 2f

    fun coerce(dpi: Int): Int = dpi.coerceIn(MIN_DPI, MAX_DPI)

    fun scale(dpi: Int): Float = BASELINE_SCALE * coerce(dpi) / DEFAULT_DPI
}
