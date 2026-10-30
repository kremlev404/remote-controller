package com.remotecontroller.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.remotecontroller.shared.model.ClipNote
import com.remotecontroller.shared.network.RemoteProtocol
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import kotlinx.coroutines.delay
import org.jetbrains.skia.Image as SkiaImage

@Composable
internal fun ClipboardCard(
    notes: List<ClipNote>,
    sharing: Boolean,
    port: Int,
    recommendedAddress: String?,
    errorMessage: String?,
    onClipboardId: Long?,
    sentCount: Int,
    onSend: (String) -> Unit,
    onSendClipboard: () -> Unit,
    onCopy: (ClipNote) -> Unit,
    onClear: () -> Unit,
    onSharingChange: (Boolean) -> Unit,
    onOpenPage: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(sentCount) {
        if (sentCount > 0) draft = ""
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = 20.dp, top = 8.dp, end = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AddressBlock(
                url = clipUrl(recommendedAddress ?: "127.0.0.1", port),
                onOpen = onOpenPage,
            )

            if (notes.isEmpty()) {
                Text(
                    text = "Nothing yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                MessageList(
                    notes = notes,
                    onClipboardId = onClipboardId,
                    onCopy = onCopy,
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(start = 4.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier
                            .weight(1f)
                            .onPreviewKeyEvent { event ->
                                val send = event.type == KeyEventType.KeyDown &&
                                    event.key == Key.Enter &&
                                    !event.isShiftPressed
                                if (!send) return@onPreviewKeyEvent false
                                if (draft.isNotBlank()) onSend(draft)
                                true
                            },
                        placeholder = { Text("Message") },
                        maxLines = 3,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    IconButton(onClick = onSendClipboard, modifier = Modifier.size(36.dp)) {
                        Icon(
                            DesktopIcons.ContentPaste,
                            contentDescription = "From clipboard",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = onClear,
                        enabled = notes.isNotEmpty(),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            DesktopIcons.Delete,
                            contentDescription = "Clear",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    BoxSend(
                        enabled = draft.isNotBlank(),
                        onClick = { if (draft.isNotBlank()) onSend(draft) },
                    )
                }
            }

            errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AddressBlock(
    url: String,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
            Text(
                text = url,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            IconButton(onClick = onOpen, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = DesktopIcons.OpenInNew,
                    contentDescription = "Open",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
}

@Composable
private fun MessageList(
    notes: List<ClipNote>,
    onClipboardId: Long?,
    onCopy: (ClipNote) -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(notes.lastOrNull()?.id) {
        delay(32)
        scroll.scrollTo(scroll.maxValue)
    }

    SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(scroll)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            notes.forEach { note ->
                ClipBubble(
                    note = note,
                    onClipboard = note.id == onClipboardId,
                    onCopy = { onCopy(note) },
                )
            }
        }
    }
}

@Composable
private fun ClipBubble(
    note: ClipNote,
    onClipboard: Boolean,
    onCopy: () -> Unit,
) {
    val mine = note.from == ClipNote.DESKTOP
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 140.dp, max = 340.dp),
            shape = RoundedCornerShape(16.dp),
            color = if (mine) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${if (mine) "This PC" else "Browser"} · ${clipTime(note.sentAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onCopy, modifier = Modifier.size(22.dp)) {
                        Icon(
                            DesktopIcons.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(14.dp),
                            tint = if (onClipboard) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                if (note.text.isNotEmpty()) {
                    Text(
                        text = note.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                note.image?.let { image ->
                    ClipImage(image)
                }
            }
        }
    }
}

@Composable
private fun ClipImage(image: String) {
    val bitmap = remember(image) {
        runCatching {
            SkiaImage.makeFromEncoded(Base64.getDecoder().decode(image)).toComposeImageBitmap()
        }.getOrNull()
    } ?: return
    Image(
        bitmap = bitmap,
        contentDescription = "Clipboard image",
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 180.dp),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun BoxSend(enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Icon(
            imageVector = DesktopIcons.Send,
            contentDescription = "Send",
            modifier = Modifier.size(18.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun clipUrl(host: String, port: Int): String = "https://$host:$port${RemoteProtocol.CLIP_PATH}"

private fun clipTime(epochMillis: Long): String {
    val time = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalTime()
    return "%02d:%02d".format(time.hour, time.minute)
}
