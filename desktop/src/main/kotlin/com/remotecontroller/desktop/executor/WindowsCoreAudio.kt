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

/**
 * Reads and sets the default playback device master volume via Windows Core Audio (WASAPI).
 */
internal object WindowsCoreAudio {

    private val CLSID_MMDeviceEnumerator = CLSID("BCDE0395-E52F-467C-8E3D-C4579291692E")
    private val IID_IMMDeviceEnumerator = IID("A95664D2-9614-4F35-A746-DE8DB63617E6")
    private val IID_IAudioEndpointVolume = IID("5CDF2C82-841E-4546-9722-0CF74078229A")

    private const val E_RENDER = 0
    private const val E_MULTIMEDIA = 1
    private val pointerSize = DWORDLONG.SIZE

    private val deviceEnumeratorRef = PointerByReference()
    private val deviceRef = PointerByReference()
    private val endpointVolumeRef = PointerByReference()

    private var endpointVolumePointer: Pointer? = null
    private var getMasterVolumeLevelScalar: Function? = null
    private var setMasterVolumeLevelScalar: Function? = null
    private var getMuteFunction: Function? = null
    private var setMuteFunction: Function? = null

    init {
        initialize()
    }

    fun readVolumePercent(): Int? {
        val fn = getMasterVolumeLevelScalar ?: return null
        val endpoint = endpointVolumePointer ?: return null

        val scalar = FloatByReference()
        val hr = fn.invoke(
            WinNT.HRESULT::class.java,
            arrayOf(endpoint, scalar),
        ) as WinNT.HRESULT

        if (!WinNT.S_OK.equals(hr)) return null
        return (scalar.value * 100f).roundToInt().coerceIn(0, 100)
    }

    fun readMuted(): Boolean? {
        val fn = getMuteFunction ?: return null
        val endpoint = endpointVolumePointer ?: return null

        val muted = BOOLByReference()
        val hr = fn.invoke(
            WinNT.HRESULT::class.java,
            arrayOf(endpoint, muted),
        ) as WinNT.HRESULT

        if (!WinNT.S_OK.equals(hr)) return null
        return muted.value.booleanValue()
    }

    fun setVolumePercent(level: Int) {
        val fn = setMasterVolumeLevelScalar ?: return
        val endpoint = endpointVolumePointer ?: return

        val scalar = level.coerceIn(0, 100) / 100f
        fn.invoke(
            WinNT.HRESULT::class.java,
            arrayOf(endpoint, scalar, null),
        )
    }

    fun setMuted(muted: Boolean) {
        val fn = setMuteFunction ?: return
        val endpoint = endpointVolumePointer ?: return

        fn.invoke(
            WinNT.HRESULT::class.java,
            arrayOf(endpoint, BOOL(muted), null),
        )
    }

    private fun initialize() {
        val initResult = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_MULTITHREADED)
        if (initResult.toInt() < 0) {
            return
        }

        if (!WinNT.S_OK.equals(
                Ole32.INSTANCE.CoCreateInstance(
                    CLSID_MMDeviceEnumerator,
                    null,
                    CLSCTX_INPROC_SERVER,
                    IID_IMMDeviceEnumerator,
                    deviceEnumeratorRef,
                ),
            )
        ) {
            return
        }

        val enumeratorPointer = deviceEnumeratorRef.value ?: return
        val getDefaultAudioEndpoint = vtableFunction(enumeratorPointer, 4)

        if (!WinNT.S_OK.equals(
                getDefaultAudioEndpoint.invoke(
                    WinNT.HRESULT::class.java,
                    arrayOf(enumeratorPointer, E_RENDER, E_MULTIMEDIA, deviceRef),
                ) as WinNT.HRESULT,
            )
        ) {
            return
        }

        val devicePointer = deviceRef.value ?: return
        val activate = vtableFunction(devicePointer, 3)

        if (!WinNT.S_OK.equals(
                activate.invoke(
                    WinNT.HRESULT::class.java,
                    arrayOf(devicePointer, IID_IAudioEndpointVolume, CLSCTX_INPROC_SERVER, null, endpointVolumeRef),
                ) as WinNT.HRESULT,
            )
        ) {
            return
        }

        val endpointPointer = endpointVolumeRef.value ?: return
        endpointVolumePointer = endpointPointer
        getMasterVolumeLevelScalar = vtableFunction(endpointPointer, 9)
        setMasterVolumeLevelScalar = vtableFunction(endpointPointer, 7)
        getMuteFunction = vtableFunction(endpointPointer, 15)
        setMuteFunction = vtableFunction(endpointPointer, 14)
    }

    private fun vtableFunction(comPointer: Pointer, index: Int): Function {
        val vtable = comPointer.getPointer(0)
        return Function.getFunction(
            vtable.getPointer((index * pointerSize).toLong()),
            Function.ALT_CONVENTION,
        )
    }
}
