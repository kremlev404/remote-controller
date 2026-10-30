package com.remotecontroller.client.feature.remote.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.remotecontroller.client.domain.model.ConnectionState
import com.remotecontroller.shared.model.RemoteKeys
import org.koin.compose.viewmodel.koinViewModel

private enum class RemotePage(val label: String) {
    Remote("Remote"),
    Keys("Keys"),
}

@Composable
fun RemoteScreen(
    onDisconnected: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScreen: () -> Unit,
    viewModel: RemoteViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var hadConnection by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(RemotePage.Remote) }

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

    val needsAttention = uiState.connectionState != ConnectionState.Connected || uiState.errorMessage != null
    val attentionIsError = uiState.errorMessage != null || uiState.connectionState == ConnectionState.Failed

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(
                connectionState = uiState.connectionState,
                isWifiConnected = uiState.networkStatus.isWifi,
                serverHint = uiState.serverHint,
                serverEndpoint = uiState.serverEndpoint,
                onOpenScreen = onOpenScreen,
                onOpenSettings = onOpenSettings,
                onDisconnect = { viewModel.disconnect(onDisconnected) },
            )

            if (needsAttention) {
                AttentionBanner(
                    message = uiState.errorMessage ?: uiState.connectionState.statusText(),
                    isError = attentionIsError,
                    retryEnabled = !uiState.isSending,
                    onRetry = viewModel::retryConnection,
                )
            }

            PageSwitch(
                selected = page,
                onSelect = { page = it },
            )

            when (page) {
                RemotePage.Remote -> RemotePad(
                    volumeLevel = uiState.volumeLevel,
                    isMuted = uiState.isMuted,
                    onVolumeChange = viewModel::setVolume,
                    onVolumeUp = viewModel::volumeUp,
                    onVolumeDown = viewModel::volumeDown,
                    onToggleMute = viewModel::toggleMute,
                    onMove = viewModel::mouseMove,
                    onClick = viewModel::mouseClick,
                    onScroll = viewModel::mouseScroll,
                    onPress = viewModel::pressKey,
                    pointerScale = uiState.pointerScale,
                    modifier = Modifier.weight(1f),
                )
                RemotePage.Keys -> KeyPad(
                    onKeyPress = viewModel::pressKey,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Header(
    connectionState: ConnectionState,
    isWifiConnected: Boolean,
    serverHint: String,
    serverEndpoint: String?,
    onOpenScreen: () -> Unit,
    onOpenSettings: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConnectionStatusStrip(
            connectionState = connectionState,
            isWifiConnected = isWifiConnected,
            serverHint = serverHint,
            serverEndpoint = serverEndpoint,
            compact = true,
            modifier = Modifier.weight(1f),
        )
        RoundIconButton(
            icon = Icons.Default.DesktopWindows,
            contentDescription = "Screen",
            onClick = onOpenScreen,
        )
        RoundIconButton(
            icon = Icons.Default.Settings,
            contentDescription = "Settings",
            onClick = onOpenSettings,
        )
        RoundIconButton(
            icon = Icons.Default.LinkOff,
            contentDescription = "Disconnect",
            onClick = onDisconnect,
        )
    }
}

@Composable
private fun AttentionBanner(
    message: String,
    isError: Boolean,
    retryEnabled: Boolean,
    onRetry: () -> Unit,
) {
    val background = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .background(background)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = content,
        )
        Text(
            text = "Retry",
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = retryEnabled, onClick = onRetry)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = content,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PageSwitch(
    selected: RemotePage,
    onSelect: (RemotePage) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
    ) {
        RemotePage.entries.forEach { page ->
            val active = page == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onSelect(page) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = page.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun RemotePad(
    volumeLevel: Int,
    isMuted: Boolean,
    onVolumeChange: (Int) -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onToggleMute: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onClick: (String) -> Unit,
    onScroll: (Int) -> Unit,
    onPress: (String) -> Unit,
    pointerScale: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VolumeBar(
            volumeLevel = volumeLevel,
            isMuted = isMuted,
            onVolumeChange = onVolumeChange,
            onVolumeUp = onVolumeUp,
            onVolumeDown = onVolumeDown,
            onToggleMute = onToggleMute,
        )
        MediaBar(onKeyPress = onPress)
        MousePadSection(
            sensitivity = pointerScale,
            onMove = onMove,
            onClick = onClick,
            onPress = onPress,
            onScroll = onScroll,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeBar(
    volumeLevel: Int,
    isMuted: Boolean,
    onVolumeChange: (Int) -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onToggleMute: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = if (isMuted) "Muted" else "$volumeLevel%",
            modifier = Modifier.align(Alignment.End),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RoundIconButton(
                icon = Icons.AutoMirrored.Filled.VolumeDown,
                contentDescription = "Volume down",
                onClick = onVolumeDown,
            )
            val sliderColors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outline,
            )
            Slider(
                value = volumeLevel.toFloat(),
                onValueChange = { onVolumeChange(it.toInt()) },
                valueRange = 0f..100f,
                modifier = Modifier.weight(1f),
                colors = sliderColors,
                thumb = {
                    SliderDefaults.Thumb(
                        interactionSource = remember { MutableInteractionSource() },
                        colors = sliderColors,
                        thumbSize = DpSize(4.dp, 18.dp),
                    )
                },
                track = { state ->
                    SliderDefaults.Track(
                        sliderState = state,
                        colors = sliderColors,
                        thumbTrackGapSize = 0.dp,
                        trackInsideCornerSize = 2.dp,
                        drawStopIndicator = null,
                    )
                },
            )
            RoundIconButton(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "Volume up",
                onClick = onVolumeUp,
            )
            RoundIconButton(
                icon = Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = "Mute",
                onClick = onToggleMute,
                container = if (isMuted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                bordered = !isMuted,
                tint = if (isMuted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun MediaBar(onKeyPress: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaButton(Icons.Default.SkipPrevious, "Previous") { onKeyPress("PREVIOUS") }
        MediaButton(
            icon = Icons.Default.Pause,
            contentDescription = "Pause",
            primary = false,
            onClick = { onKeyPress("PLAY_PAUSE") },
        )
        MediaButton(
            icon = Icons.Default.PlayArrow,
            contentDescription = "Play",
            primary = true,
            onClick = { onKeyPress("PLAY_PAUSE") },
        )
        MediaButton(Icons.Default.SkipNext, "Next") { onKeyPress("NEXT") }
        MediaButton(Icons.Default.Stop, "Stop") { onKeyPress("STOP") }
    }
}

@Composable
private fun MediaButton(
    icon: ImageVector,
    contentDescription: String,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val size = if (primary) 64.dp else 48.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (primary) {
                    Modifier
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                },
            )
            .background(
                if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(if (primary) 32.dp else 24.dp),
        )
    }
}

@Composable
private fun KeyPad(
    onKeyPress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DirectionPad(onKeyPress = onKeyPress)
        KeyGrid(
            keys = RemoteKeys.COMMON_KEYS,
            columns = 3,
            onKeyPress = onKeyPress,
        )
        KeyGrid(
            keys = RemoteKeys.FUNCTION_KEYS,
            columns = 4,
            onKeyPress = onKeyPress,
        )
    }
}

@Composable
private fun DirectionPad(onKeyPress: (String) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ArrowButton(Icons.Default.KeyboardArrowUp, "Up") { onKeyPress("UP") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArrowButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left") { onKeyPress("LEFT") }
            Box(modifier = Modifier.size(56.dp))
            ArrowButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right") { onKeyPress("RIGHT") }
        }
        ArrowButton(Icons.Default.KeyboardArrowDown, "Down") { onKeyPress("DOWN") }
    }
}

@Composable
private fun ArrowButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun KeyGrid(
    keys: List<String>,
    columns: Int,
    onKeyPress: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        keys.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { key ->
                    RemoteKey(
                        label = key.remoteLabel(),
                        onClick = { onKeyPress(key) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - row.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    container: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    bordered: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(
                if (bordered) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                } else {
                    Modifier
                },
            )
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun ConnectionState.statusText(): String = when (this) {
    ConnectionState.Connected -> "Connected"
    ConnectionState.Connecting -> "Connecting"
    ConnectionState.Reconnecting -> "Reconnecting"
    ConnectionState.Disconnected -> "Disconnected"
    ConnectionState.Failed -> "Connection failed"
}
