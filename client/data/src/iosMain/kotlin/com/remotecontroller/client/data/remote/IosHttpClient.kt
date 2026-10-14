package com.remotecontroller.client.data.remote

import com.remotecontroller.shared.network.RemoteJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.Duration.Companion.seconds

private const val CONNECT_TIMEOUT_MS = 5_000L
private const val REQUEST_TIMEOUT_MS = 8_000L

fun remoteHttpClient(): HttpClient = HttpClient(Darwin) {
    expectSuccess = true
    install(ContentNegotiation) { json(RemoteJson.json) }
    install(WebSockets) {
        pingInterval = 20.seconds
    }
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
        socketTimeoutMillis = REQUEST_TIMEOUT_MS
    }
    engine {
        configureRequest {
            setAllowsCellularAccess(true)
            setAllowsConstrainedNetworkAccess(true)
            setAllowsExpensiveNetworkAccess(true)
        }
    }
}
