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
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocketRaw
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

    @Volatile
    private var listenHost = "127.0.0.1"

    val localAddresses: List<String>
        get() = readCandidates().map { it.address }

    val recommendedAddress: String?
        get() = preferredPrivateHost()

    suspend fun start() {
        lifecycle.withLock { session = session.copy(shutdown = false) }
        bringUp("initial start")
    }

    suspend fun restart() {
        lifecycle.withLock {
            if (session.shutdown) return
            clearClientsLocked()
            session = session.copy(phase = KtorServerState.Restarting, lastError = null)
            publishRuntimeLocked()
            stopEngineLocked()
        }
        bringUp("manual restart")
    }

    suspend fun requestFirewallAccess() {
        withContext(Dispatchers.IO) {
            firewall.requestAccess(port)
        }
        repeat(FIREWALL_SETTLE_POLLS) {
            refreshFirewall()
            val granted = lifecycle.withLock { session.warning == null }
            if (granted) return
            delay(FIREWALL_SETTLE_DELAY_MS.milliseconds)
        }
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
        val rebind = lifecycle.withLock {
            publishRuntimeLocked()
            val desired = preferredPrivateHost()
                ?: session.bindHost?.takeUnless { it == WILDCARD_HOST }
                ?: WILDCARD_HOST
            session.phase == KtorServerState.Running &&
                session.engine != null &&
                session.bindHost != desired
        }
        if (rebind) {
            log("private network changed, rebinding Ktor")
            restart()
            return
        }
        refreshFirewall()
    }

    private suspend fun bringUp(reason: String) {
        val bound = lifecycle.withLock {
            if (session.shutdown) return
            if (session.engine != null && session.phase == KtorServerState.Running) return
            if (session.engine != null) stopEngineLocked()

            val host = preferredPrivateHost() ?: WILDCARD_HOST
            session = session.copy(phase = KtorServerState.Starting, lastError = null, bindHost = host)
            publishRuntimeLocked()
            log("starting Ktor ($reason) on $host:$port")

            try {
                bindServer(host)
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
                log("listening on ${session.bindHost ?: WILDCARD_HOST}:$port, recommended ${recommendedAddress ?: "no LAN address"}")
                true
            }
        }
        if (!listening) return
        ensureFirewallRule()

        if (!awaitHealthy()) {
            log("local health probe did not succeed yet; server stays up and will be rechecked")
        }
    }

    private suspend fun bindServer(host: String) {
        var lastFailure: Throwable? = null
        repeat(BIND_ATTEMPTS) { attempt ->
            try {
                // createEngine() is a plain function so Ktor does not parent the server
                // on this coroutine. startSuspend uses a blocking dispatcher; start() from
                // Dispatchers.IO never returns because the server job outlives the caller.
                val started = createEngine(host).startSuspend(wait = false)
                listenHost = if (host == WILDCARD_HOST) "127.0.0.1" else host
                session = session.copy(engine = started, bindHost = host)
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

    private fun createEngine(host: String): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        val listenPort = port
        return embeddedServer(Netty, configure = {
            connector {
                this.host = host
                this.port = listenPort
            }
            channelPipelineConfig = {
                SocketFrames.attach(channel())
            }
        }) {
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

                webSocketRaw(RemoteProtocol.WS_PATH) {
                    val slot = registerClient(this)
                    log("client connected (${slot.name})")
                    var outcome = "closed"

                    try {
                        val status = currentStatus()
                        slot.sendMessage(ServerMessage.Status(status))
                        log("status sent to ${slot.name}")
                        publishVolumeStatus()

                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> runCatching {
                                    handleCommand(slot, frame.readText())
                                }.onFailure { error ->
                                    log("command failed: ${error.message}", error)
                                    runCatching {
                                        slot.sendMessage(
                                            ServerMessage.Error(error.message ?: "Command failed"),
                                        )
                                    }
                                }
                                is Frame.Ping -> runCatching { SocketFrames.sendPong(slot.endpoint, frame.data) }
                                is Frame.Close -> break
                                else -> Unit
                            }
                        }
                    } catch (error: CancellationException) {
                        if (!slot.closedFromDesktop) {
                            outcome = "dropped (${error.message ?: error.javaClass.simpleName})"
                        }
                        throw error
                    } catch (error: Exception) {
                        if (!slot.closedFromDesktop) {
                            outcome = error.message ?: "error"
                            log("client socket closed: $outcome", error)
                        }
                    } finally {
                        if (slot.closedFromDesktop) outcome = "closed from desktop"
                        unregisterClient(slot)
                        log(
                            "client disconnected (${slot.name}): $outcome",
                            isError = outcome != "closed" && outcome != "closed from desktop",
                        )
                        publishVolumeStatus()
                    }
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
        val target = listenHost
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(target, port), PROBE_TIMEOUT_MS.toInt())
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
        val firewallWarning = withContext(Dispatchers.IO) {
            firewall.allowIncomingTcp(port)
        }
        applyFirewallWarning(firewallWarning)
    }

    private suspend fun refreshFirewall() {
        val firewallWarning = withContext(Dispatchers.IO) {
            firewall.accessWarning(port)
        }
        applyFirewallWarning(firewallWarning)
    }

    private suspend fun applyFirewallWarning(firewallWarning: String?) {
        val changed = lifecycle.withLock {
            if (session.warning == firewallWarning) return@withLock false
            session = session.copy(warning = firewallWarning)
            publishRuntimeLocked()
            true
        }
        if (changed && firewallWarning != null) log(firewallWarning, isError = true)
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

    private fun registerClient(session: WebSocketServerSession): ClientSlot {
        val point = session.call.request.local
        val remote = point.remoteAddress.ifBlank { "Client" }
        val slot = synchronized(clients) {
            ClientSlot(
                id = nextClientId++,
                endpoint = SocketFrames.endpoint(remote, point.remotePort),
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

    suspend fun disconnectClient(id: Long) {
        val slot = synchronized(clients) {
            clients.firstOrNull { it.id == id }?.also { it.closedFromDesktop = true }
        } ?: return
        log("closing ${slot.name}")
        runCatching { slot.sendMessage(ServerMessage.Disconnect()) }
        SocketFrames.close(slot.endpoint)
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

    private fun connectedClients(): List<ConnectedClient> = synchronized(clients) {
        clients.map { ConnectedClient(id = it.id, name = it.name) }
    }

    private fun publishClients() {
        val connected = connectedClients()
        runtimeStatus.update { current ->
            current.copy(connectedClients = connected.size, clients = connected)
        }
        status.update { current -> current.copy(connectedClients = connected.size) }
    }

    private fun sanitizeClientName(name: String): String =
        name.replace(Regex("\\s+"), " ").trim().take(CLIENT_NAME_MAX).ifBlank { "Client" }

    private fun publishRuntimeLocked() {
        val snapshot = session
        val addresses = readCandidates()
        val reachable = addresses
        runtimeStatus.update { current ->
            ServerRuntimeStatus(
                ktorState = snapshot.phase,
                networkState = if (reachable.isEmpty()) NetworkState.Unavailable else NetworkState.Available,
                connectedClients = clientCount(),
                clients = connectedClients(),
                localAddresses = addresses.map { it.address },
                recommendedAddress = reachable.maxWithOrNull(
                    compareBy<LocalAddress> { it.score }.thenBy { it.address },
                )?.address,
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

    private fun preferredPrivateHost(): String? =
        readCandidates().maxWithOrNull(compareBy<LocalAddress> { it.score }.thenBy { it.address })?.address

    private fun addressesOf(nic: NetworkInterface): List<LocalAddress> {
        if (!nic.isUp || nic.isLoopback || runCatching { nic.isPointToPoint }.getOrDefault(false)) return emptyList()
        val label = "${nic.displayName} ${nic.name}".lowercase()
        if (VIRTUAL_MARKERS.any { marker -> label.contains(marker) }) return emptyList()
        return nic.interfaceAddresses.mapNotNull { binding ->
            val inet = binding.address as? Inet4Address ?: return@mapNotNull null
            if (inet.isLoopbackAddress || inet.isLinkLocalAddress) return@mapNotNull null
            val host = inet.hostAddress ?: return@mapNotNull null
            if (!isPrivateLan(host)) return@mapNotNull null
            val prefix = binding.networkPrefixLength.toInt()
            if (prefix in 30..32) return@mapNotNull null
            LocalAddress(address = host, score = scoreAddress(label, host))
        }
    }

    private fun isPrivateLan(address: String): Boolean {
        if (address.startsWith("192.168.") || address.startsWith("10.")) return true
        if (!address.startsWith("172.")) return false
        val second = address.split('.').getOrNull(1)?.toIntOrNull() ?: return false
        return second in 16..31
    }

    private fun scoreAddress(label: String, address: String): Int {
        var score = 1
        if (label.contains("wi-fi") || label.contains("wifi") || label.contains("wlan") || label.contains("wireless")) {
            score += 300
        }
        if (label.contains("ethernet") || label.contains("lan")) score += 200
        if (address.startsWith("192.168.")) score += 50
        return score
    }

    private fun clearClientsLocked() {
        synchronized(clients) { clients.clear() }
        publishClients()
    }

    private fun describeBindFailure(error: Throwable): String {
        val bind = generateSequence(error) { it.cause }.filterIsInstance<BindException>().firstOrNull()
        if (bind != null) {
            return "Port $port is already in use. Close the other Remote Controller Server window."
        }
        return error.message ?: "Failed to start Ktor server"
    }

    private fun log(message: String, error: Throwable? = null, isError: Boolean = error != null) {
        println("[CommandServer] $message")
        ServerLog.record(message, error = isError)
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
        val bindHost: String? = null,
    )

    private class ClientSlot(
        val id: Long,
        val endpoint: String,
        var name: String,
    ) {
        @Volatile
        var closedFromDesktop: Boolean = false
        private val sendLock = Mutex()

        suspend fun sendMessage(message: ServerMessage) {
            sendLock.withLock {
                SocketFrames.sendText(endpoint, RemoteJson.encodeMessage(message))
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
        const val FIREWALL_SETTLE_POLLS = 8
        const val FIREWALL_SETTLE_DELAY_MS = 500L
        const val WILDCARD_HOST = "0.0.0.0"

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
            "wintun",
            "tunnel",
            "loopback",
            "pseudo",
            "bluetooth",
            "nordlynx",
            "wireguard",
            "tailscale",
            "zerotier",
            "hamachi",
            "anyconnect",
            "cisco",
            "openvpn",
            "mullvad",
            "expressvpn",
            "surfshark",
            "proton",
            "warp",
            "fortinet",
            "forticlient",
            "globalprotect",
            "pulse secure",
            "zscaler",
            "netskope",
            "cloudflare",
        )
    }
}
