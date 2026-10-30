package com.remotecontroller.desktop.server

import com.remotecontroller.shared.model.ClipEvent
import com.remotecontroller.shared.model.ClipNote
import com.remotecontroller.shared.model.ClipRequest
import com.remotecontroller.shared.model.RemoteCommand
import com.remotecontroller.shared.network.RemoteJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ClipboardState(
    val notes: List<ClipNote> = emptyList(),
    val browsers: Int = 0,
)

internal class ClipBrowser(
    val endpoint: String,
) {
    @Volatile
    var listed: Boolean = false
    private val sendLock = Mutex()

    suspend fun send(event: ClipEvent) {
        val wire = RemoteJson.encode(event)
        sendLock.withLock {
            SecureFrames.sendText(endpoint, wire)
        }
    }
}

/**
 * Text exchanged between the desktop window and browsers on /web.
 * The note list is the messenger. System clipboard reads and writes stay outside this class.
 */
class ClipboardExchange {
    private val mutex = Mutex()
    private val notes = ArrayDeque<ClipNote>()
    private val browsers = mutableListOf<ClipBrowser>()
    private var nextNoteId = 0L

    internal val state: StateFlow<ClipboardState>
        field = MutableStateFlow(ClipboardState())

    internal suspend fun open(endpoint: String): ClipBrowser = mutex.withLock {
        ClipBrowser(endpoint).also { browser ->
            browsers.add(browser)
            state.value = state.value.copy(browsers = browsers.size)
        }
    }

    internal suspend fun close(browser: ClipBrowser) {
        mutex.withLock {
            if (!browsers.remove(browser)) return
            state.value = state.value.copy(browsers = browsers.size)
        }
    }

    internal suspend fun dropBrowsers() {
        mutex.withLock {
            if (browsers.isEmpty()) return
            browsers.clear()
            state.value = state.value.copy(browsers = 0)
        }
    }

    internal suspend fun welcome(browser: ClipBrowser) {
        val snapshot = mutex.withLock { notes.toList() }
        browser.send(ClipEvent.History(snapshot))
    }

    internal suspend fun handle(
        browser: ClipBrowser,
        text: String,
        onInput: (RemoteCommand) -> Unit,
        onPresence: (ClipBrowser) -> Unit,
    ) {
        val request = runCatching { RemoteJson.decode<ClipRequest>(text) }.getOrElse {
            fail(browser, "Could not read that message")
            return
        }
        when (request) {
            is ClipRequest.Send -> submit(ClipNote.WEB, request.text, request.image).onFailure { error ->
                fail(browser, error.message ?: "Could not send")
            }
            ClipRequest.Clear -> clear()
            ClipRequest.Present -> {
                if (!browser.listed) {
                    browser.listed = true
                    onPresence(browser)
                }
            }
            is ClipRequest.Pointer -> onInput(
                RemoteCommand.Pointer(
                    x = request.x,
                    y = request.y,
                    phase = request.phase,
                    button = request.button,
                ),
            )
            is ClipRequest.Scroll -> onInput(
                RemoteCommand.PointerScroll(
                    x = request.x,
                    y = request.y,
                    deltaY = request.deltaY.coerceIn(-20, 20),
                ),
            )
            is ClipRequest.Type -> onInput(RemoteCommand.TypeText(request.text, request.backspaces))
            is ClipRequest.Key -> onInput(RemoteCommand.KeyState(request.code, request.down))
        }
    }

    internal suspend fun fail(browser: ClipBrowser, message: String) {
        runCatching { browser.send(ClipEvent.Rejected(message)) }
    }

    internal suspend fun clear() {
        val removed = mutex.withLock {
            val hadNotes = notes.isNotEmpty()
            notes.clear()
            state.value = state.value.copy(notes = emptyList())
            hadNotes
        }
        broadcast(ClipEvent.Cleared)
        if (removed) ServerLog.record("Clipboard history cleared")
    }

    internal suspend fun submit(from: String, text: String, imageBase64: String? = null): Result<ClipNote> {
        val trimmed = text.trim()
        val image = when {
            imageBase64.isNullOrBlank() -> null
            else -> ClipImages.normalize(imageBase64)
                ?: return Result.failure(IllegalArgumentException("That image could not be sent"))
        }
        if (trimmed.isEmpty() && image == null) return Result.failure(IllegalArgumentException("Nothing to send"))
        if (trimmed.length > ClipNote.MAX_TEXT) {
            return Result.failure(IllegalArgumentException("That text is too long to send"))
        }

        val note = mutex.withLock {
            val created = ClipNote(
                id = ++nextNoteId,
                from = from,
                text = trimmed,
                sentAt = System.currentTimeMillis(),
                image = image?.base64,
                imageType = image?.type,
            )
            notes.addLast(created)
            while (notes.size > MAX_NOTES) notes.removeFirst()
            state.value = state.value.copy(notes = notes.toList())
            created
        }
        broadcast(ClipEvent.Note(note))
        val origin = if (from == ClipNote.WEB) "browser" else "this PC"
        val detail = when {
            image != null && trimmed.isNotEmpty() -> "image and text"
            image != null -> "image (${image.bytes.size / 1024} KB)"
            else -> "text (${note.text.length} characters)"
        }
        ServerLog.record("Clipboard $detail from $origin")
        return Result.success(note)
    }

    private suspend fun broadcast(event: ClipEvent) {
        val targets = mutex.withLock { browsers.toList() }
        for (browser in targets) {
            runCatching { browser.send(event) }
        }
    }

    private companion object {
        const val MAX_NOTES = 100
    }
}
