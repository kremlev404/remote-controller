package com.remotecontroller.client.presentation.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.remotecontroller.client.domain.model.SavedConnection

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavedServersSection(
    servers: List<SavedConnection>,
    selectedHost: String,
    selectedPort: String,
    onSelect: (SavedConnection) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (servers.isEmpty()) return

    Text(
        text = "Saved servers",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 8.dp),
    )

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        servers.forEach { server ->
            val selected = server.host == selectedHost.trim() && server.portText == selectedPort.trim()
            FilterChip(
                selected = selected,
                onClick = { onSelect(server) },
                label = {
                    Text(
                        text = server.endpoint,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}
