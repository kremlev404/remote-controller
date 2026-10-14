package com.remotecontroller.desktop.executor

interface VolumeController {
    fun getVolumeLevel(): Int
    fun isMuted(): Boolean
    fun setVolumeLevel(level: Int)
    fun volumeUp(step: Int = 5)
    fun volumeDown(step: Int = 5)
    fun toggleMute()
}
