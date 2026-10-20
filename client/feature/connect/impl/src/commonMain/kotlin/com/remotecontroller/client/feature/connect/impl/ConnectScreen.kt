package com.remotecontroller.client.feature.connect.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.remotecontroller.client.domain.model.ConnectionState
import com.remotecontroller.shared.model.RemoteKeys
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
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

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Computer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.height(64.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Remote Controller",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = "Connect to your Windows PC over Wi-Fi",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (uiState.savedServers.isNotEmpty()) {
                SavedServersSection(
                    servers = uiState.savedServers,
                    selectedHost = uiState.host,
                    selectedPort = uiState.port,
                    onSelect = viewModel::selectSavedServer,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            OutlinedTextField(
                value = uiState.host,
                onValueChange = viewModel::onHostChange,
                label = { Text("PC IP Address") },
                placeholder = { Text("192.168.1.100") },
                leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.port,
                onValueChange = viewModel::onPortChange,
                label = { Text("Port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            if (uiState.needsLocalNetworkPermission) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = localNetworkPermissionHint(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (uiState.errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    if (viewModel.isLocalNetworkGranted()) {
                        viewModel.connect()
                    } else {
                        requestLocalNetworkAccess.request(thenConnect = true)
                    }
                },
                enabled = !uiState.isLoading && !uiState.isConnected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                } else {
                    Text(if (uiState.needsLocalNetworkPermission) "Allow local network" else "Connect")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Start the PC app first. Use the IP shown on the server screen (port ${RemoteKeys.DEFAULT_PORT}).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
