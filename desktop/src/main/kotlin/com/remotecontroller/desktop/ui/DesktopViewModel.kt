package com.remotecontroller.desktop.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remotecontroller.desktop.executor.ScreenCapturer
import com.remotecontroller.desktop.executor.SharedDisplay
import com.remotecontroller.desktop.server.ApprovalRequest
import com.remotecontroller.desktop.server.ClipImages
import com.remotecontroller.desktop.server.ClipboardExchange
import com.remotecontroller.desktop.server.CommandServer
import com.remotecontroller.desktop.server.ConnectionApprovals
import com.remotecontroller.desktop.server.DesktopSettings
import com.remotecontroller.desktop.server.RelayLink
import com.remotecontroller.desktop.server.RelayState
import com.remotecontroller.desktop.server.ServerLog
import com.remotecontroller.desktop.server.ServerLogLine
import com.remotecontroller.desktop.server.SystemClipboard
import com.remotecontroller.desktop.startup.StartupManager
import com.remotecontroller.shared.model.ClipNote
import com.remotecontroller.shared.network.RemoteProtocol
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    val displays: List<SharedDisplay> = emptyList(),
    val selectedDisplayId: String = "",
    val streamFps: Int = 0,
    val streamViewers: Int = 0,
    val streamPaused: Boolean = false,
    val streamError: String? = null,
    val clipNotes: List<ClipNote> = emptyList(),
    val clipBrowsers: Int = 0,
    val shareClipboard: Boolean = false,
    val clipError: String? = null,
    val onClipboardId: Long? = null,
    val clipSentCount: Int = 0,
    val requireCode: Boolean = true,
    val approvals: List<ApprovalRequest> = emptyList(),
    val relayEnabled: Boolean = false,
    val relayUrl: String = "",
    val relayState: RelayState = RelayState.Off,
)

