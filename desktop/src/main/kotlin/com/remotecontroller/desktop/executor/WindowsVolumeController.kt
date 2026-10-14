package com.remotecontroller.desktop.executor

class WindowsVolumeController : VolumeController {

    override suspend fun readState(): VolumeState? {
        val (level, muted) = WindowsCoreAudio.readState() ?: return null
        return VolumeState(level, muted)
    }

    override suspend fun setVolumeLevel(level: Int) {
        if (!WindowsCoreAudio.setVolumePercent(level)) {
            println("[WindowsCoreAudio] failed to set volume to $level%")
        }
    }

    override suspend fun volumeUp(step: Int) {
        val current = readState()?.level ?: return
        setVolumeLevel((current + step).coerceAtMost(100))
    }

    override suspend fun volumeDown(step: Int) {
        val current = readState()?.level ?: return
        setVolumeLevel((current - step).coerceAtLeast(0))
    }

    override suspend fun toggleMute() {
        val muted = readState()?.muted ?: return
        if (!WindowsCoreAudio.setMuted(!muted)) {
            println("[WindowsCoreAudio] failed to toggle mute")
        }
    }
}
