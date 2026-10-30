package com.remotecontroller.client.feature.screen.impl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.remotecontroller.client.core.TypingRow
import com.remotecontroller.client.domain.model.ConnectionState
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun ScreenScreen(
    onBack: () -> Unit,
    onDisconnected: () -> Unit,
    viewModel: ScreenViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var rightClick by remember { mutableStateOf(false) }
    var showHint by remember { mutableStateOf(true) }
    val trackpad = remember(viewModel) {
        TrackpadActions(
            move = viewModel::trackpadMove,
            click = viewModel::trackpadClick,
            press = viewModel::trackpadPress,
            release = viewModel::trackpadRelease,
            scroll = viewModel::trackpadScroll,
            touched = { showHint = false },
        )
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.releasePointer() }
    }

    LaunchedEffect(uiState.connectionState) {
        if (uiState.connectionState == ConnectionState.Disconnected) onDisconnected()
    }

    Scaffold(containerColor = Color.Black) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                val image = uiState.image
                val video = uiState.video
                val pictureWidth = image?.width ?: video?.width
                val pictureHeight = image?.height ?: video?.height
                if (pictureWidth == null || pictureHeight == null) {
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
                        imageWidth = pictureWidth,
                        imageHeight = pictureHeight,
                        picture = { modifier ->
                            if (image != null) {
                                Image(
                                    bitmap = image,
                                    contentDescription = "Desktop",
                                    contentScale = ContentScale.Fit,
                                    modifier = modifier,
                                )
                            } else {
                                VideoSurface(
                                    frames = viewModel.videoFrames,
                                    width = pictureWidth,
                                    height = pictureHeight,
                                    onRate = viewModel::videoRate,
                                    onLost = viewModel::videoLost,
                                    modifier = modifier,
                                )
                            }
                        },
                        touchMode = uiState.touchMode,
                        cursor = Offset(uiState.cursorX, uiState.cursorY),
                        pointerScale = uiState.pointerScale,
                        button = if (rightClick) "RIGHT" else "LEFT",
                        trackpad = trackpad,
                        onDown = viewModel::pointerDown,
                        onMove = viewModel::pointerMove,
                        onUp = viewModel::pointerUp,
                        onScroll = viewModel::pointerScroll,
                        onRelease = viewModel::releasePointer,
                    )
                    if (showHint && !uiState.touchMode) {
                        Text(
                            text = "Drag to move · Tap to click · Double tap and hold to drag\n" +
                                "Two fingers: tap to right click, drag to scroll",
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(12.dp)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
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
                        text = if (uiState.hasPicture) "${uiState.framesPerSecond} fps" else "Screen",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    FilterChip(
                        selected = uiState.touchMode,
                        onClick = { viewModel.setTouchMode(!uiState.touchMode) },
                        label = { Text("Touch") },
                        colors = FilterChipDefaults.filterChipColors(labelColor = Color.White),
                    )
                    if (uiState.touchMode) {
                        FilterChip(
                            selected = rightClick,
                            onClick = { rightClick = !rightClick },
                            label = { Text("Right click") },
                            colors = FilterChipDefaults.filterChipColors(labelColor = Color.White),
                        )
                    }
                }
            }
            TypingRow(
                onType = viewModel::type,
                onEnter = viewModel::pressEnter,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

private class TrackpadActions(
    val move: (Float, Float) -> Unit,
    val click: (String) -> Unit,
    val press: () -> Unit,
    val release: () -> Unit,
    val scroll: (Int) -> Unit,
    val touched: () -> Unit,
)

@Composable
private fun DesktopPicture(
    imageWidth: Int,
    imageHeight: Int,
    picture: @Composable (Modifier) -> Unit,
    touchMode: Boolean,
    cursor: Offset,
    pointerScale: Float,
    button: String,
    trackpad: TrackpadActions,
    onDown: (Float, Float, String) -> Unit,
    onMove: (Float, Float, String) -> Unit,
    onUp: (Float, Float, String) -> Unit,
    onScroll: (Float, Float, Int) -> Unit,
    onRelease: () -> Unit,
) {
    val downState = rememberUpdatedState(onDown)
    val moveState = rememberUpdatedState(onMove)
    val upState = rememberUpdatedState(onUp)
    val scrollState = rememberUpdatedState(onScroll)
    val buttonState = rememberUpdatedState(button)
    val scaleState = rememberUpdatedState(pointerScale)
    var touch by remember { mutableStateOf<Offset?>(null) }

    DisposableEffect(imageWidth, imageHeight, touchMode) {
        onDispose {
            touch = null
            onRelease()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(imageWidth, imageHeight, touchMode) {
                if (!touchMode) {
                    trackpadGestures(
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                        pointerScale = { scaleState.value },
                        actions = trackpad,
                    )
                    return@pointerInput
                }
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
        picture(
            Modifier
                .align(Alignment.Center)
                .aspectRatio(imageWidth.toFloat() / imageHeight, matchHeightConstraintsFirst = false)
                .fillMaxSize(),
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val placed = letterbox(size.width, size.height, imageWidth, imageHeight)
            val mark = if (touchMode) touch else cursor
            if (mark == null) return@Canvas
            val center = Offset(
                x = placed.left + mark.x * placed.width,
                y = placed.top + mark.y * placed.height,
            )
            if (touchMode) {
                drawCircle(color = Color.Black, radius = 8.dp.toPx(), center = center)
                drawCircle(color = Color.White, radius = 5.dp.toPx(), center = center)
            } else {
                translate(center.x, center.y) {
                    scale(density, pivot = Offset.Zero) {
                        drawPath(CursorArrow, Color.White)
                        drawPath(CursorArrow, Color.Black, style = Stroke(width = 1f))
                    }
                }
            }
        }
    }
}

/**
 * The finger moves the cursor and nothing is pressed until a tap, like a laptop touchpad.
 * A press right after a tap holds the left button, so tap + tap is a double click
 * and tap + press and move is a drag.
 */
private suspend fun PointerInputScope.trackpadGestures(
    imageWidth: Int,
    imageHeight: Int,
    pointerScale: () -> Float,
    actions: TrackpadActions,
) {
    var lastTapAt = -TAP_THEN_PRESS_MS
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        first.consume()
        actions.touched()
        val startedAt = first.uptimeMillis
        val holding = startedAt - lastTapAt < TAP_THEN_PRESS_MS
        if (holding) actions.press()
        var endedAt = startedAt
        var fingers = 1
        var travel = 0f
        var moving = false
        var scrolling = false
        var lastY = 0f
        var accumulated = 0f
        while (true) {
            val event = awaitPointerEvent()
            endedAt = event.changes.maxOf { it.uptimeMillis }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            fingers = max(fingers, pressed.size)
            if (pressed.size >= 2 && !holding) {
                var sum = 0f
                for (change in pressed) sum += change.position.y
                val y = sum / pressed.size
                if (lastY != 0f) {
                    accumulated += -(y - lastY)
                    val steps = (accumulated / SCROLL_STEP_PX).toInt()
                    if (steps != 0) {
                        scrolling = true
                        actions.scroll(steps)
                        accumulated -= steps * SCROLL_STEP_PX
                    }
                }
                lastY = y
            } else if (fingers == 1 || holding) {
                val delta = pressed[0].positionChange()
                travel += abs(delta.x) + abs(delta.y)
                if (travel > viewConfiguration.touchSlop) moving = true
                if (moving && delta != Offset.Zero) {
                    val scale = pointerScale()
                    actions.move(delta.x * scale / imageWidth, delta.y * scale / imageHeight)
                }
            }
            event.changes.forEach { it.consume() }
        }
        val quick = endedAt - startedAt < TAP_MAX_MS
        when {
            holding -> {
                actions.release()
                lastTapAt = -TAP_THEN_PRESS_MS
            }
            fingers >= 2 -> if (quick && !scrolling) actions.click("RIGHT")
            quick && !moving -> {
                actions.click("LEFT")
                lastTapAt = endedAt
            }
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

private val CursorArrow = Path().apply {
    moveTo(0f, 0f)
    lineTo(0f, 17f)
    lineTo(4.5f, 13f)
    lineTo(7.5f, 19.5f)
    lineTo(10.5f, 18.2f)
    lineTo(7.5f, 12f)
    lineTo(13f, 12f)
    close()
}

private const val SCROLL_STEP_PX = 32f
private const val TAP_MAX_MS = 250L
private const val TAP_THEN_PRESS_MS = 300L
