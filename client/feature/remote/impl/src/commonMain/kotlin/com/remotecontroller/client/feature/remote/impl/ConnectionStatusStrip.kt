package com.remotecontroller.client.feature.remote.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.remotecontroller.client.domain.model.ConnectionState

@Composable
fun ConnectionStatusStrip(
    connectionState: ConnectionState,
    isWifiConnected: Boolean,
    serverHint: String,
    serverEndpoint: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val color = connectionState.statusColor()
    val endpoint = serverEndpoint?.takeIf { it.isNotBlank() }
    val label = when {
        !isWifiConnected -> "Wi-Fi off"
        else -> connectionState.statusLabel()
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 8.dp else 10.dp)
                .background(color, CircleShape),
        )
        if (isWifiConnected && connectionState == ConnectionState.Connected && endpoint != null) {
            Text(
                text = serverIdentity(
                    hint = serverHint,
                    endpoint = endpoint,
                    slash = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = label,
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

private fun ConnectionState.statusLabel(): String = when (this) {
    ConnectionState.Connected -> "Connected"
    ConnectionState.Connecting -> "Connecting"
    ConnectionState.Reconnecting -> "Reconnecting"
    ConnectionState.Disconnected -> "Disconnected"
    ConnectionState.Failed -> "Connection failed"
}

@Composable
private fun ConnectionState.statusColor(): Color = when (this) {
    ConnectionState.Connected -> MaterialTheme.colorScheme.primary
    ConnectionState.Connecting, ConnectionState.Reconnecting -> Color(0xFFF9A825)
    ConnectionState.Disconnected -> MaterialTheme.colorScheme.outline
    ConnectionState.Failed -> MaterialTheme.colorScheme.error
}
