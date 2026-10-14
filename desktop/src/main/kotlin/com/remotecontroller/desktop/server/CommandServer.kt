package com.remotecontroller.desktop.server

import com.remotecontroller.desktop.executor.CommandExecutor
import com.remotecontroller.shared.model.RemoteCommand
import com.remotecontroller.shared.model.RemoteKeys
import com.remotecontroller.shared.model.ServerInfo
import com.remotecontroller.shared.model.ServerMessage
import com.remotecontroller.shared.model.ServerStatus
import com.remotecontroller.shared.network.RemoteJson
import com.remotecontroller.shared.network.RemoteProtocol
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.BindException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CommandServer(
    private val commandExecutor: CommandExecutor,
    private val port: Int = RemoteKeys.DEFAULT_PORT,
) {
    private val lifecycle = Mutex()
    private var session = ServerSession()

    val status: StateFlow<ServerStatus>
        field = MutableStateFlow(initialStatus())

    val runtimeStatus: StateFlow<ServerRuntimeStatus>
        field = MutableStateFlow(ServerRuntimeStatus(port = port))

    private val probeClient: java.net.http.HttpClient = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofMillis(PROBE_TIMEOUT_MS))
        .build()

    val localAddresses: List<String>
        get() = readCandidates().map { it.address }

    val recommendedAddress: String?
        get() = readCandidates().filter { it.score > 0 }.maxByOrNull { it.score }?.address

    suspend fun start() {
        lifecycle.withLock { session = session.copy(shutdown = false) }
        bringUp("initial start")
    }

    suspend fun stop() {
        lifecycle.withLock {
            session = session.copy(
                shutdown = true,
                probeFailures = 0,
                phase = KtorServerState.Stopped,
                lastError = null,
            )
            stopEngineLocked()
            publishRuntimeLocked()
        }
    }

    suspend fun ensureRunning() {
        if (lifecycle.withLock { session.shutdown }) return

        val needsStart = lifecycle.withLock {
            session.engine == null || session.phase != KtorServerState.Running
        }
        if (needsStart) {
            bringUp("ensure running")
            return
        }

        val healthy = probeLocalHealth()
        val restart = lifecycle.withLock {
            if (session.shutdown || session.engine == null || session.phase != KtorServerState.Running) {
                return@withLock false
            }
            if (healthy || runtimeStatus.value.connectedClients > 0) {
                if (session.probeFailures != 0 || session.lastError != null) {
                    session = session.copy(probeFailures = 0, lastError = null)
                    publishRuntimeLocked()
                }
                false
            } else {
                val failures = session.probeFailures + 1
                session = session.copy(probeFailures = failures)
                log("local health probe failed ($failures/$FAILURES_BEFORE_RESTART)")
                if (failures < FAILURES_BEFORE_RESTART) {
                    session = session.copy(
                        lastError = "Local health check failed ($failures of $FAILURES_BEFORE_RESTART)",
                    )
                    publishRuntimeLocked()
                    false
                } else {
                    true
                }
            }
        }
        if (!restart) return

        lifecycle.withLock {
            if (session.shutdown || session.engine == null) return
            log("restarting server after $FAILURES_BEFORE_RESTART failed health checks")
            session = session.copy(
                phase = KtorServerState.Recovering,
                lastError = "Restarting server after failed health checks",
                probeFailures = 0,
                restarts = session.restarts + 1,
            )
            publishRuntimeLocked()
            stopEngineLocked()
        }
        bringUp("health recovery")
    }

    suspend fun refreshStatus() {
        lifecycle.withLock { publishRuntimeLocked() }
    }

    private suspend fun bringUp(reason: String) {
        val bound = lifecycle.withLock {
            if (session.shutdown) return
            if (session.engine != null && session.phase == KtorServerState.Running) return
            if (session.engine != null) stopEngineLocked()

            session = session.copy(phase = KtorServerState.Starting, lastError = null)
            publishRuntimeLocked()
            log("starting Ktor ($reason) on 0.0.0.0:$port")

            try {
                bindServer()
                session.engine != null
            } catch (error: CancellationException) {
                withContext(NonCancellable) { stopEngineLocked() }
                throw error
            } catch (error: Exception) {
                session = session.copy(
                    engine = null,
                    phase = KtorServerState.Error,
                    lastError = describeBindFailure(error),
                )
                publishRuntimeLocked()
                log("start failed: ${session.lastError}", error)
                false
            }
        }
        if (!bound) return

        val healthy = awaitHealthy()
        val listening = lifecycle.withLock {
            if (session.shutdown || session.engine == null) {
                stopEngineLocked()
                if (!session.shutdown) {
                    session = session.copy(phase = KtorServerState.Error)
                    publishRuntimeLocked()
                }
                false
            } else if (!healthy) {
                val message = "Server started but is not accepting connections on 127.0.0.1:$port"
                session = session.copy(lastError = message, phase = KtorServerState.Error)
                log(message)
                stopEngineLocked()
                publishRuntimeLocked()
                false
            } else {
                session = session.copy(
                    phase = KtorServerState.Running,
                    lastError = null,
                    probeFailures = 0,
                )
                publishRuntimeLocked()
                log("listening on 0.0.0.0:$port, recommended ${recommendedAddress ?: "no LAN address"}")
                true
            }
        }
        if (listening) ensureFirewallRule()
    }

    private suspend fun bindServer() {
        var lastFailure: Throwable? = null
        repeat(BIND_ATTEMPTS) { attempt ->
            try {
                withContext(Dispatchers.IO) {
                    embeddedServer(Netty, host = "0.0.0.0", port = port) {
                        install(ContentNegotiation) {
                            json(RemoteJson.json)
                        }
                        install(WebSockets) {
                            pingPeriod = 20.seconds
                            timeout = 120.seconds
                        }

                        routing {
                            get(RemoteProtocol.INFO_PATH) {
                                call.respond(
                                    ServerInfo(
                                        name = System.getenv("COMPUTERNAME") ?: "Windows PC",
                                        port = port,
                                    ),
                                )
                            }

                            webSocket(RemoteProtocol.WS_PATH) {
                                val clients = noteClientChange(1)
                                log("client connected ($clients)")
                                publishVolumeStatus(clients)

                                try {
                                    sendServerStatus(currentStatus(clients))

                                    for (frame in incoming) {
                                        if (frame is Frame.Text) {
                                            runCatching {
                                                handleCommand(frame.readText())
                                            }.onFailure { error ->
                                                log("command failed: ${error.message}", error)
                                                runCatching {
                                                    send(
                                                        Frame.Text(
                                                            RemoteJson.encodeMessage(
                                                                ServerMessage.Error(error.message ?: "Command failed"),
                                                            ),
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    log("client socket closed: ${error.message}")
                                } finally {
                                    val remaining = noteClientChange(-1)
                                    log("client disconnected ($remaining)")
                                    publishVolumeStatus(remaining)
                                }
                            }
                        }
                    }.start(wait = false).also { started ->
                        session = session.copy(engine = started)
                    }
                }
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                session = session.copy(engine = null)
                lastFailure = error
                log("bind attempt ${attempt + 1}/$BIND_ATTEMPTS failed: ${error.message}")
                if (attempt < BIND_ATTEMPTS - 1) delay(BIND_RETRY_DELAY_MS.milliseconds)
            }
        }
        throw lastFailure ?: IllegalStateException("Failed to bind port $port")
    }

    private suspend fun awaitHealthy(): Boolean {
        repeat(HEALTHY_POLLS) { attempt ->
            if (probeLocalHealth()) return true
            if (attempt < HEALTHY_POLLS - 1) delay(HEALTHY_POLL_DELAY_MS.milliseconds)
        }
        return false
    }

    private suspend fun probeLocalHealth(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:$port${RemoteProtocol.INFO_PATH}"))
                .timeout(java.time.Duration.ofMillis(PROBE_TIMEOUT_MS))
                .GET()
                .build()
            val response = probeClient.send(request, java.net.http.HttpResponse.BodyHandlers.discarding())
            response.statusCode() in 200..299
        }.getOrDefault(false)
    }

    private suspend fun stopEngineLocked() {
        val current = session.engine ?: return
        session = session.copy(engine = null)
        withContext(Dispatchers.IO) {
            runCatching {
                current.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
            }.onFailure { error ->
                log("stop failed: ${error.message}", error)
            }
        }
    }

    private suspend fun ensureFirewallRule() {
        val alreadyChecked = lifecycle.withLock {
            if (session.firewallChecked) return@withLock true
            session = session.copy(firewallChecked = true)
            false
        }
        if (alreadyChecked) return

        val firewallWarning = withContext(Dispatchers.IO) {
            WindowsFirewall.allowIncomingTcp(port)
        } ?: return

        lifecycle.withLock {
            session = session.copy(warning = firewallWarning)
            publishRuntimeLocked()
        }
        log(firewallWarning)
    }

    private suspend fun DefaultWebSocketServerSession.handleCommand(text: String) {
        val command = RemoteJson.decode<RemoteCommand>(text)
        val clients = runtimeStatus.value.connectedClients
        val newStatus = commandExecutor.execute(command).copy(connectedClients = clients)
        status.value = newStatus
        send(Frame.Text(RemoteJson.encodeMessage(ServerMessage.Ack(command, success = true))))
        send(Frame.Text(RemoteJson.encodeMessage(ServerMessage.Status(newStatus))))
    }

    private suspend fun DefaultWebSocketServerSession.sendServerStatus(serverStatus: ServerStatus) {
        send(Frame.Text(RemoteJson.encodeMessage(ServerMessage.Status(serverStatus))))
    }

    private fun noteClientChange(delta: Int): Int =
        runtimeStatus.updateAndGet { current ->
            current.copy(connectedClients = (current.connectedClients + delta).coerceAtLeast(0))
        }.connectedClients

    private fun publishVolumeStatus(clients: Int) {
        status.update { current ->
            commandExecutor.currentStatus().copy(
                connectedClients = clients,
                lastCommand = current.lastCommand,
            )
        }
    }

    private fun currentStatus(clients: Int): ServerStatus =
        commandExecutor.currentStatus().copy(connectedClients = clients)

    private fun publishRuntimeLocked() {
        val snapshot = session
        val addresses = readCandidates()
        val reachable = addresses.filter { it.score > 0 }
        runtimeStatus.update { current ->
            ServerRuntimeStatus(
                ktorState = snapshot.phase,
                networkState = if (reachable.isEmpty()) NetworkState.Unavailable else NetworkState.Available,
                connectedClients = current.connectedClients,
                localAddresses = addresses.map { it.address },
                recommendedAddress = reachable.maxByOrNull { it.score }?.address,
                lastError = snapshot.lastError,
                warning = snapshot.warning,
                restartCount = snapshot.restarts,
                port = port,
            )
        }
    }

    private fun initialStatus(): ServerStatus =
        commandExecutor.currentStatus().copy(connectedClients = 0)

    private fun readCandidates(): List<LocalAddress> {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
            .getOrDefault(emptyList())

        return interfaces.flatMap { nic ->
            runCatching { addressesOf(nic) }.getOrDefault(emptyList())
        }.sortedByDescending { it.score }
    }

    private fun addressesOf(nic: NetworkInterface): List<LocalAddress> {
        if (!nic.isUp || nic.isLoopback) return emptyList()
        val label = "${nic.displayName} ${nic.name}"
        return nic.inetAddresses.toList()
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { inet ->
                val host = inet.hostAddress ?: return@mapNotNull null
                LocalAddress(
                    address = host,
                    score = scoreAddress(nic, label, host),
                )
            }
    }

    private fun scoreAddress(nic: NetworkInterface, label: String, address: String): Int {
        val name = label.lowercase()
        var score = 0
        if (VIRTUAL_MARKERS.any { marker -> name.contains(marker) }) score -= 1_000
        if (runCatching { nic.isPointToPoint }.getOrDefault(false)) score -= 500
        if (name.contains("wi-fi") || name.contains("wifi") || name.contains("wlan") || name.contains("wireless")) {
            score += 300
        }
        if (name.contains("ethernet") || name.contains("lan")) score += 200
        when {
            address.startsWith("192.168.") -> score += 50
            address.startsWith("10.") -> score += 40
            address.startsWith("172.") -> {
                val second = address.split('.').getOrNull(1)?.toIntOrNull()
                if (second != null && second in 16..31) score += 30
            }
        }
        return score
    }

    private fun describeBindFailure(error: Throwable): String {
        val bind = generateSequence(error) { it.cause }.filterIsInstance<BindException>().firstOrNull()
        if (bind != null) {
            return "Port $port is already in use. Close the other Remote Controller Server window."
        }
        return error.message ?: "Failed to start Ktor server"
    }

    private fun log(message: String, error: Throwable? = null) {
        println("[CommandServer] $message")
        error?.printStackTrace()
    }

    private data class ServerSession(
        val engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null,
        val phase: KtorServerState = KtorServerState.Starting,
        val restarts: Int = 0,
        val probeFailures: Int = 0,
        val lastError: String? = null,
        val warning: String? = null,
        val shutdown: Boolean = false,
        val firewallChecked: Boolean = false,
    )

    private data class LocalAddress(
        val address: String,
        val score: Int,
    )

    private companion object {
        const val BIND_ATTEMPTS = 3
        const val BIND_RETRY_DELAY_MS = 400L
        const val HEALTHY_POLLS = 8
        const val HEALTHY_POLL_DELAY_MS = 200L
        const val PROBE_TIMEOUT_MS = 700L
        const val FAILURES_BEFORE_RESTART = 3

        val VIRTUAL_MARKERS = listOf(
            "virtual",
            "hyper-v",
            "vethernet",
            "vmware",
            "virtualbox",
            "vbox",
            "wsl",
            "docker",
            "vpn",
            "tap-",
            "tun",
            "loopback",
            "pseudo",
            "bluetooth",
            "nordlynx",
            "wireguard",
            "zerotier",
            "hamachi",
        )
    }
}
