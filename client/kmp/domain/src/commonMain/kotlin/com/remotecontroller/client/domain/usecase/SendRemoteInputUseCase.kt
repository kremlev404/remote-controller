package com.remotecontroller.client.domain.usecase

import com.remotecontroller.client.domain.model.RemoteInput
import com.remotecontroller.client.domain.repository.RemoteInputRepository
import com.remotecontroller.shared.model.RemoteKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Carries a viewer's input to the PC in order, without ever holding up the viewer. Moves that pile
 * up behind a slow link collapse into the newest, so a click is never late; nothing else is dropped.
 * It remembers a held button, because a viewer can lose the press it started, and [release] lets
 * go of it: a button left down on the PC would drag everything the pointer touches next.
 */
class SendRemoteInputUseCase(
    private val repository: RemoteInputRepository,
) {
    private val queue = Channel<RemoteInput>(Channel.UNLIMITED)
    private var held: RemoteInput.PointerUp? = null

    fun pointerDown(x: Float, y: Float, button: String) {
        held = RemoteInput.PointerUp(x, y, button)
        queue.trySend(RemoteInput.PointerDown(x, y, button))
    }

    fun pointerMove(x: Float, y: Float, button: String) {
        held = held?.copy(x = x, y = y)
        queue.trySend(RemoteInput.PointerMove(x, y, button))
    }

    /** Does nothing once the button is up, so the two ways of noticing a release can both report it. */
    fun pointerUp(x: Float, y: Float, button: String) {
        if (held == null) return
        held = null
        queue.trySend(RemoteInput.PointerUp(x, y, button))
    }

    fun release() {
        held?.let { pointerUp(it.x, it.y, it.button) }
    }

    fun scroll(x: Float, y: Float, deltaY: Int) {
        if (deltaY == 0) return
        val steps = deltaY.coerceIn(-RemoteKeys.MAX_SCROLL_STEPS, RemoteKeys.MAX_SCROLL_STEPS)
        queue.trySend(RemoteInput.Scroll(x, y, steps))
    }

    fun type(text: String, backspaces: Int) {
        queue.trySend(RemoteInput.Type(text, backspaces))
    }

    fun pressEnter() {
        queue.trySend(RemoteInput.Enter)
    }

    fun key(code: String, down: Boolean) {
        queue.trySend(RemoteInput.Key(code, down))
    }

    /** Sends what comes in until [close]. Runs for as long as the viewer does. */
    suspend fun deliver() {
        for (first in queue) {
            val batch = ArrayDeque<RemoteInput>()
            batch.add(first)
            while (true) {
                val next = queue.tryReceive().getOrNull() ?: break
                if (next is RemoteInput.PointerMove && batch.lastOrNull() is RemoteInput.PointerMove) batch.removeLast()
                batch.add(next)
            }
            batch.forEach { input -> send(input) }
        }
    }

    fun close() {
        queue.close()
    }

    private suspend fun send(input: RemoteInput) {
        try {
            repository.send(input)
        } catch (_: CancellationException) {
            // The link was cancelled, not this queue: the input after it may still get through.
            currentCoroutineContext().ensureActive()
        }
    }
}
