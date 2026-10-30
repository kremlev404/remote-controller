package com.remotecontroller.client.feature.screen.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.remotecontroller.shared.network.ScreenFrame
import kotlinx.coroutines.channels.ReceiveChannel

/** iOS asks for nothing, so the PC keeps sending it JPEG. */
internal actual val videoCodecs: List<String> = emptyList()

@Composable
internal actual fun VideoSurface(
    frames: ReceiveChannel<ScreenFrame.Video>,
    width: Int,
    height: Int,
    onRate: (Int) -> Unit,
    onLost: () -> Unit,
    modifier: Modifier,
) = Unit
