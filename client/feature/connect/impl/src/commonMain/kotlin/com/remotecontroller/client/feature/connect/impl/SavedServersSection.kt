package com.remotecontroller.client.feature.connect.impl

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.remotecontroller.client.domain.model.SavedConnection

@Composable
fun SavedServersSection(
    servers: List<SavedConnection>,
    selectedHost: String,
    selectedPort: String,
    onSelect: (SavedConnection) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (servers.isEmpty()) return

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Saved",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        servers.forEach { server ->
            val selected = server.host == selectedHost.trim() && server.portText == selectedPort.trim()
            val shape = RoundedCornerShape(16.dp)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .border(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = shape,
                    )
                    .clickable { onSelect(server) },
                shape = shape,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Text(
                    text = serverIdentity(
                        hint = server.hint,
                        endpoint = server.endpoint,
                        slash = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun serverIdentity(hint: String, endpoint: String, slash: Color): AnnotatedString =
    buildAnnotatedString {
        if (hint.isNotBlank()) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(hint)
            }
            withStyle(SpanStyle(color = slash, fontWeight = FontWeight.Medium)) {
                append(" / ")
            }
        }
        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)) {
            append(endpoint)
        }
    }
