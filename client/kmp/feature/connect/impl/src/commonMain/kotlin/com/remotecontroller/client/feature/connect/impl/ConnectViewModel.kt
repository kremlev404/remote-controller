package com.remotecontroller.client.feature.connect.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remotecontroller.client.domain.model.LocalNetworkAccess
import com.remotecontroller.client.domain.model.SavedConnection
import com.remotecontroller.client.domain.usecase.ConnectToPcUseCase
import com.remotecontroller.client.domain.usecase.ObserveApprovalCodeUseCase
import com.remotecontroller.client.domain.usecase.DisconnectFromPcUseCase
import com.remotecontroller.client.domain.usecase.ObserveConnectionStateUseCase
import com.remotecontroller.client.domain.usecase.ObserveSavedConnectionUseCase
import com.remotecontroller.client.domain.usecase.ObserveSavedServersUseCase
import com.remotecontroller.client.core.describeForUi
import com.remotecontroller.client.domain.usecase.SaveConnectionUseCase
import com.remotecontroller.client.feature.connect.impl.state.ConnectUiState
import com.remotecontroller.shared.model.ServerPort
import com.remotecontroller.shared.network.CertFingerprint
import com.remotecontroller.shared.network.LinkCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private data class ConnectFormState(
    val hint: String = "",
    val host: String = "",
    val port: String = ServerPort.DEFAULT.value.toString(),
    val accessCode: String = "",
    val certFingerprint: String = "",
    val checkCertificate: Boolean = false,
    val isLoading: Boolean = false,
    val pendingStartupNavigation: Boolean = false,
    val errorMessage: String? = null,
    val needsLocalNetworkPermission: Boolean = false,
    val savedDefaultsApplied: Boolean = false,
)

