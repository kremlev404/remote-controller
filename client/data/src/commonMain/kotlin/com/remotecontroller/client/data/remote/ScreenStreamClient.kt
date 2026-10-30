package com.remotecontroller.client.data.remote

import com.remotecontroller.client.domain.repository.DeviceGrantRepository
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenStreamClient(
    private val http: HttpClient,
    private val grants: DeviceGrantRepository,
) {
    /** [codecs] are what this device can play. Each item of [keyframeRequests] asks the PC for a keyframe. */
    fun frames(
        host: String,
        port: Int,
        accessCode: String,
        codecs: List<String>,
        keyframeRequests: Flow<Unit>,
    ): Flow<ScreenFeed> = flow {
        val socket = http.webSocketSession("wss://$host:$port${RemoteProtocol.STREAM_PATH}")
        try {
            val cipher = socket.establishLink("$host:$port", accessCode, grants)
            cipher.sendText(socket, ScreenSignal.viewer(codecs))
            coroutineScope {
                val requests = launch {
                    keyframeRequests.collect { cipher.sendText(socket, ScreenSignal.KEYFRAME) }
                }
                val frames = ChunkBuffer()
                for (frame in socket.incoming) {
                    when (frame) {
                        is Frame.Binary -> {
                            val box = frames.push(frame.data, frame.fin) ?: continue
                            val (kind, body) = cipher.openPayload(box)
                            when (kind) {
                                LinkFrame.BYTES -> {
                                    val video = ScreenFrame.unpackVideo(body)
                                    if (video != null) {
                                        emit(ScreenFeed.Video(video))
                                    } else {
                                        ScreenFrame.unpack(body)?.let { emit(ScreenFeed.Picture(it)) }
                                    }
                                }
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
                requests.cancel()
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { socket.close() }
            }
        }
    }
}
