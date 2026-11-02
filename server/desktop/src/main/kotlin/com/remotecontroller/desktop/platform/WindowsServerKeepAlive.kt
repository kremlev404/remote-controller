package com.remotecontroller.desktop.platform

import com.remotecontroller.desktop.server.ServerKeepAlive
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT

/**
 * Asks Windows to keep running while the screen is locked.
 * A power request is not tied to the UI thread, so the Ktor server stays reachable
 * after the window is closed and while the session is locked.
 */
internal class WindowsServerKeepAlive : ServerKeepAlive {
    // PowerCreateRequest lives in kernel32. PowrProf does not export it.
    private val library = Native.load("kernel32", WindowsPower::class.java)
    private val reasonText = wideString(REASON)
    private val context = ReasonContext().apply { simpleReason = reasonText }
    private var request: Pointer? = null

    override fun start() {
        if (request != null) return
        val handle = library.PowerCreateRequest(context)
        if (handle == null || handle == Pointer.NULL) {
            println("[KeepAlive] PowerCreateRequest failed: ${Kernel32.INSTANCE.GetLastError()}")
            return
        }

        val kept = REQUESTS.count { type ->
            library.PowerSetRequest(handle, type) != 0
        }
        if (kept == 0) {
            Kernel32.INSTANCE.CloseHandle(WinNT.HANDLE(handle))
            println("[KeepAlive] PowerSetRequest failed: ${Kernel32.INSTANCE.GetLastError()}")
            return
        }
        request = handle
        println("[KeepAlive] server stays awake while the session is locked")
    }

    override fun stop() {
        val handle = request ?: return
        request = null
        REQUESTS.forEach { type -> library.PowerClearRequest(handle, type) }
        Kernel32.INSTANCE.CloseHandle(WinNT.HANDLE(handle))
    }

    private interface WindowsPower : com.sun.jna.Library {
        fun PowerCreateRequest(context: ReasonContext): Pointer?
        fun PowerSetRequest(powerRequest: Pointer, requestType: Int): Int
        fun PowerClearRequest(powerRequest: Pointer, requestType: Int): Int
    }

    @Structure.FieldOrder("version", "flags", "simpleReason")
    class ReasonContext : Structure() {
        @JvmField var version: Int = 0
        @JvmField var flags: Int = POWER_REQUEST_CONTEXT_SIMPLE_STRING
        @JvmField var simpleReason: Pointer? = null

        override fun getFieldOrder(): List<String> = listOf("version", "flags", "simpleReason")
    }

    private companion object {
        const val REASON = "Remote Controller Server is hosting phone connections"
        const val POWER_REQUEST_CONTEXT_SIMPLE_STRING = 0x00000001
        const val POWER_REQUEST_SYSTEM_REQUIRED = 1
        const val POWER_REQUEST_AWAY_MODE_REQUIRED = 2
        const val POWER_REQUEST_EXECUTION_REQUIRED = 3
        val REQUESTS = intArrayOf(
            POWER_REQUEST_SYSTEM_REQUIRED,
            POWER_REQUEST_AWAY_MODE_REQUIRED,
            POWER_REQUEST_EXECUTION_REQUIRED,
        )

        fun wideString(value: String): Memory =
            Memory(((value.length + 1) * Native.WCHAR_SIZE).toLong()).apply {
                setWideString(0, value)
            }
    }
}
