package com.remotecontroller.client.data.remote

import com.remotecontroller.shared.network.ChunkBuffer
import com.remotecontroller.shared.network.LinkFrame
import com.remotecontroller.shared.network.RemoteProtocol
import com.remotecontroller.shared.network.ScreenFeed
import com.remotecontroller.shared.network.ScreenFrame
import com.remotecontroller.shared.network.ScreenSignal
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class ScreenStreamClient(
    private val http: HttpClient,
) {
    fun frames(host: String, port: Int, accessCode: String): Flow<ScreenFeed> = flow {
        val socket = http.webSocketSession("wss://$host:$port${RemoteProtocol.STREAM_PATH}")
        try {
            val cipher = socket.establishLink("$host:$port", accessCode)
            val frames = ChunkBuffer()
            for (frame in socket.incoming) {
                when (frame) {
                    is Frame.Binary -> {
                        val box = frames.push(frame.data, frame.fin) ?: continue
                        val (kind, body) = cipher.openPayload(box)
                        when (kind) {
                            LinkFrame.BYTES -> ScreenFrame.unpack(body)?.let { emit(ScreenFeed.Picture(it)) }
                            LinkFrame.TEXT -> {
                                val text = body.decodeToString()
                                when {
                                    ScreenSignal.isPaused(text) -> emit(ScreenFeed.HostPaused)
                                    ScreenSignal.isLive(text) -> emit(ScreenFeed.HostLive)
                                }
                            }
                        }
                    }
                    is Frame.Close -> break
                    else -> Unit
                }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { socket.close() }
            }
        }
    }
}
