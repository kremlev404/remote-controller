package com.remotecontroller.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remotecontroller.desktop.executor.SharedDisplay
import com.remotecontroller.desktop.server.ApprovalRequest
import com.remotecontroller.desktop.server.ConnectedClient
import com.remotecontroller.desktop.server.RelayState
import com.remotecontroller.shared.network.RelayProtocol
import com.remotecontroller.desktop.AppVersion
import com.remotecontroller.desktop.server.ServerLogLine
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DesktopApp(viewModel: DesktopViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    DesktopTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeaderSection(server = uiState.server)

                ServerStatusCard(
                    server = uiState.server,
                    firewallRequestInProgress = uiState.firewallRequestInProgress,
                    onRestart = viewModel::restartServer,
                    onDisconnectClient = viewModel::disconnectClient,
                    onAllowFirewall = viewModel::allowFirewall,
                    displays = uiState.displays,
                    selectedDisplayId = uiState.selectedDisplayId,
                    framesPerSecond = uiState.streamFps,
                    viewers = uiState.streamViewers,
                    paused = uiState.streamPaused,
                    streamError = uiState.streamError,
                    onSelectDisplay = viewModel::selectDisplay,
                    onStartSharing = viewModel::resumeSharing,
                    onStopSharing = viewModel::pauseSharing,
                    onRestartSharing = viewModel::restartSharing,
                    onGrantScreen = viewModel::grantScreenAccess,
                    canGrantScreen = viewModel.canGrantScreenAccess(),
                    pairingCode = viewModel.pairingCode,
                    requireCode = uiState.requireCode,
                    onRequireCode = viewModel::setRequireCode,
                    certificateFingerprint = viewModel.certificateFingerprint,
                    onTrustCertificate = viewModel::trustCertificate,
                )

                RelayCard(
                    enabled = uiState.relayEnabled,
                    url = uiState.relayUrl,
                    state = uiState.relayState,
                    requireCode = uiState.requireCode,
                    onEnabledChange = viewModel::setRelayEnabled,
                    onUrlChange = viewModel::setRelayUrl,
                )

                ClipboardCard(
                    notes = uiState.clipNotes,
                    sharing = uiState.shareClipboard,
                    port = uiState.server.port,
                    recommendedAddress = uiState.server.recommendedAddress,
                    errorMessage = uiState.clipError,
                    onClipboardId = uiState.onClipboardId,
                    sentCount = uiState.clipSentCount,
                    onSend = viewModel::sendClip,
                    onSendClipboard = viewModel::sendSystemClipboard,
                    onCopy = viewModel::copyClip,
                    onClear = viewModel::clearClipHistory,
                    onSharingChange = viewModel::setShareClipboard,
                    onOpenPage = viewModel::openClipPage,
                )

                StatusCard(uiState)

                ConnectionCard(
                    addresses = uiState.server.addresses,
                    recommendedAddress = uiState.server.recommendedAddress,
                    port = uiState.server.port,
                )

                LastCommandCard(uiState.lastCommand)

                StartupCard(
                    title = uiState.startupTitle,
                    enabledHint = uiState.startupEnabledHint,
                    unavailableHint = uiState.startupUnavailableHint,
                    enabled = uiState.launchAtStartup,
                    available = uiState.startupAvailable,
                    errorMessage = uiState.startupError,
                    onEnabledChange = viewModel::setLaunchAtStartup,
                )

                ConnectionLogCard(
                    lines = uiState.logs,
                    limit = uiState.logLimit,
                    onLimitChange = viewModel::setLogLimit,
                )
            }
            uiState.approvals.firstOrNull()?.let { request ->
                ApprovalDialog(
                    request = request,
                    waiting = uiState.approvals.size - 1,
                    onAnswer = viewModel::answerApproval,
                )
            }
        }
    }
}

@Composable
private fun ApprovalDialog(
    request: ApprovalRequest,
    waiting: Int,
    onAnswer: (Long, Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAnswer(request.id, false) },
        title = { Text("Allow ${request.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (request.viaRelay) {
                        "It asks through the relay, from ${request.address}. It will control this PC and see its screen."
                    } else {
                        "It asks from ${request.address} on this network. It will control this PC and see its screen."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = request.checkCode,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "The device shows the same number. If it does not, deny.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (waiting > 0) {
                    Text(
                        text = "$waiting more waiting",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAnswer(request.id, true) }) { Text("Allow") }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(request.id, false) }) { Text("Deny") }
        },
    )
}

