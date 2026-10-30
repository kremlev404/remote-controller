package com.remotecontroller.client.data.repository

import com.remotecontroller.client.domain.model.PcConnection
import com.remotecontroller.client.domain.repository.ClipSnapshot
import com.remotecontroller.client.domain.repository.ClipboardRepository
import com.remotecontroller.shared.model.ClipEvent
import com.remotecontroller.shared.model.ClipNote
import com.remotecontroller.shared.model.ClipRequest
import com.remotecontroller.client.data.remote.establishLink
import com.remotecontroller.client.data.remote.openPayload
import com.remotecontroller.client.data.remote.sendText
import com.remotecontroller.shared.network.ChunkBuffer
import com.remotecontroller.shared.network.LinkFrame
import com.remotecontroller.shared.network.RemoteJson
import com.remotecontroller.shared.network.RemoteProtocol
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

class ClipboardRepositoryImpl(
    private val http: HttpClient,
) : ClipboardRepository {
    private val outgoing = Channel<String>(Channel.BUFFERED)

    override fun observe(connection: PcConnection): Flow<ClipSnapshot> = channelFlow {
        val socket = http.webSocketSession(
            "wss://${connection.host.value}:${connection.port.value}${RemoteProtocol.CLIP_WS_PATH}",
        )
        val cipher = socket.establishLink(
            "${connection.host.value}:${connection.port.value}",
            connection.accessCode,
        )
        val sender = launch {
            for (text in outgoing) {
                cipher.sendText(socket, text)
            }
        }
        try {
            send(ClipSnapshot(connected = true))
            val notes = mutableListOf<ClipNote>()
            var banner: String? = null
            val frames = ChunkBuffer()
            for (frame in socket.incoming) {
                if (frame !is Frame.Binary) continue
                val box = frames.push(frame.data, frame.fin) ?: continue
                val (kind, body) = cipher.openPayload(box)
                if (kind != LinkFrame.TEXT) continue
                val event = runCatching { RemoteJson.decode<ClipEvent>(body.decodeToString()) }.getOrNull() ?: continue
                when (event) {
                    is ClipEvent.History -> {
                        notes.clear()
                        notes.addAll(event.messages)
                        banner = null
                    }
                    is ClipEvent.Note -> {
                        notes.add(event.message)
                        banner = null
                    }
                    is ClipEvent.Cleared -> notes.clear()
                    is ClipEvent.Rejected -> banner = event.message
                }
                send(ClipSnapshot(notes.toList(), connected = true, banner = banner))
            }
        } finally {
            sender.cancel()
            runCatching { socket.close() }
            send(ClipSnapshot(connected = false))
        }
    }

    override suspend fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > ClipNote.MAX_TEXT) return
        outgoing.send(RemoteJson.encodeRequest(ClipRequest.Send(text = trimmed)))
    }

    override suspend fun clear() {
        outgoing.send(RemoteJson.encodeRequest(ClipRequest.Clear))
    }
}
