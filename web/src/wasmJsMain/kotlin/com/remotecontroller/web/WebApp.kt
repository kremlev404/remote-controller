package com.remotecontroller.web

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.remotecontroller.shared.model.ClipNote
import com.remotecontroller.shared.model.RemoteKeys
import com.remotecontroller.shared.network.LinkCrypto
import com.remotecontroller.shared.network.RelayProtocol
import kotlin.math.min
import kotlin.io.encoding.Base64
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun WebApp() {
    WebTheme(darkTheme = isSystemInDarkTheme()) {
        var target by remember { mutableStateOf(PageTarget.fromPage()) }
        if (target.relay && target.deviceId.length != RelayProtocol.ID_DIGITS) {
            DeviceGate(onContinue = { id, code -> target = target.copy(deviceId = id, code = code) })
        } else {
            LinkedSession(target)
        }
    }
}

@Composable
private fun GateColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

@Composable
private fun DeviceGate(onContinue: (String, String) -> Unit) {
    var id by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val digits = RelayProtocol.normalizeId(id)
    GateColumn {
        Text(
            text = "Connect to a PC",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Type the ID shown on the PC. Add its access code if the PC asks for one; otherwise you will wait for Allow.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = id,
            onValueChange = { id = it.take(16) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("PC ID") },
            placeholder = { Text("123 456 789") },
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Access code (optional)") },
            placeholder = { Text("XXXX-XXXX-XXXX") },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = { onContinue(digits, code) },
            enabled = digits.length == RelayProtocol.ID_DIGITS && (code.isBlank() || LinkCrypto.isCode(code)),
        ) {
            Text("Connect")
        }
    }
}

