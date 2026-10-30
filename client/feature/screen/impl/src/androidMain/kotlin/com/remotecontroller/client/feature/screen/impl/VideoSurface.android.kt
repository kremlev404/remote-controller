package com.remotecontroller.client.feature.screen.impl

import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import com.remotecontroller.shared.network.ScreenCodec
import com.remotecontroller.shared.network.ScreenFrame
import kotlinx.coroutines.channels.ReceiveChannel

internal actual val videoCodecs: List<String> = listOf(ScreenCodec.H264)

/** A SurfaceView: frames go straight from the decoder to the display, past Compose. */
@Composable
internal actual fun VideoSurface(
    frames: ReceiveChannel<ScreenFrame.Video>,
    width: Int,
    height: Int,
    onRate: (Int) -> Unit,
    onLost: () -> Unit,
    modifier: Modifier,
) {
    val rate = rememberUpdatedState(onRate)
    val lost = rememberUpdatedState(onLost)
    AndroidExternalSurface(
        modifier = modifier,
        surfaceSize = IntSize(width, height),
    ) {
        onSurface { surface, _, _ ->
            val decoder = H264Decoder(
                surface = surface,
                onRate = { rate.value(it) },
                onLost = { lost.value() },
            )
            try {
                for (frame in frames) decoder.submit(frame)
            } finally {
                decoder.release()
            }
        }
    }
}
