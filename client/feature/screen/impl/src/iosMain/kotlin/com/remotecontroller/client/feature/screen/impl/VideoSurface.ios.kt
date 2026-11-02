package com.remotecontroller.client.feature.screen.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.remotecontroller.shared.network.ScreenCodec
import com.remotecontroller.shared.network.ScreenFrame
import kotlinx.coroutines.channels.ReceiveChannel

/** No video decoder here yet. Tiles only need JPEG, which iOS already draws. */
internal actual val videoCodecs: List<String> = listOf(ScreenCodec.TILES)

@Composable
internal actual fun VideoSurface(
    frames: ReceiveChannel<ScreenFrame.Video>,
    width: Int,
    height: Int,
    onRate: (Int) -> Unit,
    onLost: () -> Unit,
    modifier: Modifier,
) = Unit
