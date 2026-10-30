package com.remotecontroller.shared.network

import com.remotecontroller.shared.model.ClipRequest
import com.remotecontroller.shared.model.RemoteCommand
import com.remotecontroller.shared.model.ServerMessage
import kotlinx.serialization.json.Json

object RemoteProtocol {
    const val WS_PATH = "/ws"
    const val STREAM_PATH = "/stream"
    const val INFO_PATH = "/info"
    const val CLIP_PATH = "/web"
    const val CLIP_WS_PATH = "/web/ws"
}

object RemoteJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    inline fun <reified T> decode(text: String): T = json.decodeFromString(text)

    inline fun <reified T> encode(value: T): String = json.encodeToString(value)

    fun encodeCommand(command: RemoteCommand): String = encode(command)

    fun encodeRequest(request: ClipRequest): String = encode(request)

    fun encodeMessage(message: ServerMessage): String = encode(message)
}
