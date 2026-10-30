package com.remotecontroller.desktop.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Frame rate cap of the screen stream. All viewers share one capture loop, so they share the mode. */
enum class ScreenMode(val label: String, val framesPerSecond: Int) {
    ECONOMY("Economy", 15),
    BALANCED("Balanced", 30),
    PERFORMANCE("Performance", 60);

    val frameInterval: Duration = 1.seconds / framesPerSecond

    companion object {
        fun fromStored(value: String?): ScreenMode = entries.firstOrNull { it.name == value } ?: BALANCED
    }
}

/** Settings that survive a restart. Stored in the user's Java preferences. */
class DesktopSettings {
    private val prefs = Preferences.userRoot().node("com/remotecontroller/desktop")

    val requireCode: StateFlow<Boolean>
        field = MutableStateFlow(prefs.getBoolean(KEY_REQUIRE_CODE, true))

    val relayEnabled: StateFlow<Boolean>
        field = MutableStateFlow(prefs.getBoolean(KEY_RELAY_ENABLED, false))

    val relayUrl: StateFlow<String>
        field = MutableStateFlow(prefs.get(KEY_RELAY_URL, DEFAULT_RELAY_URL))

    val screenMode: StateFlow<ScreenMode>
        field = MutableStateFlow(ScreenMode.fromStored(prefs.get(KEY_SCREEN_MODE, null)))

    /** Null until the user picks one: the window then follows the system. */
    val darkTheme: StateFlow<Boolean?>
        field = MutableStateFlow(prefs.get(KEY_DARK_THEME, null)?.toBooleanStrictOrNull())

    /** Tells this PC apart from others whatever its address is. Clients keep their grant under it. */
    val pcId: String by lazy {
        prefs.get(KEY_PC_ID, null) ?: UUID.randomUUID().toString().also { prefs.put(KEY_PC_ID, it) }
    }

    fun setRequireCode(enabled: Boolean) {
        prefs.putBoolean(KEY_REQUIRE_CODE, enabled)
        requireCode.value = enabled
    }

    fun setRelayEnabled(enabled: Boolean) {
        prefs.putBoolean(KEY_RELAY_ENABLED, enabled)
        relayEnabled.value = enabled
    }

    fun setRelayUrl(url: String) {
        val cleaned = url.trim().trimEnd('/')
        prefs.put(KEY_RELAY_URL, cleaned)
        relayUrl.value = cleaned
    }

    fun setScreenMode(mode: ScreenMode) {
        prefs.put(KEY_SCREEN_MODE, mode.name)
        screenMode.value = mode
    }

    fun setDarkTheme(dark: Boolean) {
        prefs.putBoolean(KEY_DARK_THEME, dark)
        darkTheme.value = dark
    }

    private companion object {
        const val KEY_REQUIRE_CODE = "requireCode"
        const val KEY_RELAY_ENABLED = "relayEnabled"
        const val KEY_RELAY_URL = "relayUrl"
        const val KEY_SCREEN_MODE = "screenMode"
        const val KEY_DARK_THEME = "darkTheme"
        const val KEY_PC_ID = "pcId"
        const val DEFAULT_RELAY_URL = ""
    }
}

/** This user's app folder: the local CA and the relay device key live here. */
internal object AppFiles {
    val directory: File by lazy {
        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name")
        val directory = when {
            os.startsWith("Mac") -> File(home, "Library/Application Support/Remote Controller")
            os.startsWith("Win") -> File(System.getenv("APPDATA") ?: home, "Remote Controller")
            else -> File(
                System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share",
                "remote-controller",
            )
        }
        directory.mkdirs()
        directory
    }

    /** Owner-only on POSIX; on Windows %APPDATA% is already per user. */
    fun restrict(file: File) {
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }
}
