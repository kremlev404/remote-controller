package com.remotecontroller.client.data.remote

import com.remotecontroller.client.data.log.logInfo
import com.remotecontroller.client.data.log.logWarn
import com.remotecontroller.shared.model.PcHost
import com.remotecontroller.shared.model.RemoteCommand
import com.remotecontroller.shared.model.RemoteCommand.Hello
import com.remotecontroller.shared.model.ServerInfo
import com.remotecontroller.shared.model.ServerMessage
import com.remotecontroller.shared.model.ServerPort
import com.remotecontroller.shared.model.ServerStatus
import com.remotecontroller.shared.network.RemoteJson
import com.remotecontroller.shared.network.RemoteProtocol
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

interface PcTransport {
    suspend fun open(host: PcHost, port: ServerPort): PcLink
}

class SessionClosedByHost(message: String) : IllegalStateException(message)

interface PcLink {
    suspend fun send(command: RemoteCommand)
    suspend fun readUntilClosed(onStatus: suspend (ServerStatus) -> Unit)
    suspend fun close()
}

class PcSocketClient(
    private val http: HttpClient,
    private val deviceIdentity: DeviceIdentity,
) : PcTransport {

    override suspend fun open(host: PcHost, port: ServerPort): PcLink {
        val endpoint = "${host.value}:${port.value}"
        var lastError: Throwable? = null
        repeat(HANDSHAKE_ATTEMPTS) { attempt ->
            try {
                return openOnce(endpoint)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
                logWarn(TAG, "handshake ${attempt + 1} failed for $endpoint", error)
                if (attempt < HANDSHAKE_ATTEMPTS - 1) delay(HANDSHAKE_RETRY_DELAY)
            }
        }
        throw lastError ?: IllegalStateException("Cannot reach PC at $endpoint")
    }

    private suspend fun openOnce(endpoint: String): PcLink {
        val info = within(INFO_TIMEOUT) {
            http.get("http://$endpoint${RemoteProtocol.INFO_PATH}").body<ServerInfo>()
        }
        logInfo(TAG, "server info $endpoint name=${info.name}")
        val socket = within(SOCKET_TIMEOUT) {
            http.webSocketSession("ws://$endpoint${RemoteProtocol.WS_PATH}")
        }
        logInfo(TAG, "websocket open $endpoint")
        val link = KtorLink(socket)
        try {
            link.send(Hello(deviceName()))
        } catch (error: Throwable) {
            link.close()
            throw error
        }
        return link
    }

    private fun deviceName(): String =
        deviceIdentity.deviceName()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(NAME_MAX)
            .ifBlank { "Client" }

    private suspend fun <T> within(timeout: Duration, block: suspend () -> T): T {
        return withTimeoutOrNull(timeout) { block() }
            ?: throw IllegalStateException("Timed out after $timeout")
    }

    private class KtorLink(
        private val socket: WebSocketSession,
    ) : PcLink {

        override suspend fun send(command: RemoteCommand) {
            socket.send(Frame.Text(RemoteJson.encodeCommand(command)))
        }

        override suspend fun readUntilClosed(onStatus: suspend (ServerStatus) -> Unit) {
            for (frame in socket.incoming) {
                if (frame is Frame.Text) {
                    deliver(frame.readText(), onStatus)
                }
            }
        }

        override suspend fun close() {
            runCatching { socket.close() }
        }

        private suspend fun deliver(text: String, onStatus: suspend (ServerStatus) -> Unit) {
            val message = runCatching { RemoteJson.decode<ServerMessage>(text) }
                .getOrElse { error ->
                    logWarn(TAG, "ignored malformed server message", error)
                    return
                }
            when (message) {
                is ServerMessage.Status -> onStatus(message.status)
                is ServerMessage.Ack -> Unit
                is ServerMessage.Error -> logWarn(TAG, "server error: ${message.message}")
                is ServerMessage.Disconnect -> throw SessionClosedByHost(message.reason)
            }
        }
    }

    private companion object {
        const val TAG = "RemoteConnection"
        const val NAME_MAX = 48
        const val HANDSHAKE_ATTEMPTS = 2
        val HANDSHAKE_RETRY_DELAY = 400.milliseconds
        val INFO_TIMEOUT = 8.seconds
        val SOCKET_TIMEOUT = 8.seconds
    }
}
