package com.remotecontroller.desktop.executor

class WindowsVolumeController : VolumeController {

    override fun getVolumeLevel(): Int = WindowsCoreAudio.readVolumePercent() ?: 0

    override fun isMuted(): Boolean = WindowsCoreAudio.readMuted() ?: false

    override fun setVolumeLevel(level: Int) {
        WindowsCoreAudio.setVolumePercent(level)
    }

    override fun volumeUp(step: Int) {
        val current = getVolumeLevel()
        setVolumeLevel((current + step).coerceAtMost(100))
    }

    override fun volumeDown(step: Int) {
        val current = getVolumeLevel()
        setVolumeLevel((current - step).coerceAtLeast(0))
    }

    override fun toggleMute() {
        WindowsCoreAudio.setMuted(!isMuted())
    }
}
