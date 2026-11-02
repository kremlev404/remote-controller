@file:OptIn(ExperimentalWasmJsInterop::class)

package com.remotecontroller.web.browser

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.merge
import org.w3c.dom.clipboard.ClipboardEvent
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventTarget
import org.w3c.dom.get
import org.w3c.files.FileReader
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsString
import kotlin.js.js

private val APPLE_PLATFORM = Regex("Mac|iPhone|iPad")

/** An image from the clipboard: its base64 bytes and its type. */
class PastedImage(
    val encoded: String,
    val type: String,
)

private fun decodeUriComponent(text: String): String = js("decodeURIComponent(text)")

private fun platformName(): String =
    js("(navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || 'browser'")

private fun enterFullscreen() {
    js("document.documentElement.requestFullscreen({ navigationUI: 'hide' }).catch(() => {})")
}

private fun exitFullscreen() {
    js("document.exitFullscreen().catch(() => {})")
}

/** These run for as long as the page: nothing ever takes the listener off again. */
private fun EventTarget.events(type: String): Flow<Event> = callbackFlow {
    addEventListener(type, { event -> trySend(event) })
    awaitClose()
}

/** Reads the fragment once and removes it from the address bar and history: it may hold the code. */
fun takePageHash(): String {
    val hash = decodeUriComponent(window.location.hash.removePrefix("#"))
    if (hash.isNotEmpty()) window.history.replaceState(null, "", window.location.pathname + window.location.search)
    return hash
}

/** The relay serves this same page with a marker, so the page knows to ask for a PC ID. */
fun isRelayPage(): Boolean = document.querySelector("meta[name=\"rc-relay\"]") != null

fun browserLabel(): String = "Web (${platformName()})"

fun socketOrigin(): String =
    (if (window.location.protocol == "https:") "wss://" else "ws://") + window.location.host

fun canGoFullscreen(): Boolean = document.fullscreenEnabled

fun toggleFullscreen() {
    if (document.fullscreenElement != null) exitFullscreen() else enterFullscreen()
}

fun fullscreenChanges(): Flow<Boolean> = callbackFlow {
    document.addEventListener("fullscreenchange", { trySend(document.fullscreenElement != null) })
    awaitClose()
}

/**
 * Every way a held mouse button can end without the picture seeing it: let go outside the page,
 * the browser taking the pointer, the window losing focus. They arrive after the picture's own
 * release, which makes them harmless when it did see it.
 */
fun pointerLetGo(): Flow<Event> = merge(
    window.events("pointerup"),
    window.events("pointercancel"),
    window.events("blur"),
)

/** Phones have no physical keys to capture: their text row is the way to type. */
fun hasPreciseMouse(): Boolean = window.matchMedia("(pointer: fine)").matches

fun releaseKeysLabel(): String =
    if (APPLE_PLATFORM.containsMatchIn(window.navigator.platform)) "Control + Option" else "Ctrl + Alt"

/** Null when the browser keeps no site data, as in some private windows. */
fun readStored(key: String): String? = runCatching { localStorage.getItem(key) }.getOrNull()

fun writeStored(key: String, value: String) {
    runCatching { localStorage.setItem(key, value) }
}

fun removeStored(key: String) {
    runCatching { localStorage.removeItem(key) }
}

/** The event has to be claimed while it is being delivered, so this reads it on the spot. */
fun pastedImages(): Flow<PastedImage> = callbackFlow {
    window.addEventListener("paste", { event ->
        val items = (event as ClipboardEvent).clipboardData?.items
        for (index in 0 until (items?.length ?: 0)) {
            val item = items?.get(index) ?: continue
            val type = item.type
            if (!type.startsWith("image/")) continue
            val file = item.getAsFile() ?: continue
            event.preventDefault()
            val reader = FileReader()
            reader.onload = {
                val encoded = (reader.result as? JsString)?.toString().orEmpty().substringAfter("base64,", "")
                if (encoded.isNotEmpty()) trySend(PastedImage(encoded, type))
            }
            reader.readAsDataURL(file)
            break
        }
    })
    awaitClose()
}
