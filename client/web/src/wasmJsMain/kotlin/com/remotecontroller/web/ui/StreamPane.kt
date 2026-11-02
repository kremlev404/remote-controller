package com.remotecontroller.web.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.remotecontroller.client.core.PictureTouch
import com.remotecontroller.client.core.TypingRow
import com.remotecontroller.client.core.letterbox
import com.remotecontroller.client.core.touchPicture
import com.remotecontroller.web.browser.canGoFullscreen
import com.remotecontroller.web.browser.hasPreciseMouse
import com.remotecontroller.web.browser.releaseKeysLabel

@Composable
internal fun StreamPane(
    state: PageUiState,
    viewModel: PageViewModel,
    modifier: Modifier = Modifier,
) {
    val screen = state.screen
    val picture = screen.picture
    var rightClick by remember { mutableStateOf(false) }
    Column(modifier = modifier.background(Color.Black)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (picture != null) {
                DesktopPicture(
                    picture = picture,
                    viewModel = viewModel,
                    touchButton = if (rightClick) "RIGHT" else "LEFT",
                )
                KeyboardHint(
                    captured = state.keyboardCaptured,
                    fullscreen = state.fullscreen,
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                )
            } else {
                Text(
                    text = screen.message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = when {
                        !screen.watching -> "Stopped"
                        screen.hostPaused -> "Paused on the computer"
                        picture == null -> "Screen"
                        else -> "${screen.framesPerSecond} fps"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
                TextButton(onClick = { if (screen.watching) viewModel.stopStream() else viewModel.resumeStream() }) {
                    Text(if (screen.watching) "Stop" else "Resume", color = Color.White)
                }
                if (picture != null && canGoFullscreen()) {
                    TextButton(onClick = viewModel::switchFullscreen) {
                        Text(if (state.fullscreen) "Exit full screen" else "Full screen", color = Color.White)
                    }
                }
                // A mouse has its own right button: this is for a finger.
                if (picture != null && !hasPreciseMouse()) {
                    FilterChip(
                        selected = rightClick,
                        onClick = { rightClick = !rightClick },
                        label = { Text("Right click") },
                    )
                }
            }
        }
        if (!state.fullscreen) {
            TypingRow(
                onType = viewModel::type,
                onEnter = viewModel::pressEnter,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun KeyboardHint(
    captured: Boolean,
    fullscreen: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!captured && !hasPreciseMouse()) return
    Column(
        modifier = modifier
            .background(
                if (captured) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.7f),
                RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val color = if (captured) MaterialTheme.colorScheme.onPrimary else Color.White
        Text(
            text = if (captured) "Keyboard captured" else "Click the screen to capture the keyboard",
            color = color,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (captured) {
            Text(
                text = "Press ${releaseKeysLabel()} to release it",
                color = color,
                style = MaterialTheme.typography.titleMedium,
            )
            if (fullscreen) {
                Text(
                    text = "Hold Esc to leave full screen",
                    color = color,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun DesktopPicture(
    picture: ScreenPicture,
    viewModel: PageViewModel,
    touchButton: String,
) {
    val imageWidth = picture.width
    val imageHeight = picture.height
    val density = LocalDensity.current.density
    val button by rememberUpdatedState(touchButton)
    // A new size restarts the gestures below, and a press in progress must not outlive them.
    DisposableEffect(imageWidth, imageHeight) {
        onDispose { viewModel.releasePointer() }
    }
    val input = Modifier
        .fillMaxSize()
        .desktopMouse(imageWidth, imageHeight, viewModel)
        .pointerInput(imageWidth, imageHeight, viewModel) {
            touchPicture(
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                button = { button },
                touch = PictureTouch(
                    onDown = viewModel::pointerDown,
                    onMove = viewModel::pointerMove,
                    onUp = viewModel::pointerUp,
                    onScroll = viewModel::scroll,
                ),
                accepts = { it.type != PointerType.Mouse },
            )
        }
    when (picture) {
        is ScreenPicture.Still -> Image(
            bitmap = picture.bitmap,
            contentDescription = "Desktop screen",
            contentScale = ContentScale.Fit,
            modifier = input,
        )
        // The video is on a canvas behind the page: this box only clears the way to it.
        is ScreenPicture.Moving -> Canvas(
            modifier = input.onGloballyPositioned { box ->
                val origin = box.positionInWindow()
                val placed = letterbox(
                    boxWidth = box.size.width.toFloat(),
                    boxHeight = box.size.height.toFloat(),
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                )
                viewModel.placeVideo(
                    ((origin.x + placed.left) / density).toDouble(),
                    ((origin.y + placed.top) / density).toDouble(),
                    (placed.width / density).toDouble(),
                    (placed.height / density).toDouble(),
                )
            },
        ) {
            drawRect(color = Color.Transparent, blendMode = BlendMode.Clear)
        }
    }
}
