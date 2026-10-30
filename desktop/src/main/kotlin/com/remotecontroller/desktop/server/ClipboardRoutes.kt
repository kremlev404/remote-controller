package com.remotecontroller.desktop.server

import com.remotecontroller.shared.model.RemoteCommand
import com.remotecontroller.shared.network.ChunkBuffer
import com.remotecontroller.shared.network.RemoteProtocol
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.Routing
import io.ktor.server.websocket.webSocketRaw
import io.ktor.websocket.Frame

internal fun Routing.clipboardRoutes(serve: suspend (LinkSocket) -> Unit) {
    staticResources(RemoteProtocol.CLIP_PATH, "web")

    webSocketRaw(RemoteProtocol.CLIP_WS_PATH) {
        serve(linkSocket())
    }
}

/** The browser page's socket: clipboard notes, pointer input and typing. */
internal suspend fun serveClipboard(
    link: LinkSocket,
    gate: LinkGate,
    clipboard: ClipboardExchange,
    onInput: (RemoteCommand) -> Unit,
    onBrowserListed: (String) -> Unit,
    onBrowserGone: (String) -> Unit,
    log: (String) -> Unit,
) {
    val endpoint = link.endpoint
    val remote = link.remote
    if (!gate.admit(link)) {
        SecureFrames.clear(endpoint)
        log("rejected clipboard link from $remote")
        return
    }
    val browser = clipboard.open(endpoint)
    val frames = ChunkBuffer()
    log("clipboard browser connected ($remote)")
    try {
        clipboard.welcome(browser)
        for (frame in link.incoming) {
            when (frame) {
                is Frame.Binary -> {
                    val box = frames.push(frame.data, frame.fin) ?: continue
                    // A frame that does not open is a replay or tampering: drop the link.
                    val text = runCatching { SecureFrames.openText(endpoint, box) }.getOrElse { error ->
                        log("clipboard browser frame rejected: ${error.javaClass.simpleName}")
                        break
                    }
                    runCatching {
                        clipboard.handle(
                            browser = browser,
                            text = text,
                            onInput = onInput,
                            onPresence = { onBrowserListed(endpoint) },
                        )
                    }.onFailure { error ->
                        log("clipboard browser message failed: ${error.javaClass.simpleName}")
                        runCatching { clipboard.fail(browser, "Could not read that message") }
                    }
                }
                is Frame.Ping -> runCatching { SocketFrames.sendPong(endpoint, frame.data) }
                is Frame.Close -> break
                else -> Unit
            }
        }
    } finally {
        SecureFrames.clear(endpoint)
        onBrowserGone(endpoint)
        clipboard.close(browser)
        log("clipboard browser disconnected ($remote)")
    }
}
