package com.remotecontroller.desktop.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remotecontroller.desktop.server.CommandServer
import com.remotecontroller.desktop.server.ServerLog
import com.remotecontroller.desktop.server.ServerLogLine
import com.remotecontroller.desktop.startup.StartupManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DesktopUiState(
    val server: DesktopServerState = initialDesktopServerState(),
    val volumeLevel: Int = 0,
    val isMuted: Boolean = false,
    val lastCommand: String? = null,
    val launchAtStartup: Boolean = false,
    val startupAvailable: Boolean = false,
    val startupTitle: String = "",
    val startupEnabledHint: String = "",
    val startupUnavailableHint: String = "",
    val startupError: String? = null,
    val firewallRequestInProgress: Boolean = false,
    val logs: List<ServerLogLine> = emptyList(),
    val logLimit: Int = 5,
)

class DesktopViewModel(
    private val commandServer: CommandServer,
    private val startupManager: StartupManager,
) : ViewModel() {

    val uiState: StateFlow<DesktopUiState>
        field = MutableStateFlow(DesktopUiState())

    init {
        uiState.value = DesktopUiState(
            server = commandServer.runtimeStatus.value.toDesktopServerState(),
            launchAtStartup = startupManager.isEnabled(),
            startupAvailable = startupManager.canConfigure(),
            startupTitle = startupManager.title,
            startupEnabledHint = startupManager.enabledDescription,
            startupUnavailableHint = startupManager.unavailableDescription,
        )

        viewModelScope.launch {
            commandServer.runtimeStatus.collect { runtime ->
                uiState.update { current ->
                    current.copy(server = runtime.toDesktopServerState())
                }
            }
        }

        viewModelScope.launch {
            ServerLog.lines.collect { lines ->
                uiState.update { current -> current.copy(logs = lines) }
            }
        }

        viewModelScope.launch {
            commandServer.status.collect { serverStatus ->
                uiState.update { current ->
                    current.copy(
                        volumeLevel = serverStatus.volumeLevel,
                        isMuted = serverStatus.isMuted,
                        lastCommand = serverStatus.lastCommand,
                    )
                }
            }
        }
    }

    fun restartServer() {
        if (uiState.value.server.restartInProgress) return
        viewModelScope.launch {
            commandServer.restart()
        }
    }

    fun disconnectClient(id: Long) {
        viewModelScope.launch {
            commandServer.disconnectClient(id)
        }
    }

    fun allowFirewall() {
        if (uiState.value.firewallRequestInProgress) return
        uiState.update { it.copy(firewallRequestInProgress = true) }
        viewModelScope.launch {
            try {
                commandServer.requestFirewallAccess()
            } finally {
                uiState.update { it.copy(firewallRequestInProgress = false) }
            }
        }
    }

    fun setLogLimit(limit: Int) {
        if (limit !in LOG_LIMITS) return
        uiState.update { it.copy(logLimit = limit) }
    }

    fun setLaunchAtStartup(enabled: Boolean) {
        if (!startupManager.canConfigure()) return

        runCatching {
            startupManager.setEnabled(enabled)
        }.onSuccess {
            uiState.update {
                it.copy(launchAtStartup = enabled, startupError = null)
            }
        }.onFailure { error ->
            uiState.update {
                it.copy(startupError = error.message ?: "Failed to update startup setting")
            }
        }
    }

    private companion object {
        val LOG_LIMITS = setOf(1, 5, 10)
    }
}
