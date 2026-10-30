package com.remotecontroller.client.feature.screen.impl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.remotecontroller.client.domain.model.ConnectionState
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.min

@Composable
fun ScreenScreen(
    onBack: () -> Unit,
    onDisconnected: () -> Unit,
    viewModel: ScreenViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var rightClick by remember { mutableStateOf(false) }

    DisposableEffect(viewModel) {
        onDispose { viewModel.releasePointer() }
    }

    LaunchedEffect(uiState.connectionState) {
        if (uiState.connectionState == ConnectionState.Disconnected) onDisconnected()
    }

    Scaffold(containerColor = Color.Black) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val image = uiState.image
            if (image == null) {
                Text(
                    text = uiState.message ?: "Connecting to the screen…",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                DesktopPicture(
                    image = image,
                    button = if (rightClick) "RIGHT" else "LEFT",
                    onDown = viewModel::pointerDown,
                    onMove = viewModel::pointerMove,
                    onUp = viewModel::pointerUp,
                    onScroll = viewModel::pointerScroll,
                    onRelease = viewModel::releasePointer,
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
                Text(
                    text = if (image == null) "Screen" else "${uiState.framesPerSecond} fps",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(end = 8.dp),
                )
                FilterChip(
                    selected = rightClick,
                    onClick = { rightClick = !rightClick },
                    label = { Text("Right click") },
                )
            }
        }
    }
}

@Composable
private fun DesktopPicture(
    image: ImageBitmap,
    button: String,
    onDown: (Float, Float, String) -> Unit,
    onMove: (Float, Float, String) -> Unit,
    onUp: (Float, Float, String) -> Unit,
    onScroll: (Float, Float, Int) -> Unit,
    onRelease: () -> Unit,
) {
    val imageWidth = image.width
    val imageHeight = image.height
    val downState = rememberUpdatedState(onDown)
    val moveState = rememberUpdatedState(onMove)
    val upState = rememberUpdatedState(onUp)
    val scrollState = rememberUpdatedState(onScroll)
    val buttonState = rememberUpdatedState(button)
    var touch by remember { mutableStateOf<Offset?>(null) }

    DisposableEffect(imageWidth, imageHeight) {
        onDispose { onRelease() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(imageWidth, imageHeight) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val placed = letterbox(
                        boxWidth = size.width.toFloat(),
                        boxHeight = size.height.toFloat(),
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                    )
                    if (!placed.contains(first.position)) {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                        }
                        return@awaitEachGesture
                    }
                    val buttonName = buttonState.value
                    var last = placed.normalize(first.position)
                    downState.value(last.x, last.y, buttonName)
                    touch = last
                    first.consume()
                    var scrolling = false
                    var lastY = first.position.y
                    var accumulated = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            if (!scrolling) upState.value(last.x, last.y, buttonName)
                            touch = null
                            break
                        }
                        if (pressed.size >= 2) {
                            var sum = 0f
                            for (change in pressed) sum += change.position.y
                            val y = sum / pressed.size
                            if (!scrolling) {
                                upState.value(last.x, last.y, buttonName)
                                scrolling = true
                                lastY = y
                                accumulated = 0f
                                touch = null
                            } else {
                                accumulated += -(y - lastY)
                                lastY = y
                                val steps = (accumulated / SCROLL_STEP_PX).toInt()
                                if (steps != 0) {
                                    scrollState.value(last.x, last.y, steps)
                                    accumulated -= steps * SCROLL_STEP_PX
                                }
                            }
                        } else if (!scrolling) {
                            val current = letterbox(
                                boxWidth = size.width.toFloat(),
                                boxHeight = size.height.toFloat(),
                                imageWidth = imageWidth,
                                imageHeight = imageHeight,
                            ).normalize(pressed[0].position)
                            last = current
                            moveState.value(current.x, current.y, buttonName)
                            touch = current
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        Image(
            bitmap = image,
            contentDescription = "Desktop",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val mark = touch ?: return@Canvas
            val placed = letterbox(size.width, size.height, imageWidth, imageHeight)
            val center = Offset(
                x = placed.left + mark.x * placed.width,
                y = placed.top + mark.y * placed.height,
            )
            drawCircle(color = Color.Black, radius = 8.dp.toPx(), center = center)
            drawCircle(color = Color.White, radius = 5.dp.toPx(), center = center)
        }
    }
}

private data class PlacedImage(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    fun contains(position: Offset): Boolean =
        position.x in left..(left + width) && position.y in top..(top + height)

    fun normalize(position: Offset): Offset {
        val x = if (width <= 0f) 0f else ((position.x - left) / width).coerceIn(0f, 1f)
        val y = if (height <= 0f) 0f else ((position.y - top) / height).coerceIn(0f, 1f)
        return Offset(x, y)
    }
}

private fun letterbox(boxWidth: Float, boxHeight: Float, imageWidth: Int, imageHeight: Int): PlacedImage {
    if (boxWidth <= 0f || boxHeight <= 0f || imageWidth <= 0 || imageHeight <= 0) {
        return PlacedImage(0f, 0f, boxWidth.coerceAtLeast(0f), boxHeight.coerceAtLeast(0f))
    }
    val scale = min(boxWidth / imageWidth, boxHeight / imageHeight)
    val drawnWidth = imageWidth * scale
    val drawnHeight = imageHeight * scale
    return PlacedImage(
        left = (boxWidth - drawnWidth) / 2f,
        top = (boxHeight - drawnHeight) / 2f,
        width = drawnWidth,
        height = drawnHeight,
    )
}

private const val SCROLL_STEP_PX = 32f
