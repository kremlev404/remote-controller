package com.remotecontroller.desktop.server

import com.remotecontroller.desktop.AppVersion
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.BindException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CommandServer(
    private val commandExecutor: CommandExecutor,
    private val firewall: IncomingFirewall,
    private val machineName: String,
    private val port: Int = RemoteKeys.DEFAULT_PORT,
) {
    private val lifecycle = Mutex()
    private var session = ServerSession()

    val status: StateFlow<ServerStatus>
        field = MutableStateFlow(ServerStatus(connectedClients = 0, volumeLevel = 0, isMuted = false))

    val runtimeStatus: StateFlow<ServerRuntimeStatus>
        field = MutableStateFlow(ServerRuntimeStatus(port = port))

    private val clients = mutableListOf<ClientSlot>()
    private var nextClientId = 1L

    @Volatile
    private var volumeSnapshot = VolumeSnapshot(level = -1, muted = false)

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
        val down = lifecycle.withLock {
            if (session.shutdown) return
            session.engine == null ||
                session.phase == KtorServerState.Stopped ||
                session.phase == KtorServerState.Error
        }
        if (down) bringUp("ensure running")
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

        val listening = lifecycle.withLock {
            if (session.shutdown || session.engine == null) {
                stopEngineLocked()
                if (!session.shutdown) {
                    session = session.copy(phase = KtorServerState.Error)
                    publishRuntimeLocked()
                }
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
        if (!listening) return
        ensureFirewallRule()

        if (!awaitHealthy()) {
            log("local health probe did not succeed yet; server stays up and will be rechecked")
        }
    }

    private suspend fun bindServer() {
        var lastFailure: Throwable? = null
        repeat(BIND_ATTEMPTS) { attempt ->
            try {
                // createEngine() is a plain function so Ktor does not parent the server
                // on this coroutine. startSuspend uses a blocking dispatcher; start() from
                // Dispatchers.IO never returns because the server job outlives the caller.
                val started = createEngine().startSuspend(wait = false)
                session = session.copy(engine = started)
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

    private fun createEngine(): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
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
                            name = machineName,
                            port = port,
                            version = AppVersion.VERSION,
                        ),
                    )
                }

                webSocket(RemoteProtocol.WS_PATH) {
                    val slot = registerClient(this)
                    log("client connected (${slot.name})")
                    publishVolumeStatus()

                    try {
                        slot.sendMessage(ServerMessage.Status(currentStatus()))

                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                runCatching {
                                    handleCommand(slot, frame.readText())
                                }.onFailure { error ->
                                    log("command failed: ${error.message}", error)
                                    runCatching {
                                        slot.sendMessage(
                                            ServerMessage.Error(error.message ?: "Command failed"),
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
                        unregisterClient(slot)
                        log("client disconnected (${slot.name})")
                        publishVolumeStatus()
                    }
                }
            }
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
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS.toInt())
            }
            true
        }.getOrDefault(false)
    }

    private suspend fun stopEngineLocked() {
        val current = session.engine ?: return
        session = session.copy(engine = null)
        runCatching {
            current.stopSuspend(gracePeriodMillis = 200, timeoutMillis = 1_000)
        }.onFailure { error ->
            log("stop failed: ${error.message}", error)
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
            firewall.allowIncomingTcp(port)
        } ?: return

        lifecycle.withLock {
            session = session.copy(warning = firewallWarning)
            publishRuntimeLocked()
        }
        log(firewallWarning)
    }

    fun observeSystemVolume(): Flow<ServerStatus> = flow {
        while (currentCoroutineContext().isActive) {
            delay(VOLUME_POLL_INTERVAL_MS.milliseconds)
            val changed = try {
                readSystemVolumeChange()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log("volume sync failed: ${error.message}")
                null
            } ?: continue
            emit(changed)
        }
    }

    suspend fun shareStatus(serverStatus: ServerStatus) {
        broadcast(ServerMessage.Status(serverStatus))
    }

    private suspend fun readSystemVolumeChange(): ServerStatus? {
        if (lifecycle.withLock { session.shutdown || session.phase != KtorServerState.Running }) return null

        val before = volumeSnapshot
        val latest = commandExecutor.currentStatusIfReadable() ?: return null
        if (latest.volumeLevel == before.level && latest.isMuted == before.muted) return null

        val published = status.updateAndGet { current ->
            if (current.volumeLevel != before.level || current.isMuted != before.muted) {
                current
            } else {
                latest.copy(
                    connectedClients = clientCount(),
                    lastCommand = current.lastCommand,
                )
            }
        }
        if (published.volumeLevel == before.level && published.isMuted == before.muted) return null
        volumeSnapshot = VolumeSnapshot(published.volumeLevel, published.isMuted)
        return published
    }

    private suspend fun handleCommand(slot: ClientSlot, text: String) {
        val command = RemoteJson.decode<RemoteCommand>(text)
        if (command is RemoteCommand.Hello) {
            val name = sanitizeClientName(command.name)
            synchronized(clients) { slot.name = name }
            publishClients()
            log("client ${slot.id} is $name")
            return
        }

        val newStatus = commandExecutor.execute(command).copy(connectedClients = clientCount())
        volumeSnapshot = VolumeSnapshot(newStatus.volumeLevel, newStatus.isMuted)
        status.value = newStatus
        slot.sendMessage(ServerMessage.Ack(command, success = true))
        broadcast(ServerMessage.Status(newStatus))
    }

    private fun registerClient(session: DefaultWebSocketServerSession): ClientSlot {
        val remote = session.call.request.local.remoteHost.ifBlank { "Client" }
        val slot = synchronized(clients) {
            ClientSlot(
                id = nextClientId++,
                session = session,
                name = remote,
            ).also(clients::add)
        }
        publishClients()
        return slot
    }

    private fun unregisterClient(slot: ClientSlot) {
        synchronized(clients) { clients.remove(slot) }
        publishClients()
    }

    private suspend fun broadcast(message: ServerMessage) {
        val slots = synchronized(clients) { clients.toList() }
        for (slot in slots) {
            runCatching { slot.sendMessage(message) }
        }
    }

    private suspend fun publishVolumeStatus() {
        val latest = commandExecutor.currentStatus()
        volumeSnapshot = VolumeSnapshot(latest.volumeLevel, latest.isMuted)
        status.update { current ->
            latest.copy(
                connectedClients = clientCount(),
                lastCommand = current.lastCommand,
            )
        }
    }

    private suspend fun currentStatus(): ServerStatus =
        commandExecutor.currentStatus().copy(connectedClients = clientCount())

    private fun clientCount(): Int = synchronized(clients) { clients.size }

    private fun clientNames(): List<String> = synchronized(clients) { clients.map { it.name } }

    private fun publishClients() {
        val names = clientNames()
        runtimeStatus.update { current ->
            current.copy(connectedClients = names.size, clientNames = names)
        }
        status.update { current -> current.copy(connectedClients = names.size) }
    }

    private fun sanitizeClientName(name: String): String =
        name.replace(Regex("\\s+"), " ").trim().take(CLIENT_NAME_MAX).ifBlank { "Client" }

    private fun publishRuntimeLocked() {
        val snapshot = session
        val addresses = readCandidates()
        val reachable = addresses.filter { it.score > 0 }
        runtimeStatus.update { current ->
            ServerRuntimeStatus(
                ktorState = snapshot.phase,
                networkState = if (reachable.isEmpty()) NetworkState.Unavailable else NetworkState.Available,
                connectedClients = clientCount(),
                clientNames = clientNames(),
                localAddresses = addresses.map { it.address },
                recommendedAddress = reachable.maxByOrNull { it.score }?.address,
                lastError = snapshot.lastError,
                warning = snapshot.warning,
                restartCount = snapshot.restarts,
                port = port,
            )
        }
    }

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

    private class ClientSlot(
        val id: Long,
        val session: DefaultWebSocketServerSession,
        var name: String,
    ) {
        private val sendLock = Mutex()

        suspend fun sendMessage(message: ServerMessage) {
            sendLock.withLock {
                session.send(Frame.Text(RemoteJson.encodeMessage(message)))
            }
        }
    }

    private data class VolumeSnapshot(
        val level: Int,
        val muted: Boolean,
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
        const val CLIENT_NAME_MAX = 48
        const val VOLUME_POLL_INTERVAL_MS = 400L

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
