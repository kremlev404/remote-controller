package com.remotecontroller.desktop.executor

import com.sun.jna.Function
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid.CLSID
import com.sun.jna.platform.win32.Guid.IID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinDef.BOOL
import com.sun.jna.platform.win32.WinDef.BOOLByReference
import com.sun.jna.platform.win32.WinDef.DWORDLONG
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WTypes.CLSCTX_INPROC_SERVER
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.PointerByReference
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads and sets the default playback device master volume via Windows Core Audio (WASAPI).
 *
 * Calls run on [Dispatchers.IO] after that thread joins the multi-threaded COM apartment.
 * A mutex keeps the cached endpoint on one caller at a time, so phone connections do not
 * need their own thread or an executor.
 */
internal object WindowsCoreAudio {

    private val CLSID_MMDeviceEnumerator = CLSID("BCDE0395-E52F-467C-8E3D-C4579291692E")
    private val IID_IMMDeviceEnumerator = IID("A95664D2-9614-4F35-A746-DE8DB63617E6")
    private val IID_IAudioEndpointVolume = IID("5CDF2C82-841E-4546-9722-0CF74078229A")

    private const val E_RENDER = 0
    private const val E_MULTIMEDIA = 1
    private val pointerSize = DWORDLONG.SIZE
    private val gate = Mutex()

    private var enumeratorPointer: Pointer? = null
    private var endpointPointer: Pointer? = null
    private var endpointId: String? = null
    private var getMasterVolumeLevelScalar: Function? = null
    private var setMasterVolumeLevelScalar: Function? = null
    private var getMuteFunction: Function? = null
    private var setMuteFunction: Function? = null

    suspend fun readState(): Pair<Int, Boolean>? = onAudio {
        val endpoint = currentEndpoint() ?: return@onAudio null
        val volumeFn = getMasterVolumeLevelScalar ?: return@onAudio null
        val muteFn = getMuteFunction ?: return@onAudio null
        val scalar = FloatByReference()
        if (!succeeded(volumeFn.invoke(WinNT.HRESULT::class.java, arrayOf(endpoint, scalar)))) {
            invalidateEndpoint()
            return@onAudio null
        }
        val muted = BOOLByReference()
        if (!succeeded(muteFn.invoke(WinNT.HRESULT::class.java, arrayOf(endpoint, muted)))) {
            invalidateEndpoint()
            return@onAudio null
        }
        (scalar.value * 100f).roundToInt().coerceIn(0, 100) to muted.value.booleanValue()
    }

    suspend fun setVolumePercent(level: Int): Boolean = onAudio {
        val endpoint = currentEndpoint() ?: return@onAudio false
        val fn = setMasterVolumeLevelScalar ?: return@onAudio false
        val scalar = level.coerceIn(0, 100) / 100f
        val hr = fn.invoke(WinNT.HRESULT::class.java, arrayOf(endpoint, scalar, Pointer.NULL))
        if (succeeded(hr)) return@onAudio true
        invalidateEndpoint()
        val retryEndpoint = currentEndpoint() ?: return@onAudio false
        val retry = setMasterVolumeLevelScalar ?: return@onAudio false
        succeeded(retry.invoke(WinNT.HRESULT::class.java, arrayOf(retryEndpoint, scalar, Pointer.NULL)))
    } ?: false

    suspend fun setMuted(muted: Boolean): Boolean = onAudio {
        val endpoint = currentEndpoint() ?: return@onAudio false
        val fn = setMuteFunction ?: return@onAudio false
        val hr = fn.invoke(WinNT.HRESULT::class.java, arrayOf(endpoint, BOOL(muted), Pointer.NULL))
        if (succeeded(hr)) return@onAudio true
        invalidateEndpoint()
        val retryEndpoint = currentEndpoint() ?: return@onAudio false
        val retry = setMuteFunction ?: return@onAudio false
        succeeded(retry.invoke(WinNT.HRESULT::class.java, arrayOf(retryEndpoint, BOOL(muted), Pointer.NULL)))
    } ?: false

