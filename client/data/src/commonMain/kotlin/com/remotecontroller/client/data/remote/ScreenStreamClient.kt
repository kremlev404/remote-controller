package com.remotecontroller.client.data.remote

import com.remotecontroller.shared.network.RemoteProtocol
import com.remotecontroller.shared.network.ScreenFrame
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
    fun frames(host: String, port: Int): Flow<ScreenFrame.Picture> = flow {
        val socket = http.webSocketSession("ws://$host:$port${RemoteProtocol.STREAM_PATH}")
        try {
            for (frame in socket.incoming) {
                when (frame) {
                    is Frame.Binary -> ScreenFrame.unpack(frame.data)?.let { emit(it) }
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
