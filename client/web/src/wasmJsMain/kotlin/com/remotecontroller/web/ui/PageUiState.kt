package com.remotecontroller.web.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.remotecontroller.client.domain.repository.ClipSnapshot

/** A JPEG the page draws itself, or the video or tiles the player paints behind the page. */
sealed interface ScreenPicture {
    val width: Int
    val height: Int

    data class Still(val bitmap: ImageBitmap) : ScreenPicture {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height
    }

    data class Moving(
        override val width: Int,
        override val height: Int,
    ) : ScreenPicture
}

sealed interface LinkState {
    data object Connecting : LinkState
    data object Live : LinkState
    data class NeedsCode(val message: String?) : LinkState
    data class Waiting(val checkCode: String) : LinkState
    data class Stopped(val message: String) : LinkState
}

data class ScreenState(
    val picture: ScreenPicture? = null,
    val framesPerSecond: Int = 0,
    val watching: Boolean = true,
    val hostPaused: Boolean = false,
    val message: String = CONNECTING,
) {
    companion object {
        const val CONNECTING = "Connecting to the screen…"
    }
}

data class PageUiState(
    val link: LinkState = LinkState.Connecting,
    val clip: ClipSnapshot = ClipSnapshot(),
    val screen: ScreenState = ScreenState(),
    val keyboardCaptured: Boolean = false,
    val fullscreen: Boolean = false,
)