    private suspend fun <T> onAudio(block: () -> T): T? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(audioTimeout) {
            gate.withLock {
                if (!joinMta()) return@withLock null
                block()
            }
        }
    }

    private fun joinMta(): Boolean {
        val hr = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_MULTITHREADED).toInt()
        if (hr < 0) {
            println("[WindowsCoreAudio] CoInitializeEx failed: 0x${hr.toUInt().toString(16)}")
            return false
        }
        return true
    }

    private fun currentEndpoint(): Pointer? {
        if (!ensureEnumerator()) return null
        val enumerator = enumeratorPointer ?: return null
        val deviceRef = PointerByReference()
        val getDefaultAudioEndpoint = vtableFunction(enumerator, 4)
        if (!succeeded(
                getDefaultAudioEndpoint.invoke(
                    WinNT.HRESULT::class.java,
                    arrayOf(enumerator, E_RENDER, E_MULTIMEDIA, deviceRef),
                ),
            )
        ) {
            return null
        }

        val devicePointer = deviceRef.value ?: return null
        val deviceId = readDeviceId(devicePointer)
        val cached = endpointPointer
        if (cached != null && (deviceId == null || deviceId == endpointId)) {
            releaseCom(devicePointer)
            return cached
        }

        val activated = activateEndpoint(devicePointer)
        releaseCom(devicePointer)
        if (activated == null) return null

        releaseCom(endpointPointer)
        endpointPointer = activated
        endpointId = deviceId
        getMasterVolumeLevelScalar = vtableFunction(activated, 9)
        setMasterVolumeLevelScalar = vtableFunction(activated, 7)
        getMuteFunction = vtableFunction(activated, 15)
        setMuteFunction = vtableFunction(activated, 14)
        return activated
    }

    private fun ensureEnumerator(): Boolean {
        enumeratorPointer?.let { return true }
        val enumeratorRef = PointerByReference()
        if (!succeeded(
                Ole32.INSTANCE.CoCreateInstance(
                    CLSID_MMDeviceEnumerator,
                    null,
                    CLSCTX_INPROC_SERVER,
                    IID_IMMDeviceEnumerator,
                    enumeratorRef,
                ),
            )
        ) {
            println("[WindowsCoreAudio] MMDeviceEnumerator was not created")
            return false
        }
        enumeratorPointer = enumeratorRef.value
        return enumeratorPointer != null
    }

    private fun activateEndpoint(devicePointer: Pointer): Pointer? {
        val endpointRef = PointerByReference()
        val activate = vtableFunction(devicePointer, 3)
        if (!succeeded(
                activate.invoke(
                    WinNT.HRESULT::class.java,
                    arrayOf(devicePointer, IID_IAudioEndpointVolume, CLSCTX_INPROC_SERVER, Pointer.NULL, endpointRef),
                ),
            )
        ) {
            return null
        }
        return endpointRef.value
    }

    private fun readDeviceId(devicePointer: Pointer): String? {
        val idRef = PointerByReference()
        val getId = vtableFunction(devicePointer, 5)
        if (!succeeded(getId.invoke(WinNT.HRESULT::class.java, arrayOf(devicePointer, idRef)))) {
            return null
        }
        val raw = idRef.value ?: return null
        return try {
            raw.getWideString(0)
        } finally {
            Ole32.INSTANCE.CoTaskMemFree(raw)
        }
    }

    private fun invalidateEndpoint() {
        releaseCom(endpointPointer)
        endpointPointer = null
        endpointId = null
        getMasterVolumeLevelScalar = null
        setMasterVolumeLevelScalar = null
        getMuteFunction = null
        setMuteFunction = null
    }

    private fun releaseCom(pointer: Pointer?) {
        if (pointer == null) return
        runCatching {
            vtableFunction(pointer, 2).invoke(Int::class.java, arrayOf(pointer))
        }
    }

    private fun succeeded(result: Any?): Boolean {
        val hr = result as? WinNT.HRESULT ?: return false
        return WinNT.S_OK.equals(hr)
    }

    private fun vtableFunction(comPointer: Pointer, index: Int): Function {
        val vtable = comPointer.getPointer(0)
        return Function.getFunction(
            vtable.getPointer((index * pointerSize).toLong()),
            Function.ALT_CONVENTION,
        )
    }

    private val audioTimeout = 2.seconds
}
