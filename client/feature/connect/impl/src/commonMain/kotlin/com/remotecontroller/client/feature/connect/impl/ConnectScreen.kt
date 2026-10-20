package com.remotecontroller.client.feature.connect.impl

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.remotecontroller.client.domain.model.ConnectionState
import com.remotecontroller.shared.model.RemoteKeys
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ConnectViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val requestLocalNetworkAccess = rememberLocalNetworkPermissionController(
        permission = viewModel.localNetworkPermission,
        isGranted = viewModel::isLocalNetworkGranted,
        onResult = { granted, thenConnect ->
            viewModel.onLocalNetworkAccess(granted)
            if (granted && thenConnect) viewModel.connect()
        },
    )

    LaunchedEffect(Unit) {
        requestLocalNetworkAccess.request(thenConnect = false)
    }

    LaunchedEffect(uiState.connectionState, uiState.pendingStartupNavigation) {
        when (uiState.connectionState) {
            ConnectionState.Connected -> onConnected()
            ConnectionState.Connecting, ConnectionState.Reconnecting -> {
                if (uiState.pendingStartupNavigation) onConnected()
            }
            ConnectionState.Disconnected, ConnectionState.Failed -> Unit
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.End)
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = "Remote",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Connect to the laptop",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Same Wi-Fi. Use the address on the laptop screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (uiState.savedServers.isNotEmpty()) {
                SavedServersSection(
                    servers = uiState.savedServers,
                    selectedHost = uiState.host,
                    selectedPort = uiState.port,
                    onSelect = viewModel::selectSavedServer,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = uiState.hint,
                onValueChange = viewModel::onHintChange,
                label = { Text("Hint") },
                placeholder = { Text("Living room") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = uiState.host,
                    onValueChange = viewModel::onHostChange,
                    label = { Text("Address") },
                    placeholder = { Text("192.168.1.20") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = uiState.port,
                    onValueChange = viewModel::onPortChange,
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.width(108.dp),
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            if (uiState.needsLocalNetworkPermission) {
                Text(
                    text = localNetworkPermissionHint(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = {
                    if (viewModel.isLocalNetworkGranted()) {
                        viewModel.connect()
                    } else {
                        requestLocalNetworkAccess.request(thenConnect = true)
                    }
                },
                enabled = !uiState.isLoading && !uiState.isConnected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        text = if (uiState.needsLocalNetworkPermission) "Allow local network" else "Connect",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Leave the laptop app open. The usual port is ${RemoteKeys.DEFAULT_PORT}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
