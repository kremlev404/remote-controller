package com.remotecontroller.desktop.executor

class MacVolumeController : VolumeController {

    override suspend fun readState(): VolumeState? {
        val (level, muted) = MacCoreAudio.readState() ?: return null
        return VolumeState(level, muted)
    }

    override suspend fun setVolumeLevel(level: Int) {
        if (!MacCoreAudio.setVolumePercent(level)) {
            println("[MacVolume] failed to set volume to $level%")
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
        if (!MacCoreAudio.setMuted(!muted)) {
            println("[MacVolume] failed to toggle mute")
        }
    }
}
