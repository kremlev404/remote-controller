package com.remotecontroller.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ClipNote(
    val id: Long,
    val from: String,
    val text: String = "",
    val sentAt: Long,
    val image: String? = null,
    val imageType: String? = null,
) {
    companion object {
        const val DESKTOP = "desktop"
        const val WEB = "web"
        const val MAX_TEXT = 100_000
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    }
}

@Serializable
sealed interface ClipRequest {
    @Serializable
    @SerialName("send")
    data class Send(
        val text: String = "",
        val image: String? = null,
        val imageType: String? = null,
    ) : ClipRequest

    @Serializable
    @SerialName("clear")
    data object Clear : ClipRequest

    @Serializable
    @SerialName("present")
    data object Present : ClipRequest

    @Serializable
    @SerialName("pointer")
    data class Pointer(
        val x: Float,
        val y: Float,
        val phase: String,
        val button: String = "LEFT",
    ) : ClipRequest

    @Serializable
    @SerialName("scroll")
    data class Scroll(
        val x: Float,
        val y: Float,
        val deltaY: Int,
    ) : ClipRequest
}

@Serializable
sealed interface ClipEvent {
    @Serializable
    @SerialName("history")
    data class History(val messages: List<ClipNote>) : ClipEvent

    @Serializable
    @SerialName("note")
    data class Note(val message: ClipNote) : ClipEvent

    @Serializable
    @SerialName("error")
    data class Rejected(val message: String) : ClipEvent

    @Serializable
    @SerialName("cleared")
    data object Cleared : ClipEvent
}