@Composable
private fun RelayCard(
    enabled: Boolean,
    url: String,
    state: RelayState,
    requireCode: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onUrlChange: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Reach from anywhere",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Through a relay with a real certificate: no browser warning, no port forwarding.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            var draft by remember(url) { mutableStateOf(url) }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Relay address") },
                placeholder = { Text("https://relay.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (draft.trim().trimEnd('/') != url) {
                        TextButton(onClick = { onUrlChange(draft) }) { Text("Save") }
                    }
                },
            )
            if (enabled) {
                when (state) {
                    RelayState.Off -> Text(
                        text = "Add the relay address to go online.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RelayState.Connecting -> Text(
                        text = "Connecting to the relay…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is RelayState.Online -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "This PC's ID",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelectionContainer {
                            Text(
                                text = RelayProtocol.displayId(state.id),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        SelectionContainer {
                            Text(
                                text = "${url.trimEnd('/')}/#id=${state.id}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            text = if (requireCode) {
                                "Open that page anywhere, then type the access code."
                            } else {
                                "Open that page anywhere. You will be asked to allow each device."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is RelayState.Failed -> Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderSection(server: DesktopServerState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = DesktopIcons.Computer,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column {
            Text(
                text = "${AppVersion.NAME} ${AppVersion.VERSION}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = server.headline,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ServerStatusCard(
    server: DesktopServerState,
    firewallRequestInProgress: Boolean,
    onRestart: () -> Unit,
    onDisconnectClient: (Long) -> Unit,
    onAllowFirewall: () -> Unit,
    displays: List<SharedDisplay>,
    selectedDisplayId: String,
    framesPerSecond: Int,
    viewers: Int,
    paused: Boolean,
    streamError: String?,
    onSelectDisplay: (String) -> Unit,
    onStartSharing: () -> Unit,
    onStopSharing: () -> Unit,
    onRestartSharing: () -> Unit,
    onGrantScreen: () -> Unit,
    canGrantScreen: Boolean,
    pairingCode: String,
    requireCode: Boolean,
    onRequireCode: (Boolean) -> Unit,
    certificateFingerprint: String,
    onTrustCertificate: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Server Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                RestartButton(
                    restarting = server.restartInProgress,
                    onClick = onRestart,
                )
            }

            StatusRow(
                label = "Ktor",
                value = server.ktorLabel,
                color = server.ktorColor(),
            )
            StatusRow(
                label = "Network",
                value = server.networkLabel,
                color = server.networkColor(),
                icon = if (server.networkAvailable) DesktopIcons.Wifi else DesktopIcons.WifiOff,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Require access code",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = requireCode,
                    onCheckedChange = onRequireCode,
                )
            }
            if (requireCode) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Access code",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(
                            text = pairingCode,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = "Type this on the phone or in the browser. It is created when this window starts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = "No code: every new device asks first, and you press Allow here after checking its number.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            var checkCertificate by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Certificate",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = checkCertificate,
                    onCheckedChange = { checkCertificate = it },
                )
            }
            if (checkCertificate) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SelectionContainer {
                        Text(
                            text = certificateFingerprint,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = "Paste this fingerprint on the phone, with the same switch on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onTrustCertificate) {
                        Text("Trust this computer")
                    }
                }
            }
            ClientsRow(
                clients = server.clients,
                onDisconnect = onDisconnectClient,
            )
            ScreenShareRow(
                displays = displays,
                selectedDisplayId = selectedDisplayId,
                framesPerSecond = framesPerSecond,
                viewers = viewers,
                paused = paused,
                errorMessage = streamError,
                onSelect = onSelectDisplay,
                onStart = onStartSharing,
                onStop = onStopSharing,
                onRestart = onRestartSharing,
                onGrant = onGrantScreen,
                canGrant = canGrantScreen,
            )

            server.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            server.warning?.let { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(
                    onClick = onAllowFirewall,
                    enabled = !firewallRequestInProgress,
                ) {
                    Text(if (firewallRequestInProgress) "Waiting for Windows…" else "Allow again")
                }
            }

            if (server is DesktopServerState.Running && server.restartCount > 0) {
                Text(
                    text = "Auto-recovered ${server.restartCount} time(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RestartButton(
    restarting: Boolean,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        FilledTonalButton(
            onClick = onClick,
            enabled = !restarting,
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            Icon(
                imageVector = DesktopIcons.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (restarting) "Restarting" else "Restart",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun ClientsRow(
    clients: List<ConnectedClient>,
    onDisconnect: (Long) -> Unit,
) {
    val connected = clients.isNotEmpty()
    val color = if (connected) statusOkColor() else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape),
        )
        Text(
            text = "Clients",
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Icon(
            imageVector = if (connected) DesktopIcons.Link else DesktopIcons.LinkOff,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = color,
        )
        if (!connected) {
            Text(
                text = "Disconnected",
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
        } else {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                clients.forEach { client ->
                    ClientChip(
                        client = client,
                        onDisconnect = onDisconnect,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClientChip(
    client: ConnectedClient,
    onDisconnect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = statusOkColor()
    Surface(
        modifier = modifier.height(26.dp),
        shape = RoundedCornerShape(13.dp),
        color = color.copy(alpha = 0.16f),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = client.name,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                IconButton(
                    onClick = { onDisconnect(client.id) },
                    modifier = Modifier.size(18.dp),
                ) {
                    Icon(
                        imageVector = DesktopIcons.Close,
                        contentDescription = "Disconnect ${client.name}",
                        modifier = Modifier.size(12.dp),
                        tint = color,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape),
        )
        Text(
            text = label,
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = color,
            )
        }
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

@Composable
private fun StatusCard(uiState: DesktopUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(DesktopIcons.VolumeUp, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("System Volume")
                }
                Text(
                    text = buildString {
                        append("${uiState.volumeLevel}%")
                        if (uiState.isMuted) append(" (muted)")
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    addresses: List<String>,
    recommendedAddress: String?,
    port: Int,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Connect from Android",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "On the same Wi-Fi, open Remote Controller on your phone and enter the PC address below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (addresses.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = DesktopIcons.WifiOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = "No Wi-Fi address found. Connect this PC to your local network.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            } else {
                recommendedAddress?.let { recommended ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Recommended address",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "$recommended:$port",
                                style = MaterialTheme.typography.headlineSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                val otherAddresses = addresses.filter { it != recommendedAddress }
                if (otherAddresses.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Other network adapters",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        otherAddresses.forEach { address ->
                            Text(
                                text = "$address:$port",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = DesktopIcons.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Phone and PC must be on the same Wi-Fi network",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScreenShareRow(
    displays: List<SharedDisplay>,
    selectedDisplayId: String,
    framesPerSecond: Int,
    viewers: Int,
    paused: Boolean,
    errorMessage: String?,
    onSelect: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onGrant: () -> Unit,
    canGrant: Boolean,
) {
    val status = when {
        errorMessage != null -> errorMessage
        paused && viewers > 0 -> "Stopped · $viewers waiting"
        paused -> "Stopped"
        viewers <= 0 -> "Idle"
        framesPerSecond <= 0 -> "Starting"
        viewers == 1 -> "$framesPerSecond fps · 1 viewer"
        else -> "$framesPerSecond fps · $viewers viewers"
    }
    val needsAccess = canGrant && (errorMessage != null || (!paused && viewers > 0 && framesPerSecond <= 0))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Screen",
                modifier = Modifier.width(64.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = status,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (errorMessage != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = onStart, enabled = paused) { Text("Start") }
            OutlinedButton(onClick = onStop, enabled = !paused) { Text("Stop") }
            OutlinedButton(onClick = onRestart) { Text("Restart") }
            if (needsAccess) {
                TextButton(onClick = onGrant) { Text("Allow access") }
            }
        }
        if (displays.size > 1) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                displays.forEach { display ->
                    FilterChip(
                        selected = display.id == selectedDisplayId,
                        onClick = { onSelect(display.id) },
                        label = { Text(display.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StartupCard(
    title: String,
    enabledHint: String,
    unavailableHint: String,
    enabled: Boolean,
    available: Boolean,
    errorMessage: String?,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = available) { onEnabledChange(!enabled) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    imageVector = DesktopIcons.PowerSettingsNew,
                    contentDescription = null,
                    tint = if (enabled && available) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = if (available) enabledHint else unavailableHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = available,
                )
            }

            if (errorMessage != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    text = errorMessage,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ConnectionLogCard(
    lines: List<ServerLogLine>,
    limit: Int,
    onLimitChange: (Int) -> Unit,
) {
    val shown = lines.takeLast(limit)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Connection log",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 5, 10).forEach { count ->
                        FilterChip(
                            selected = limit == count,
                            onClick = { onLimitChange(count) },
                            label = { Text(count.toString()) },
                        )
                    }
                }
            }
            if (shown.isEmpty()) {
                Text(
                    text = "No connection logs yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                shown.forEach { line ->
                    Text(
                        text = "${line.time}  ${line.message}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (line.error) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LastCommandCard(command: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Last Command",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            command?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun DesktopServerState.ktorColor(): Color = when (this) {
    is DesktopServerState.Running -> statusOkColor()
    is DesktopServerState.Starting,
    is DesktopServerState.Restarting,
    is DesktopServerState.Recovering,
    -> statusPendingColor()
    is DesktopServerState.Stopped -> MaterialTheme.colorScheme.onSurfaceVariant
    is DesktopServerState.Failed -> MaterialTheme.colorScheme.error
}

@Composable
private fun DesktopServerState.networkColor(): Color = when (this) {
    is DesktopServerState.Starting -> MaterialTheme.colorScheme.outline
    else -> if (networkAvailable) statusOkColor() else MaterialTheme.colorScheme.error
}