@Composable
private fun AccessGate(
    message: String?,
    onContinue: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    GateColumn {
        Text(
            text = "Access code",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message ?: "This PC asks for the code from its window. It is not saved in this page.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("XXXX-XXXX-XXXX") },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = { onContinue(code) },
            enabled = LinkCrypto.isCode(code),
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun WaitingGate(checkCode: String) {
    GateColumn {
        Text(
            text = "Waiting for the PC",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Press Allow on the PC. It shows this number; if it shows another one, deny there.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = checkCode,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StoppedGate(message: String, onRetry: () -> Unit) {
    GateColumn {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onRetry) {
            Text("Ask again")
        }
    }
}

@Composable
private fun LinkedSession(target: PageTarget) {
        val session = remember(target) { PageSession(target) }
        DisposableEffect(session) {
            session.start()
            onDispose { session.close() }
        }
        val linkState by session.linkState.collectAsState()
        when (val state = linkState) {
            is LinkState.NeedsCode -> {
                AccessGate(message = state.message, onContinue = session::submitCode)
                return
            }
            is LinkState.Waiting -> {
                WaitingGate(state.checkCode)
                return
            }
            is LinkState.Stopped -> {
                StoppedGate(state.message, onRetry = session::retry)
                return
            }
            LinkState.Connecting, LinkState.Live -> Unit
        }
        val notes by session.notes.collectAsState()
        val banner by session.clipBanner.collectAsState()
        val connected by session.clipConnected.collectAsState()
        val frame by session.frame.collectAsState()
        val fps by session.framesPerSecond.collectAsState()
        val watching by session.watching.collectAsState()
        val hostPaused by session.hostPaused.collectAsState()
        val streamMessage by session.streamMessage.collectAsState()
        var draft by remember { mutableStateOf("") }
        var rightClick by remember { mutableStateOf(false) }

        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val wide = maxWidth >= 840.dp
            if (wide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    StreamPane(
                        frame = frame,
                        framesPerSecond = fps,
                        watching = watching,
                        hostPaused = hostPaused,
                        message = streamMessage,
                        onStop = session::stopStream,
                        onResume = session::resumeStream,
                        rightClick = rightClick,
                        onRightClick = { rightClick = !rightClick },
                        onPointer = session::pointer,
                        onScroll = session::scroll,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    ChatPane(
                        notes = notes,
                        banner = banner,
                        connected = connected,
                        draft = draft,
                        onDraft = { draft = it },
                        onSend = {
                            session.send(draft)
                            draft = ""
                        },
                        onClear = session::clearHistory,
                        modifier = Modifier.width(360.dp).fillMaxHeight(),
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    StreamPane(
                        frame = frame,
                        framesPerSecond = fps,
                        watching = watching,
                        hostPaused = hostPaused,
                        message = streamMessage,
                        onStop = session::stopStream,
                        onResume = session::resumeStream,
                        rightClick = rightClick,
                        onRightClick = { rightClick = !rightClick },
                        onPointer = session::pointer,
                        onScroll = session::scroll,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    ChatPane(
                        notes = notes,
                        banner = banner,
                        connected = connected,
                        draft = draft,
                        onDraft = { draft = it },
                        onSend = {
                            session.send(draft)
                            draft = ""
                        },
                        onClear = session::clearHistory,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 320.dp),
                    )
                }
            }
        }
}

@Composable
private fun StreamPane(
    frame: ImageBitmap?,
    framesPerSecond: Int,
    watching: Boolean,
    hostPaused: Boolean,
    message: String,
    onStop: () -> Unit,
    onResume: () -> Unit,
    rightClick: Boolean,
    onRightClick: () -> Unit,
    onPointer: (Float, Float, String, String) -> Unit,
    onScroll: (Float, Float, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(Color.Black)) {
        if (frame != null) {
            DesktopPicture(
                image = frame,
                button = if (rightClick) "RIGHT" else "LEFT",
                onPointer = onPointer,
                onScroll = onScroll,
            )
        } else {
            Text(
                text = message,
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
                    !watching -> "Stopped"
                    hostPaused -> "Paused on the computer"
                    frame == null -> "Screen"
                    else -> "$framesPerSecond fps"
                },
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
            TextButton(onClick = { if (watching) onStop() else onResume() }) {
                Text(if (watching) "Stop" else "Resume", color = Color.White)
            }
            if (frame != null) {
                FilterChip(
                    selected = rightClick,
                    onClick = onRightClick,
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
    onPointer: (Float, Float, String, String) -> Unit,
    onScroll: (Float, Float, Int) -> Unit,
) {
    val imageWidth = image.width
    val imageHeight = image.height
    Image(
        bitmap = image,
        contentDescription = "Desktop screen",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(imageWidth, imageHeight, button) {
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
                    val buttonName = if (currentEvent.buttons.isSecondaryPressed) "RIGHT" else button
                    var last = placed.normalize(first.position)
                    onPointer(last.x, last.y, RemoteKeys.POINTER_DOWN, buttonName)
                    first.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            onPointer(last.x, last.y, RemoteKeys.POINTER_UP, buttonName)
                            break
                        }
                        val current = letterbox(
                            boxWidth = size.width.toFloat(),
                            boxHeight = size.height.toFloat(),
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                        ).normalize(pressed[0].position)
                        last = current
                        onPointer(current.x, current.y, RemoteKeys.POINTER_MOVE, buttonName)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            .pointerInput(imageWidth, imageHeight) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val change = event.changes.firstOrNull() ?: continue
                        val placed = letterbox(
                            boxWidth = size.width.toFloat(),
                            boxHeight = size.height.toFloat(),
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                        )
                        if (!placed.contains(change.position)) continue
                        val point = placed.normalize(change.position)
                        val steps = change.scrollDelta.y.toInt().coerceIn(-20, 20)
                        if (steps != 0) onScroll(point.x, point.y, steps)
                        change.consume()
                    }
                }
            },
    )
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

@Composable
private fun ChatPane(
    notes: List<ClipNote>,
    banner: String?,
    connected: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(notes.size) {
        if (notes.isNotEmpty()) listState.animateScrollToItem(notes.lastIndex)
    }
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (connected) "Connected" else "Connecting…",
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (banner != null) {
            Text(
                text = banner,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            if (notes.isEmpty()) {
                item {
                    Text(
                        text = "Nothing yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(notes, key = { it.id }) { note ->
                NoteLine(note)
            }
        }
        Row(
            modifier = Modifier.padding(end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f).sendOnEnter(onSend),
                placeholder = { Text("Message") },
                maxLines = 4,
                shape = RoundedCornerShape(16.dp),
            )
            IconButton(
                onClick = onClear,
                enabled = notes.isNotEmpty(),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Clear",
                    modifier = Modifier.size(20.dp),
                )
            }
            TextButton(
                onClick = onSend,
                enabled = draft.isNotBlank(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
private fun NoteLine(note: ClipNote) {
    val mine = note.from == ClipNote.WEB
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = if (mine) "This browser" else "PC",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (note.text.isNotEmpty()) {
            Text(
                text = note.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .background(
                        if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        val encoded = note.image
        if (encoded != null) {
            val bitmap = remember(note.id) {
                runCatching {
                    SkiaImage.makeFromEncoded(Base64.Default.decode(encoded)).toComposeImageBitmap()
                }.getOrNull()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Clipboard image",
                    modifier = Modifier.padding(top = 4.dp).heightIn(max = 160.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

private fun Modifier.sendOnEnter(onSend: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val send = event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed
    if (!send) return@onPreviewKeyEvent false
    onSend()
    true
}
