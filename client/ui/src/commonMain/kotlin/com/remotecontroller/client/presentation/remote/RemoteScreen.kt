package com.remotecontroller.client.presentation.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.remotecontroller.client.domain.model.ConnectionState
import com.remotecontroller.shared.model.RemoteKeys
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RemoteScreen(
    onDisconnected: () -> Unit,
    viewModel: RemoteViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var hadConnection by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.connectionState) {
        when (uiState.connectionState) {
            ConnectionState.Connected -> hadConnection = true
            ConnectionState.Failed -> Unit
            ConnectionState.Disconnected -> {
                if (hadConnection) onDisconnected()
            }
            ConnectionState.Connecting, ConnectionState.Reconnecting -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    ConnectionStatusStrip(
                        connectionState = uiState.connectionState,
                        isWifiConnected = uiState.networkStatus.isWifi,
                        pcHost = uiState.pcHost,
                        compact = true,
                    )
                },
                actions = {
                    IconButton(onClick = { viewModel.disconnect(onDisconnected) }) {
                        Icon(Icons.Default.LinkOff, contentDescription = "Disconnect")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (uiState.connectionState != ConnectionState.Connected || uiState.errorMessage != null) {
                Button(
                    onClick = viewModel::retryConnection,
                    enabled = !uiState.isSending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Retry now")
                }
            }

            VolumeSection(
                volumeLevel = uiState.volumeLevel,
                isMuted = uiState.isMuted,
                onVolumeChange = viewModel::setVolume,
                onVolumeUp = viewModel::volumeUp,
                onVolumeDown = viewModel::volumeDown,
                onToggleMute = viewModel::toggleMute,
            )

            MousePadSection(
                onMove = viewModel::mouseMove,
                onClick = viewModel::mouseClick,
                onScroll = viewModel::mouseScroll,
                onPress = viewModel::pressKey,
            )

            MediaSection(onKeyPress = viewModel::pressKey)

            KeySection(
                title = "Navigation",
                keys = RemoteKeys.ARROW_KEYS + RemoteKeys.COMMON_KEYS,
                onKeyPress = viewModel::pressKey,
            )

            KeySection(
                title = "Function Keys",
                keys = RemoteKeys.FUNCTION_KEYS,
                onKeyPress = viewModel::pressKey,
            )

            if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun VolumeSection(
    volumeLevel: Int,
    isMuted: Boolean,
    onVolumeChange: (Int) -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onToggleMute: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Volume",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onVolumeDown) {
                    Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = "Volume down")
                }

                Text(
                    text = if (isMuted) "Muted" else "$volumeLevel%",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )

                IconButton(onClick = onVolumeUp) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Volume up")
                }

                IconButton(onClick = onToggleMute) {
                    Icon(Icons.Default.VolumeOff, contentDescription = "Mute toggle")
                }
            }

            Slider(
                value = volumeLevel.toFloat(),
                onValueChange = { onVolumeChange(it.toInt()) },
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaSection(onKeyPress: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Media",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onKeyPress("PREVIOUS") }) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = null)
                    Text("Prev")
                }
                FilledTonalButton(onClick = { onKeyPress("PLAY_PAUSE") }) {
                    Text("Play/Pause")
                }
                FilledTonalButton(onClick = { onKeyPress("NEXT") }) {
                    Icon(Icons.Default.SkipNext, contentDescription = null)
                    Text("Next")
                }
                FilledTonalButton(onClick = { onKeyPress("STOP") }) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text("Stop")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeySection(
    title: String,
    keys: List<String>,
    onKeyPress: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                keys.forEach { key ->
                    FilledTonalButton(onClick = { onKeyPress(key) }) {
                        Text(key.replace("_", " "))
                    }
                }
            }
        }
    }
}