class DesktopViewModel(
    private val commandServer: CommandServer,
    private val startupManager: StartupManager,
    private val screenCapturer: ScreenCapturer,
    private val clipboard: ClipboardExchange,
    private val settings: DesktopSettings,
    private val approvals: ConnectionApprovals,
    private val relayLink: RelayLink,
) : ViewModel() {
    private val clipGate = Mutex()
    private var clipboardStamp: String = ""
    private var handledNoteId: Long = 0L

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
            displays = screenCapturer.displays.value,
            selectedDisplayId = screenCapturer.selectedDisplayId.value,
            streamFps = screenCapturer.framesPerSecond.value,
            streamViewers = screenCapturer.viewerCount.value,
            streamError = screenCapturer.captureError.value,
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

        viewModelScope.launch {
            clipboard.state.collect { clip ->
                uiState.update { current ->
                    current.copy(clipNotes = clip.notes, clipBrowsers = clip.browsers)
                }
                val latest = clip.notes.lastOrNull() ?: return@collect
                if (latest.id == handledNoteId) return@collect
                handledNoteId = latest.id
                if (latest.from != ClipNote.WEB || !uiState.value.shareClipboard) return@collect
                clipGate.withLock {
                    placeOnClipboard(latest)
                }
            }
        }

        viewModelScope.launch {
            clipGate.withLock { rememberClipboard() }
            while (isActive) {
                delay(CLIP_POLL_MS)
                if (!uiState.value.shareClipboard) continue
                clipGate.withLock { publishSystemClipboard(markOnClipboard = true) }
            }
        }

        viewModelScope.launch {
            combine(
                screenCapturer.displays,
                screenCapturer.selectedDisplayId,
                screenCapturer.framesPerSecond,
                screenCapturer.viewerCount,
                screenCapturer.captureError,
            ) { displays, selected, fps, viewers, error ->
                ScreenShare(displays, selected, fps, viewers, error)
            }.collect { share ->
                uiState.update { current ->
                    current.copy(
                        displays = share.displays,
                        selectedDisplayId = share.selectedDisplayId,
                        streamFps = share.framesPerSecond,
                        streamViewers = share.viewers,
                        streamError = share.error,
                    )
                }
            }
        }

        viewModelScope.launch {
            screenCapturer.sharingPaused.collect { paused ->
                uiState.update { it.copy(streamPaused = paused) }
            }
        }

        viewModelScope.launch {
            combine(
                settings.requireCode,
                approvals.pending,
                settings.relayEnabled,
                settings.relayUrl,
                relayLink.state,
            ) { requireCode, pending, relayEnabled, relayUrl, relayState ->
                Access(requireCode, pending, relayEnabled, relayUrl, relayState)
            }.collect { access ->
                uiState.update { current ->
                    current.copy(
                        requireCode = access.requireCode,
                        approvals = access.approvals,
                        relayEnabled = access.relayEnabled,
                        relayUrl = access.relayUrl,
                        relayState = access.relayState,
                    )
                }
            }
        }
    }

    fun setRequireCode(enabled: Boolean) {
        commandServer.setRequireCode(enabled)
    }

    fun answerApproval(id: Long, allow: Boolean) {
        approvals.answer(id, allow)
    }

    fun setRelayEnabled(enabled: Boolean) {
        settings.setRelayEnabled(enabled)
    }

    fun setRelayUrl(url: String) {
        settings.setRelayUrl(url)
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

    fun selectDisplay(id: String) {
        screenCapturer.selectDisplay(id)
    }

    fun pauseSharing() {
        screenCapturer.pauseSharing()
    }

    fun resumeSharing() {
        screenCapturer.resumeSharing()
    }

    fun restartSharing() {
        screenCapturer.restartSharing()
    }

    fun canGrantScreenAccess(): Boolean {
        val os = System.getProperty("os.name").orEmpty()
        return os.startsWith("Mac", ignoreCase = true) || os.startsWith("Windows", ignoreCase = true)
    }

    fun grantScreenAccess() {
        val os = System.getProperty("os.name").orEmpty()
        val command = when {
            os.startsWith("Mac", ignoreCase = true) -> listOf(
                "open",
                "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture",
            )
            os.startsWith("Windows", ignoreCase = true) -> listOf(
                "cmd",
                "/c",
                "start",
                "ms-settings:privacy",
            )
            else -> return
        }
        runCatching { ProcessBuilder(command).start() }
    }

    fun sendClip(text: String) {
        viewModelScope.launch {
            publishClip(text, imageBase64 = null, clearDraft = true, markOnClipboard = false)
        }
    }

    fun sendSystemClipboard() {
        viewModelScope.launch {
            clipGate.withLock { publishSystemClipboard(markOnClipboard = true, reportEmpty = true) }
        }
    }

    fun copyClip(note: ClipNote) {
        viewModelScope.launch {
            clipGate.withLock { placeOnClipboard(note) }
        }
    }

    fun clearClipHistory() {
        viewModelScope.launch {
            clipboard.clear()
            uiState.update { it.copy(onClipboardId = null, clipError = null) }
        }
    }

    fun setShareClipboard(enabled: Boolean) {
        uiState.update { it.copy(shareClipboard = enabled) }
        if (!enabled) return
        viewModelScope.launch {
            clipGate.withLock { rememberClipboard() }
        }
    }

    val pairingCode: String
        get() = commandServer.pairingCode

    val certificateFingerprint: String
        get() = commandServer.certificateFingerprint

    fun trustCertificate() {
        val file = commandServer.caCertificateFile
        val os = System.getProperty("os.name")
        if (os.startsWith("Linux")) {
            trustOnLinux(file)
            return
        }
        if (!os.startsWith("Mac")) {
            runCatching { Desktop.getDesktop().open(file) }
            return
        }
        val keychain = "${System.getProperty("user.home")}/Library/Keychains/login.keychain-db"
        runCatching {
            ProcessBuilder(
                "security",
                "add-trusted-cert",
                "-r",
                "trustRoot",
                "-p",
                "ssl",
                "-k",
                keychain,
                file.absolutePath,
            ).inheritIO().start()
        }.onFailure {
            runCatching { Desktop.getDesktop().open(file) }
        }
    }

    /**
     * Chrome, Chromium, Edge and Brave on Linux read the user's NSS database (~/.pki/nssdb).
     * Firefox keeps its own store: import local-ca.crt there under Settings → Certificates.
     */
    private fun trustOnLinux(file: java.io.File) {
        val nssdb = java.io.File(System.getProperty("user.home"), ".pki/nssdb")
        val added = runCatching {
            nssdb.mkdirs()
            val process = ProcessBuilder(
                "certutil", "-d", "sql:${nssdb.absolutePath}", "-A",
                "-t", "C,,", "-n", "Remote Controller Local CA (LAN only)", "-i", file.absolutePath,
            ).inheritIO().start()
            process.waitFor() == 0
        }.getOrDefault(false)
        if (added) {
            uiState.update { it.copy(clipError = null) }
            return
        }
        runCatching { ProcessBuilder("xdg-open", file.parentFile.absolutePath).start() }
        uiState.update {
            it.copy(clipError = "Install certutil (libnss3-tools / nss-tools) or import ${file.name} in the browser.")
        }
    }

    fun openClipPage() {
        val page = "https://127.0.0.1:${uiState.value.server.port}${RemoteProtocol.CLIP_PATH}"
        val url = if (uiState.value.requireCode) "$page#${commandServer.pairingCode}" else page
        runCatching {
            Desktop.getDesktop().browse(URI(url))
        }.onFailure {
            uiState.update { it.copy(clipError = "Could not open the browser. Use $page") }
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

    private suspend fun rememberClipboard() {
        clipboardStamp = withContext(Dispatchers.IO) { SystemClipboard.read().fingerprint() }
    }

    private suspend fun placeOnClipboard(note: ClipNote) {
        val wrote = withContext(Dispatchers.IO) {
            val image = note.image?.let(ClipImages::normalize)
            if (note.image != null && image == null) return@withContext false
            if (image != null) SystemClipboard.writeImage(image.bytes) else SystemClipboard.writeText(note.text)
        }
        if (wrote) {
            rememberClipboard()
            uiState.update { it.copy(onClipboardId = note.id, clipError = null) }
        } else {
            uiState.update { it.copy(clipError = "Could not copy that onto the clipboard.") }
        }
    }

    private suspend fun publishSystemClipboard(markOnClipboard: Boolean, reportEmpty: Boolean = false) {
        val payload = withContext(Dispatchers.IO) { SystemClipboard.read() }
        val stamp = payload.fingerprint()
        if (stamp == clipboardStamp && !reportEmpty) return
        val image = payload.image
        val text = if (payload.text.length > ClipNote.MAX_TEXT) "" else payload.text
        if (image != null && image.bytes.size > ClipNote.MAX_IMAGE_BYTES) {
            clipboardStamp = stamp
            uiState.update { it.copy(clipError = "That image is too large to send.") }
            return
        }
        if (text.isEmpty() && image == null) {
            clipboardStamp = stamp
            if (reportEmpty) uiState.update { it.copy(clipError = "The clipboard has no text or image.") }
            return
        }
        val last = clipboard.state.value.notes.lastOrNull()
        if (!reportEmpty && last != null && last.text == text && last.image == image?.base64) {
            clipboardStamp = stamp
            return
        }
        clipboardStamp = stamp
        publishClip(text, image?.base64, clearDraft = false, markOnClipboard = markOnClipboard)
    }

    private suspend fun publishClip(
        text: String,
        imageBase64: String?,
        clearDraft: Boolean,
        markOnClipboard: Boolean,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && imageBase64.isNullOrBlank()) return
        if (trimmed.length > ClipNote.MAX_TEXT && imageBase64.isNullOrBlank()) {
            uiState.update { it.copy(clipError = "That text is too long to send.") }
            return
        }
        val textToSend = if (trimmed.length > ClipNote.MAX_TEXT) "" else trimmed
        clipboard.submit(ClipNote.DESKTOP, textToSend, imageBase64)
            .onSuccess { note ->
                uiState.update { current ->
                    current.copy(
                        clipError = null,
                        clipSentCount = if (clearDraft) current.clipSentCount + 1 else current.clipSentCount,
                        onClipboardId = if (markOnClipboard) note.id else current.onClipboardId,
                    )
                }
            }
            .onFailure { error ->
                uiState.update { it.copy(clipError = error.message ?: "Could not send") }
            }
    }

    private companion object {
        val LOG_LIMITS = setOf(1, 5, 10)
        const val CLIP_POLL_MS = 700L
    }
}

private data class Access(
    val requireCode: Boolean,
    val approvals: List<ApprovalRequest>,
    val relayEnabled: Boolean,
    val relayUrl: String,
    val relayState: RelayState,
)

private data class ScreenShare(
    val displays: List<SharedDisplay>,
    val selectedDisplayId: String,
    val framesPerSecond: Int,
    val viewers: Int,
    val error: String?,
)
