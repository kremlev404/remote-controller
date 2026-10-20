package com.remotecontroller.shared.model

import kotlin.jvm.JvmInline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class PcHost(val value: String) {
    init {
        require(value.isNotBlank()) { "PC IP address cannot be blank" }
    }
}

@JvmInline
@Serializable
value class ServerPort(val value: Int) {
    init {
        require(value in 1..65535) { "Port must be between 1 and 65535" }
    }

    companion object {
        val DEFAULT = ServerPort(8765)
    }
}

@JvmInline
@Serializable
value class VolumePercent(val value: Int) {
    fun coerce(): VolumePercent = VolumePercent(value.coerceIn(0, 100))
}

@Serializable
sealed interface RemoteCommand {

    @Serializable
    @SerialName("volume_set")
    data class SetVolume(val value: Int) : RemoteCommand

    @Serializable
    @SerialName("volume_up")
    data class VolumeUp(val step: Int = 5) : RemoteCommand

    @Serializable
    @SerialName("volume_down")
    data class VolumeDown(val step: Int = 5) : RemoteCommand

    @Serializable
    @SerialName("volume_mute")
    data object MuteToggle : RemoteCommand

    @Serializable
    @SerialName("key_press")
    data class KeyPress(val key: String) : RemoteCommand

    @Serializable
    @SerialName("key_combo")
    data class KeyCombo(val keys: List<String>) : RemoteCommand

    @Serializable
    @SerialName("mouse_move")
    data class MouseMove(val dx: Int, val dy: Int) : RemoteCommand

    @Serializable
    @SerialName("mouse_click")
    data class MouseClick(val button: String = "LEFT") : RemoteCommand

    @Serializable
    @SerialName("mouse_scroll")
    data class MouseScroll(val deltaY: Int) : RemoteCommand

    @Serializable
    @SerialName("hello")
    data class Hello(val name: String) : RemoteCommand

    @Serializable
    @SerialName("pointer")
    data class Pointer(
        val x: Float,
        val y: Float,
        val phase: String,
        val button: String = "LEFT",
    ) : RemoteCommand

    @Serializable
    @SerialName("pointer_scroll")
    data class PointerScroll(
        val x: Float,
        val y: Float,
        val deltaY: Int,
    ) : RemoteCommand
}

fun RemoteCommand.describe(): String = when (this) {
    is RemoteCommand.SetVolume -> "Volume → $value%"
    is RemoteCommand.VolumeUp -> "Volume Up"
    is RemoteCommand.VolumeDown -> "Volume Down"
    is RemoteCommand.MuteToggle -> "Toggle Mute"
    is RemoteCommand.KeyPress -> "Key: $key"
    is RemoteCommand.KeyCombo -> "Combo: ${keys.joinToString("+")}"
    is RemoteCommand.MouseMove -> "Mouse move ($dx, $dy)"
    is RemoteCommand.MouseClick -> "Mouse ${button.lowercase()} click"
    is RemoteCommand.MouseScroll -> "Mouse scroll $deltaY"
    is RemoteCommand.Hello -> "Client connected"
    is RemoteCommand.Pointer -> "Pointer ${phase.lowercase()} ${button.lowercase()}"
    is RemoteCommand.PointerScroll -> "Pointer scroll $deltaY"
}

@Serializable
data class ServerInfo(
    val name: String,
    val port: Int,
    val version: String = "1.0",
)

@Serializable
data class ServerStatus(
    val connectedClients: Int,
    val volumeLevel: Int,
    val isMuted: Boolean,
    val lastCommand: String? = null,
)

@Serializable
sealed interface ServerMessage {

    @Serializable
    @SerialName("status")
    data class Status(val status: ServerStatus) : ServerMessage

    @Serializable
    @SerialName("ack")
    data class Ack(val command: RemoteCommand, val success: Boolean) : ServerMessage

    @Serializable
    @SerialName("error")
    data class Error(val message: String) : ServerMessage

    @Serializable
    @SerialName("disconnect")
    data class Disconnect(val reason: String = "Closed from the PC") : ServerMessage
}

object RemoteKeys {
    val DEFAULT_PORT: Int = ServerPort.DEFAULT.value

    val MEDIA_KEYS = listOf("PLAY_PAUSE", "NEXT", "PREVIOUS", "STOP")
    val ARROW_KEYS = listOf("UP", "DOWN", "LEFT", "RIGHT")
    val MODIFIER_KEYS = listOf("CTRL", "ALT", "SHIFT", "WIN")
    val COMMON_KEYS = listOf(
        "ENTER", "ESC", "TAB", "SPACE", "BACKSPACE", "DELETE",
        "HOME", "END", "PAGE_UP", "PAGE_DOWN",
    )
    val FUNCTION_KEYS = (1..12).map { "F$it" }
    val MOUSE_BUTTONS = listOf("LEFT", "RIGHT",)
    val MOUSE_BUTTONS_ADD = listOf( "ESC", "SPACE", "ENTER")

    const val POINTER_DOWN = "down"
    const val POINTER_MOVE = "move"
    const val POINTER_UP = "up"
}