class ConnectViewModel(
    private val connectToPcUseCase: ConnectToPcUseCase,
    private val disconnectFromPcUseCase: DisconnectFromPcUseCase,
    private val saveConnectionUseCase: SaveConnectionUseCase,
    observeConnectionStateUseCase: ObserveConnectionStateUseCase,
    private val observeSavedConnectionUseCase: ObserveSavedConnectionUseCase,
    observeSavedServersUseCase: ObserveSavedServersUseCase,
    private val localNetwork: LocalNetworkAccess,
    observeApprovalCodeUseCase: ObserveApprovalCodeUseCase,
) : ViewModel() {

    val localNetworkPermission: String = localNetwork.permission

    private val formState = MutableStateFlow(ConnectFormState())
    private val localNetworkAllowed = MutableStateFlow(localNetwork.isGranted())
    private val startup = Mutex()
    private var startupStarted = false

    val uiState: StateFlow<ConnectUiState> = combine(
        formState,
        observeConnectionStateUseCase(),
        observeSavedServersUseCase(),
        observeApprovalCodeUseCase(),
    ) { form, connection, savedServers, approvalCode ->
        ConnectUiState(
            hint = form.hint,
            host = form.host,
            port = form.port,
            accessCode = form.accessCode,
            certFingerprint = form.certFingerprint,
            checkCertificate = form.checkCertificate,
            savedServers = savedServers,
            connectionState = connection,
            isLoading = form.isLoading,
            pendingStartupNavigation = form.pendingStartupNavigation,
            errorMessage = form.errorMessage,
            needsLocalNetworkPermission = form.needsLocalNetworkPermission,
            approvalCode = approvalCode,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ConnectUiState(),
    )

    init {
        viewModelScope.launch {
            val saved = observeSavedConnectionUseCase().first() ?: return@launch
            formState.update {
                it.copy(
                    hint = saved.hint,
                    host = saved.host,
                    port = saved.portText,
                    certFingerprint = saved.certFingerprint,
                    savedDefaultsApplied = true,
                )
            }
        }
        if (localNetworkAllowed.value) launchStartupConnect()
    }

    fun onLocalNetworkAccess(granted: Boolean) {
        localNetworkAllowed.value = granted
        formState.update { current ->
            current.copy(
                needsLocalNetworkPermission = !granted,
                errorMessage = if (granted && current.needsLocalNetworkPermission) null else current.errorMessage,
            )
        }
        if (granted) launchStartupConnect()
    }

    fun onHintChange(hint: String) {
        formState.update { it.copy(hint = hint, errorMessage = null, savedDefaultsApplied = true) }
    }

    fun onHostChange(host: String) {
        formState.update { it.copy(host = host, errorMessage = null, savedDefaultsApplied = true) }
    }

    fun onCheckCertificate(enabled: Boolean) {
        formState.update { it.copy(checkCertificate = enabled, errorMessage = null) }
    }

    fun onCertFingerprintChange(value: String) {
        val cleaned = value.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(80)
        formState.update { it.copy(certFingerprint = cleaned, errorMessage = null, savedDefaultsApplied = true) }
    }

    fun onAccessCodeChange(code: String) {
        val cleaned = code.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(14)
        formState.update { it.copy(accessCode = cleaned, errorMessage = null, savedDefaultsApplied = true) }
    }

    fun onPortChange(port: String) {
        formState.update {
            it.copy(
                port = port.filter(Char::isDigit),
                errorMessage = null,
                savedDefaultsApplied = true,
            )
        }
    }

    fun selectSavedServer(server: SavedConnection) {
        formState.update {
            it.copy(
                hint = server.hint,
                host = server.host,
                port = server.portText,
                certFingerprint = server.certFingerprint,
                errorMessage = null,
                savedDefaultsApplied = true,
            )
        }
    }

    fun isLocalNetworkGranted(): Boolean = localNetwork.isGranted()

    fun connect() {
        if (!localNetwork.isGranted()) {
            localNetworkAllowed.value = false
            formState.update { it.copy(needsLocalNetworkPermission = true) }
            return
        }
        localNetworkAllowed.value = true
        val form = formState.value
        // The code is optional: a PC with the code switched off asks its owner instead.
        if (form.accessCode.isNotBlank() && !LinkCrypto.isCode(form.accessCode)) {
            formState.update { it.copy(errorMessage = "The access code has 12 letters and digits.") }
            return
        }
        val fingerprint = if (form.checkCertificate) form.certFingerprint else ""
        if (form.checkCertificate && CertFingerprint.normalize(fingerprint).length != CertFingerprint.HEX_LENGTH) {
            formState.update { it.copy(errorMessage = "Paste the certificate fingerprint from the laptop.") }
            return
        }
        val port = form.port.toIntOrNull() ?: ServerPort.DEFAULT.value
        formState.update { it.copy(pendingStartupNavigation = false) }
        viewModelScope.launch {
            performConnect(form.hint, form.host, port, form.accessCode, fingerprint)
        }
    }

    private fun launchStartupConnect() {
        viewModelScope.launch {
            val saved = startup.withLock {
                if (startupStarted || AppSession.startupAutoConnectDone || !localNetworkAllowed.value) {
                    null
                } else {
                    startupStarted = true
                    observeSavedConnectionUseCase().first()
                }
            } ?: return@launch
            if (saved.host.isBlank()) return@launch
            AppSession.startupAutoConnectDone = true
            formState.update {
                it.copy(
                    hint = saved.hint,
                    host = saved.host,
                    port = saved.portText,
                    certFingerprint = saved.certFingerprint,
                    savedDefaultsApplied = true,
                )
            }
        }
    }

    private suspend fun performConnect(
        hint: String,
        host: String,
        port: Int,
        accessCode: String,
        certFingerprint: String,
    ) {
        formState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            connectToPcUseCase(host, port, hint, accessCode, certFingerprint)
                .onSuccess { saveConnectionUseCase(host, port, hint, certFingerprint) }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    formState.update { it.copy(errorMessage = error.describeForUi()) }
                }
        } finally {
            formState.update { it.copy(isLoading = false) }
        }
    }

    fun disconnect() {
        viewModelScope.launch { disconnectFromPcUseCase() }
    }
}
