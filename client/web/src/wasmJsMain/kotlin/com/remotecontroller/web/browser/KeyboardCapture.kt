@file:OptIn(ExperimentalWasmJsInterop::class)

package com.remotecontroller.web.browser

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js

private val CHORD_KEY = Regex("^(Control|Alt)(Left|Right)$")
private val MODIFIER_KEY = Regex("^(Control|Alt|Shift|Meta)(Left|Right)$")

private fun lockKeyboard() {
    js("if (navigator.keyboard) navigator.keyboard.lock().catch(() => {})")
}

private fun unlockKeyboard() {
    js("if (navigator.keyboard) navigator.keyboard.unlock()")
}

/**
 * While engaged, every key goes to [onKey] and nothing reaches the page or the browser's
 * shortcuts it allows pages to take. Ctrl + Alt alone, pressed and let go, hands the keyboard
 * back, so Ctrl + Alt + another key still reaches the PC. Leaving the window hands it back too.
 * In full screen Chromium browsers also give up Ctrl + W and the like, through the Keyboard Lock API.
 */
class KeyboardCapture(
    private val onKey: (String, Boolean) -> Unit,
    private val onReleased: () -> Unit,
) {
    private val held = LinkedHashSet<String>()
    private var active = false
    private var chord = false

    init {
        window.addEventListener("keydown", ::keyDown, true)
        window.addEventListener("keyup", ::keyUp, true)
        window.addEventListener("blur", { release() })
    }

    fun engage() {
        active = true
        if (document.fullscreenElement != null) lockKeyboard()
    }

    fun release() {
        if (!active) return
        active = false
        chord = false
        unlockKeyboard()
        held.forEach { code -> onKey(code, false) }
        held.clear()
        onReleased()
    }

    /** Takes the event from the page. Null when there is nothing to pass on. */
    private fun claim(event: Event): KeyboardEvent? {
        if (!active) return null
        event.preventDefault()
        event.stopImmediatePropagation()
        return (event as KeyboardEvent).takeIf { it.code.isNotEmpty() }
    }

    private fun keyDown(event: Event) {
        val key = claim(event) ?: return
        if (CHORD_KEY.matches(key.code)) {
            if (key.ctrlKey && key.altKey) chord = true
        } else {
            chord = false
        }
        held.add(key.code)
        onKey(key.code, true)
    }

    private fun keyUp(event: Event) {
        val key = claim(event) ?: return
        if (chord && CHORD_KEY.matches(key.code)) {
            release()
            return
        }
        // Browsers on a Mac send no keyup for a letter pressed while Command is down.
        if (key.code == "MetaLeft" || key.code == "MetaRight") {
            held.filterNot(MODIFIER_KEY::matches).forEach { code ->
                onKey(code, false)
                held.remove(code)
            }
        }
        held.remove(key.code)
        onKey(key.code, false)
    }
}
