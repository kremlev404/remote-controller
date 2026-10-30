@file:OptIn(ExperimentalWasmJsInterop::class)

package com.remotecontroller.web

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js

external interface BrowserSocket : JsAny {
    fun send(text: String)
    fun sendBytes(base64: String)
    fun close()
}

fun openBrowserSocket(
    url: String,
    onText: (String) -> Unit,
    onFrame: (String) -> Unit,
    onOpen: () -> Unit,
    onClose: (Int) -> Unit,
): BrowserSocket = js(
    """
    (() => {
      const socket = new WebSocket(url);
      socket.binaryType = 'arraybuffer';
      socket.onopen = () => onOpen();
      socket.onclose = (event) => onClose(event.code);
      socket.onmessage = (event) => {
        const data = event.data;
        if (typeof data === 'string') {
          onText(data);
          return;
        }
        const bytes = new Uint8Array(data);
        let binary = '';
        const size = 0x8000;
        for (let index = 0; index < bytes.length; index += size) {
          const slice = bytes.subarray(index, Math.min(index + size, bytes.length));
          binary += String.fromCharCode.apply(null, slice);
        }
        onFrame(btoa(binary));
      };
      return {
        send: (text) => { if (socket.readyState === 1) socket.send(text); },
        sendBytes: (b64) => {
          if (socket.readyState !== 1) return;
          const binary = atob(b64);
          const bytes = new Uint8Array(binary.length);
          for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i) & 255;
          socket.send(bytes);
        },
        close: () => socket.close()
      };
    })()
    """,
)

/** Reads the fragment once and removes it from the address bar and history: it may hold the code. */
fun takePageHash(): String = js(
    """
    (() => {
      const hash = decodeURIComponent((location.hash || '').replace(/^#/, ''));
      if (hash) history.replaceState(null, '', location.pathname + location.search);
      return hash;
    })()
    """,
)

/** The relay serves this same page with a marker, so the page knows to ask for a PC ID. */
fun isRelayPage(): Boolean = js("document.querySelector('meta[name=\"rc-relay\"]') !== null")

fun browserLabel(): String = js(
    "'Web (' + ((navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || 'browser') + ')'",
)

external interface KeyboardCapture : JsAny {
    fun engage()
    fun release()
}

/**
 * While engaged, every key goes to [onKey] and nothing reaches the page or the browser's
 * shortcuts it allows pages to take. Ctrl + Alt alone, pressed and let go, hands the keyboard
 * back, so Ctrl + Alt + another key still reaches the PC. Leaving the window hands it back too.
 * In full screen Chromium browsers also give up Ctrl + W and the like, through the Keyboard Lock API.
 */
fun installKeyboardCapture(
    onKey: (String, Boolean) -> Unit,
    onReleased: () -> Unit,
): KeyboardCapture = js(
    """
    (() => {
      const isChord = (code) => /^(Control|Alt)(Left|Right)$/.test(code);
      const isModifier = (code) => /^(Control|Alt|Shift|Meta)(Left|Right)$/.test(code);
      const held = new Set();
      let active = false;
      let chord = false;
      const lock = () => {
        if (!active || !document.fullscreenElement || !navigator.keyboard) return;
        navigator.keyboard.lock().catch(() => {});
      };
      const release = () => {
        if (!active) return;
        active = false;
        chord = false;
        if (navigator.keyboard) navigator.keyboard.unlock();
        held.forEach((code) => onKey(code, false));
        held.clear();
        onReleased();
      };
      window.addEventListener('keydown', (event) => {
        if (!active) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        if (!event.code) return;
        if (isChord(event.code)) {
          if (event.ctrlKey && event.altKey) chord = true;
        } else {
          chord = false;
        }
        held.add(event.code);
        onKey(event.code, true);
      }, true);
      window.addEventListener('keyup', (event) => {
        if (!active) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        if (!event.code) return;
        if (chord && isChord(event.code)) {
          release();
          return;
        }
        // Browsers on a Mac send no keyup for a letter pressed while Command is down.
        if (event.code === 'MetaLeft' || event.code === 'MetaRight') {
          held.forEach((code) => {
            if (isModifier(code)) return;
            onKey(code, false);
            held.delete(code);
          });
        }
        held.delete(event.code);
        onKey(event.code, false);
      }, true);
      window.addEventListener('blur', release);
      return {
        engage: () => {
          active = true;
          lock();
        },
        release: release
      };
    })()
    """,
)

fun canGoFullscreen(): Boolean = js("!!document.fullscreenEnabled")

fun toggleFullscreen() {
    js(
        """
        if (document.fullscreenElement) {
          document.exitFullscreen().catch(() => {});
        } else {
          document.documentElement.requestFullscreen({ navigationUI: 'hide' }).catch(() => {});
        }
        """,
    )
}

fun listenForFullscreen(handler: (Boolean) -> Unit) {
    js("document.addEventListener('fullscreenchange', () => handler(!!document.fullscreenElement))")
}

/** Phones have no physical keys to capture: their text row is the way to type. */
fun hasPreciseMouse(): Boolean = js("window.matchMedia('(pointer: fine)').matches")

fun releaseKeysLabel(): String = js(
    "/Mac|iPhone|iPad/.test(navigator.platform) ? 'Control + Option' : 'Ctrl + Alt'",
)

/** Null when the browser keeps no site data, as in some private windows. */
fun readStored(key: String): String? = js(
    "(() => { try { return localStorage.getItem(key); } catch (error) { return null; } })()",
)

fun writeStored(key: String, value: String) {
    js("try { localStorage.setItem(key, value); } catch (error) {}")
}

fun removeStored(key: String) {
    js("try { localStorage.removeItem(key); } catch (error) {}")
}

fun socketOrigin(): String = js(
    "(location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host",
)

fun listenForImagePaste(handler: (String, String) -> Unit) {
    js(
        """
        window.addEventListener('paste', (event) => {
          const items = event.clipboardData && event.clipboardData.items;
          if (!items) return;
          for (const item of items) {
            if (!item.type || item.type.indexOf('image/') !== 0) continue;
            const file = item.getAsFile();
            if (!file) continue;
            event.preventDefault();
            const reader = new FileReader();
            reader.onload = () => handler(String(reader.result || ''), item.type);
            reader.readAsDataURL(file);
            return;
          }
        });
        """,
    )
}
