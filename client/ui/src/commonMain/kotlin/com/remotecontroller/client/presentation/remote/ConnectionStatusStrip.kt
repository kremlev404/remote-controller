package com.remotecontroller.client.presentation.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remotecontroller.client.domain.model.ConnectionState

@Composable
fun ConnectionStatusStrip(
    connectionState: ConnectionState,
    isWifiConnected: Boolean,
    pcHost: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 8.dp else 10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (compact) 0.35f else 0.55f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (compact) 8.dp else 10.dp,
                    vertical = if (compact) 4.dp else 6.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(
                label = "PC",
                value = connectionState.shortLabel(),
                color = connectionState.statusColor(),
            )
            StatusChip(
                label = "Wi‑Fi",
                value = if (isWifiConnected) "OK" else "Off",
                color = if (isWifiConnected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            )
            if (!pcHost.isNullOrBlank() && connectionState != ConnectionState.Disconnected) {
                Text(
                    text = pcHost,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(
    label: String,
    value: String,
    color: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color, CircleShape),
        )
        Text(
            text = "$label·$value",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private fun ConnectionState.shortLabel(): String = when (this) {
    ConnectionState.Connected -> "OK"
    ConnectionState.Connecting -> "…"
    ConnectionState.Reconnecting -> "↻"
    ConnectionState.Disconnected -> "Off"
    ConnectionState.Failed -> "Err"
}

@Composable
private fun ConnectionState.statusColor(): Color = when (this) {
    ConnectionState.Connected -> Color(0xFF2E7D32)
    ConnectionState.Connecting, ConnectionState.Reconnecting -> Color(0xFFF9A825)
    ConnectionState.Disconnected -> MaterialTheme.colorScheme.outline
    ConnectionState.Failed -> MaterialTheme.colorScheme.error
}
