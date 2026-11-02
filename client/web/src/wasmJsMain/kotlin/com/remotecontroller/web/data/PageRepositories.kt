package com.remotecontroller.web.data

import com.remotecontroller.client.domain.model.PcConnection
import com.remotecontroller.client.domain.model.RemoteInput
import com.remotecontroller.client.domain.repository.ClipSnapshot
import com.remotecontroller.client.domain.repository.ClipboardRepository
import com.remotecontroller.client.domain.repository.RemoteInputRepository
import com.remotecontroller.client.domain.repository.ScreenRepository
import com.remotecontroller.shared.model.ClipEvent
import com.remotecontroller.shared.model.ClipRequest
import com.remotecontroller.shared.model.RemoteKeys
import com.remotecontroller.shared.network.LinkFrame
import com.remotecontroller.shared.network.RemoteJson
import com.remotecontroller.shared.network.RemoteProtocol
import com.remotecontroller.shared.network.ScreenFeed
import com.remotecontroller.shared.network.ScreenSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** The clipboard socket. On a page it also carries the mouse and keyboard, so input needs no socket of its own. */
class PageClipboardRepository(
    private val link: PageLink,
) : ClipboardRepository, RemoteInputRepository {
    private val session = MutableStateFlow<SealedSocket?>(null)

    override fun observe(connection: PcConnection): Flow<ClipSnapshot> = channelFlow {
        link.connect(RemoteProtocol.CLIP_WS_PATH, connection.accessCode) { socket ->
            session.value = socket
            try {
                var snapshot = ClipSnapshot(connected = true)
                send(snapshot)
                socket.send(RemoteJson.encodeRequest(ClipRequest.Present))
                socket.frames.collect { (kind, body) ->
                    if (kind != LinkFrame.TEXT) return@collect
                    val event = runCatching { RemoteJson.decode<ClipEvent>(body.decodeToString()) }.getOrNull()
                    if (event != null) {
                        snapshot = snapshot.after(event)
                        send(snapshot)
                    }
                }
            } finally {
                session.value = null
            }
        }
    }

    override suspend fun send(text: String, image: String?, imageType: String?) =
        request(ClipRequest.Send(text = text, image = image, imageType = imageType))

    override suspend fun clear() = request(ClipRequest.Clear)

    override suspend fun send(input: RemoteInput) = request(input.toRequest())

    private suspend fun request(request: ClipRequest) {
        session.value?.send(RemoteJson.encodeRequest(request))
    }

    private fun RemoteInput.toRequest(): ClipRequest = when (this) {
        is RemoteInput.PointerDown -> ClipRequest.Pointer(x, y, RemoteKeys.POINTER_DOWN, button)
        is RemoteInput.PointerMove -> ClipRequest.Pointer(x, y, RemoteKeys.POINTER_MOVE, button)
        is RemoteInput.PointerUp -> ClipRequest.Pointer(x, y, RemoteKeys.POINTER_UP, button)
        is RemoteInput.Scroll -> ClipRequest.Scroll(x, y, deltaY)
        is RemoteInput.Type -> ClipRequest.Type(text, backspaces)
        RemoteInput.Enter -> ClipRequest.Type("\n")
        is RemoteInput.Key -> ClipRequest.Key(code, down)
    }
}

class PageScreenRepository(
    private val link: PageLink,
) : ScreenRepository {
    override fun watch(
        connection: PcConnection,
        codecs: List<String>,
        keyframeRequests: Flow<Unit>,
    ): Flow<ScreenFeed> = channelFlow {
        link.connect(RemoteProtocol.STREAM_PATH, connection.accessCode) { socket ->
            socket.send(ScreenSignal.viewer(codecs))
            launch { keyframeRequests.collect { socket.send(ScreenSignal.KEYFRAME) } }
            socket.frames.collect { (kind, body) -> ScreenFeed.read(kind, body)?.let { send(it) } }
        }
    }
}
