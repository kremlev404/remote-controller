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
