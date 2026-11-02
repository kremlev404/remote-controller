package com.remotecontroller.web.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.remotecontroller.client.domain.repository.ClipSnapshot
import com.remotecontroller.shared.model.ClipNote
import kotlin.io.encoding.Base64
import org.jetbrains.skia.Image as SkiaImage

@Composable
internal fun ChatPane(
    clip: ClipSnapshot,
    viewModel: PageViewModel,
    modifier: Modifier = Modifier,
) {
    val notes = clip.notes
    var draft by remember { mutableStateOf("") }
    val send = {
        viewModel.send(draft)
        draft = ""
    }
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
            text = if (clip.connected) "Connected" else "Connecting…",
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        clip.banner?.let { banner ->
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
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f).sendOnEnter(send),
                placeholder = { Text("Message") },
                maxLines = 4,
                shape = RoundedCornerShape(16.dp),
            )
            IconButton(
                onClick = viewModel::clearHistory,
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
                onClick = send,
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
