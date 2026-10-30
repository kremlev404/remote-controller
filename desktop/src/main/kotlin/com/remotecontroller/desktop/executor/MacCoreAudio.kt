package com.remotecontroller.desktop.executor

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Default output volume through Core Audio.
 *
 * The native calls live in libmacvolume. Calls run on [Dispatchers.IO]. A mutex keeps each read or
 * write on one caller at a time, so phone connections do not need their own thread.
 */
internal object MacCoreAudio {

    private val gate = Mutex()
    private val audioTimeout = 2.seconds
    private val nativeLoaded = loadNative()

    suspend fun readState(): Pair<Int, Boolean>? = onAudio {
        val scalar = nativeReadVolume()
        if (scalar.isNaN()) return@onAudio null
        (scalar * 100f).roundToInt().coerceIn(0, 100) to (nativeReadMuted() == 1)
    }

    suspend fun setVolumePercent(level: Int): Boolean = onAudio {
        nativeSetVolume(level.coerceIn(0, 100) / 100f)
    } ?: false

    suspend fun setMuted(muted: Boolean): Boolean = onAudio {
        nativeSetMuted(muted)
    } ?: false

    @JvmStatic
    private external fun nativeReadVolume(): Float

    @JvmStatic
    private external fun nativeReadMuted(): Int

    @JvmStatic
    private external fun nativeSetVolume(scalar: Float): Boolean

    @JvmStatic
    private external fun nativeSetMuted(muted: Boolean): Boolean

    private fun loadNative(): Boolean = runCatching {
        val resource = MacCoreAudio::class.java.getResourceAsStream("/natives/libmacvolume.dylib")
            ?: error("libmacvolume.dylib is not on the classpath")
        val library = Files.createTempDirectory("remote-controller-volume").resolve("libmacvolume.dylib")
        resource.use { input ->
            Files.copy(input, library, StandardCopyOption.REPLACE_EXISTING)
        }
        library.toFile().deleteOnExit()
        System.load(library.toAbsolutePath().toString())
    }.onFailure { error ->
        println("[MacVolume] Core Audio library was not loaded: ${error.message}")
    }.isSuccess

    private suspend fun <T> onAudio(block: () -> T): T? {
        if (!nativeLoaded) return null
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(audioTimeout) {
                gate.withLock { block() }
            }
        }
    }
}
