package com.remotecontroller.shared.network

import com.remotecontroller.shared.model.RemoteCommand
import com.remotecontroller.shared.model.ServerMessage
import kotlinx.serialization.json.Json

object RemoteProtocol {
    const val WS_PATH = "/ws"
    const val INFO_PATH = "/info"
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

    fun encodeMessage(message: ServerMessage): String = encode(message)
}
