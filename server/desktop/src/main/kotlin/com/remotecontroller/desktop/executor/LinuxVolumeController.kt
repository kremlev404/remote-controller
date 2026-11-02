package com.remotecontroller.desktop.executor

import com.remotecontroller.desktop.platform.LinuxCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Default output volume through whatever the desktop runs:
 * PipeWire (`wpctl`, Ubuntu 22.10+, Fedora, Mint 22, Debian 12 GNOME), PulseAudio (`pactl`),
 * or plain ALSA (`amixer`). The first tool found wins.
 */
class LinuxVolumeController : VolumeController {
    private val backend: Backend? = when {
        LinuxCommands.exists("wpctl") -> Backend.WirePlumber
        LinuxCommands.exists("pactl") -> Backend.Pulse
        LinuxCommands.exists("amixer") -> Backend.Alsa
        else -> null
    }

    override suspend fun readState(): VolumeState? = io { backend?.read() }

    override suspend fun setVolumeLevel(level: Int) {
        io { backend?.set(level.coerceIn(0, 100)) }
    }

    override suspend fun volumeUp(step: Int) {
        val current = readState()?.level ?: return
        setVolumeLevel(current + step)
    }

    override suspend fun volumeDown(step: Int) {
        val current = readState()?.level ?: return
        setVolumeLevel(current - step)
    }

    override suspend fun toggleMute() {
        io { backend?.toggleMute() }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private enum class Backend {
        WirePlumber {
            // "Volume: 0.45" or "Volume: 0.45 [MUTED]"
            override fun read(): VolumeState? {
                val output = LinuxCommands.run("wpctl", "get-volume", SINK) ?: return null
                val scalar = output.substringAfter("Volume:").trim().substringBefore(' ').toFloatOrNull() ?: return null
                return VolumeState((scalar * 100).roundToInt().coerceIn(0, 100), output.contains("[MUTED]"))
            }

            override fun set(level: Int) {
                LinuxCommands.run("wpctl", "set-volume", SINK, "%.2f".format(java.util.Locale.ROOT, level / 100f))
            }

            override fun toggleMute() {
                LinuxCommands.run("wpctl", "set-mute", SINK, "toggle")
            }
        },
        Pulse {
            // "Volume: front-left: 29491 /  45% / -20.81 dB, ..." and "Mute: yes"
            override fun read(): VolumeState? {
                val volume = LinuxCommands.run("pactl", "get-sink-volume", "@DEFAULT_SINK@") ?: return null
                val level = PERCENT.find(volume)?.groupValues?.get(1)?.toIntOrNull() ?: return null
                val muted = LinuxCommands.run("pactl", "get-sink-mute", "@DEFAULT_SINK@")?.contains("yes") == true
                return VolumeState(level.coerceIn(0, 100), muted)
            }

            override fun set(level: Int) {
                LinuxCommands.run("pactl", "set-sink-volume", "@DEFAULT_SINK@", "$level%")
            }

            override fun toggleMute() {
                LinuxCommands.run("pactl", "set-sink-mute", "@DEFAULT_SINK@", "toggle")
            }
        },
        Alsa {
            // "Front Left: Playback 39321 [60%] [-12.00dB] [on]"
            override fun read(): VolumeState? {
                val output = LinuxCommands.run("amixer", "get", "Master") ?: return null
                val level = PERCENT_BRACKETS.find(output)?.groupValues?.get(1)?.toIntOrNull() ?: return null
                return VolumeState(level.coerceIn(0, 100), output.contains("[off]"))
            }

            override fun set(level: Int) {
                LinuxCommands.run("amixer", "-q", "set", "Master", "$level%")
            }

            override fun toggleMute() {
                LinuxCommands.run("amixer", "-q", "set", "Master", "toggle")
            }
        },
        ;

        abstract fun read(): VolumeState?

        abstract fun set(level: Int)

        abstract fun toggleMute()
    }

    private companion object {
        const val SINK = "@DEFAULT_AUDIO_SINK@"
        val PERCENT = Regex("""(\d+)%""")
        val PERCENT_BRACKETS = Regex("""\[(\d+)%]""")
    }
}
