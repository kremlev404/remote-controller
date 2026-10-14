package com.remotecontroller.client.presentation.remote

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remotecontroller.client.domain.model.ConnectionState
import com.remotecontroller.client.domain.model.NetworkStatus
import com.remotecontroller.client.domain.usecase.DisconnectFromPcUseCase
import com.remotecontroller.client.domain.usecase.ObserveActiveConnectionUseCase
import com.remotecontroller.client.domain.usecase.ObserveConnectionStateUseCase
import com.remotecontroller.client.domain.usecase.ObserveNetworkStatusUseCase
import com.remotecontroller.client.domain.usecase.ObserveServerStatusUseCase
import com.remotecontroller.client.domain.usecase.RetryConnectionUseCase
import com.remotecontroller.client.domain.usecase.SendKeyCommandUseCase
import com.remotecontroller.client.domain.usecase.SendMouseCommandUseCase
import com.remotecontroller.client.domain.usecase.SendVolumeCommandUseCase
import com.remotecontroller.client.presentation.describeForUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Immutable
data class RemoteScreenUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val networkStatus: NetworkStatus = NetworkStatus(),
    val pcHost: String? = null,
    val volumeLevel: Int = 50,
    val isMuted: Boolean = false,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
)

private data class RemoteLocalState(
    val volumeLevel: Int = 50,
    val isMuted: Boolean = false,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
)

@OptIn(FlowPreview::class)
class RemoteViewModel(
    private val sendVolumeCommandUseCase: SendVolumeCommandUseCase,
    private val sendKeyCommandUseCase: SendKeyCommandUseCase,
    private val sendMouseCommandUseCase: SendMouseCommandUseCase,
    private val disconnectFromPcUseCase: DisconnectFromPcUseCase,
    private val retryConnectionUseCase: RetryConnectionUseCase,
    observeConnectionStateUseCase: ObserveConnectionStateUseCase,
    observeServerStatusUseCase: ObserveServerStatusUseCase,
    observeActiveConnectionUseCase: ObserveActiveConnectionUseCase,
    observeNetworkStatusUseCase: ObserveNetworkStatusUseCase,
) : ViewModel() {

    private val localState = MutableStateFlow(RemoteLocalState())

    private val pendingVolume = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    val uiState: StateFlow<RemoteScreenUiState> = combine(
        observeConnectionStateUseCase(),
        observeServerStatusUseCase(),
        observeActiveConnectionUseCase(),
        observeNetworkStatusUseCase(),
        localState,
    ) { connection, serverStatus, activeConnection, networkStatus, local ->
        RemoteScreenUiState(
            connectionState = connection,
            networkStatus = networkStatus,
            pcHost = activeConnection?.host?.value,
            volumeLevel = serverStatus?.volumeLevel ?: local.volumeLevel,
            isMuted = serverStatus?.isMuted ?: local.isMuted,
            isSending = local.isSending,
            errorMessage = local.errorMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RemoteScreenUiState(),
    )

    init {
        viewModelScope.launch {
            pendingVolume
                .debounce(250.milliseconds)
                .collect { level -> runCommand { sendVolumeCommandUseCase.setVolume(level) } }
        }
    }

    fun setVolume(level: Int) {
        localState.update { it.copy(volumeLevel = level) }
        pendingVolume.tryEmit(level)
    }

    fun volumeUp() = runCommand { sendVolumeCommandUseCase.volumeUp() }

    fun volumeDown() = runCommand { sendVolumeCommandUseCase.volumeDown() }

    fun toggleMute() = runCommand { sendVolumeCommandUseCase.toggleMute() }

    fun pressKey(key: String) = runCommand { sendKeyCommandUseCase.pressKey(key) }

    fun mouseMove(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        runCommand { sendMouseCommandUseCase.move(dx, dy) }
    }

    fun mouseClick(button: String) = runCommand { sendMouseCommandUseCase.click(button) }

    fun mouseScroll(deltaY: Int) {
        if (deltaY == 0) return
        runCommand { sendMouseCommandUseCase.scroll(deltaY) }
    }

    fun retryConnection() {
        viewModelScope.launch {
            localState.update { it.copy(isSending = true, errorMessage = null) }
            try {
                retryConnectionUseCase().onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    localState.update { it.copy(errorMessage = error.describeForUi()) }
                }
            } finally {
                localState.update { it.copy(isSending = false) }
            }
        }
    }

    fun disconnect(onDisconnected: () -> Unit) {
        viewModelScope.launch {
            disconnectFromPcUseCase()
            onDisconnected()
        }
    }

    private fun runCommand(block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            localState.update { it.copy(isSending = true, errorMessage = null) }
            block().onFailure { error ->
                localState.update { it.copy(errorMessage = error.describeForUi()) }
            }
            localState.update { it.copy(isSending = false) }
        }
    }
}
