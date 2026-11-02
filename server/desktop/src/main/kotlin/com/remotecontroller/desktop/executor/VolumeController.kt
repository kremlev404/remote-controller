package com.remotecontroller.desktop.executor

data class VolumeState(
    val level: Int,
    val muted: Boolean,
)

interface VolumeController {
    suspend fun readState(): VolumeState?
    suspend fun setVolumeLevel(level: Int)
    suspend fun volumeUp(step: Int = 5)
    suspend fun volumeDown(step: Int = 5)
    suspend fun toggleMute()
}
