package com.remotecontroller.client.domain.model

import com.remotecontroller.shared.model.ServerPort

data class SavedConnection(
    val host: String,
    val port: Int,
) {
    val portText: String get() = port.toString()

    val endpoint: String get() = "$host:$port"

    companion object {
        fun from(host: String, port: Int): SavedConnection? {
            val trimmedHost = host.trim()
            if (trimmedHost.isBlank()) return null
            return SavedConnection(trimmedHost, port.coerceIn(1, 65535))
        }

        val emptyPortText: String = ServerPort.DEFAULT.value.toString()
    }
}
