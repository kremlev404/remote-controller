package com.remotecontroller.client.feature.screen.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.remotecontroller.shared.network.ScreenFrame
import kotlinx.coroutines.channels.ReceiveChannel

/** The video this device can decode in hardware. Empty means the PC sends JPEG. */
internal expect val videoCodecs: List<String>

/**
 * Plays [frames] of a [width] × [height] stream. [onRate] gets the frames shown per second,
 * [onLost] asks for a keyframe: after a gap nothing can be decoded until one arrives.
 */
@Composable
internal expect fun VideoSurface(
    frames: ReceiveChannel<ScreenFrame.Video>,
    width: Int,
    height: Int,
    onRate: (Int) -> Unit,
    onLost: () -> Unit,
    modifier: Modifier,
)
