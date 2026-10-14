package com.remotecontroller.desktop.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remotecontroller.desktop.server.CommandServer
import com.remotecontroller.desktop.server.KtorServerState
import com.remotecontroller.desktop.server.ServerRuntimeStatus
import com.remotecontroller.desktop.startup.StartupManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DesktopUiState(
    val runtime: ServerRuntimeStatus? = null,
    val connectedClients: Int = 0,
    val volumeLevel: Int = 0,
    val isMuted: Boolean = false,
    val lastCommand: String? = null,
    val localAddresses: List<String> = emptyList(),
    val recommendedAddress: String? = null,
    val launchAtStartup: Boolean = false,
    val startupAvailable: Boolean = false,
    val startupError: String? = null,
) {
    val isRunning: Boolean
        get() = runtime?.ktorState == KtorServerState.Running

    val serverRestarts: Int
        get() = runtime?.restartCount ?: 0
}

class DesktopViewModel(
    private val commandServer: CommandServer,
    private val startupManager: StartupManager,
) : ViewModel() {

    val uiState: StateFlow<DesktopUiState>
        field = MutableStateFlow(DesktopUiState())

    init {
        uiState.value = DesktopUiState(
            runtime = commandServer.runtimeStatus.value,
            localAddresses = commandServer.localAddresses,
            recommendedAddress = commandServer.recommendedAddress,
            launchAtStartup = startupManager.isEnabled(),
            startupAvailable = startupManager.canConfigure(),
        )

        viewModelScope.launch {
            commandServer.runtimeStatus.collect { runtime ->
                uiState.update { current ->
                    current.copy(
                        runtime = runtime,
                        connectedClients = runtime.connectedClients,
                        localAddresses = runtime.localAddresses,
                        recommendedAddress = runtime.recommendedAddress,
                    )
                }
            }
        }

        viewModelScope.launch {
            commandServer.status.collect { serverStatus ->
                uiState.update { current ->
                    current.copy(
                        connectedClients = serverStatus.connectedClients,
                        volumeLevel = serverStatus.volumeLevel,
                        isMuted = serverStatus.isMuted,
                        lastCommand = serverStatus.lastCommand,
                    )
                }
            }
        }
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
}
