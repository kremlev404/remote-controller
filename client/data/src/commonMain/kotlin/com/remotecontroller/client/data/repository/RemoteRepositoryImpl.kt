package com.remotecontroller.client.data.repository

import com.remotecontroller.client.data.AppScope
import com.remotecontroller.client.data.log.logInfo
import com.remotecontroller.client.data.log.logWarn
import com.remotecontroller.client.data.remote.LinkSessions
import com.remotecontroller.client.data.remote.PcLink
import com.remotecontroller.client.data.remote.PcTransport
import com.remotecontroller.client.data.remote.ServerTrust
import com.remotecontroller.client.data.remote.SessionClosedByHost
import com.remotecontroller.client.domain.model.NetworkMonitor
import com.remotecontroller.client.domain.model.ConnectionState
import com.remotecontroller.client.domain.model.PcConnection
import com.remotecontroller.client.domain.repository.RemoteRepository
import com.remotecontroller.shared.model.RemoteCommand
import com.remotecontroller.shared.model.ServerStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class RemoteRepositoryImpl(
    private val transport: PcTransport,
    private val network: NetworkMonitor,
    private val appScope: AppScope,
) : RemoteRepository {

    override val connectionState: StateFlow<ConnectionState>
        field = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

    override val activeConnection: StateFlow<PcConnection?>
        field = MutableStateFlow(null)

    override val serverStatus: StateFlow<ServerStatus?>
        field = MutableStateFlow(null)

    override val approvalCode: StateFlow<String?> = LinkSessions.awaitingApproval

    private val gate = Mutex()
    private var epoch = 0
    private var session: Job? = null
    private var target: PcConnection? = null
    private var live: PcLink? = null
    private var lastFailure: Throwable? = null

    override suspend fun connect(connection: PcConnection): Result<Unit> =
        replaceSession(connection, reconnecting = false)

    override suspend fun retryNow(): Result<Unit> {
        val connection = gate.withLock { target }
            ?: return Result.failure(IllegalStateException("No previous connection to retry"))
        return replaceSession(connection, reconnecting = true)
    }

    override suspend fun disconnect() {
        val job = gate.withLock {
            epoch += 1
            live = null
            session.also { session = null }
        }
        connectionState.value = ConnectionState.Disconnected
        activeConnection.value = null
        serverStatus.value = null
        job?.cancel()
    }

    override suspend fun sendCommand(command: RemoteCommand): Result<Unit> = runCatching {
        val (socket, failure) = gate.withLock { live to lastFailure }
        val active = socket ?: throw failure ?: IllegalStateException("WebSocket session is null")
        try {
            active.send(command)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            gate.withLock { lastFailure = error }
            logWarn(TAG, "send failed", error)
            throw error
        }
    }

    private suspend fun replaceSession(connection: PcConnection, reconnecting: Boolean): Result<Unit> {
        val ready = CompletableDeferred<Result<Unit>>()
        val (epoch, previous) = gate.withLock {
            target = connection
            this.epoch += 1
            val previous = session
            session = null
            this.epoch to previous
        }
        previous?.cancel()
        val job = appScope.launch { runSession(connection, reconnecting, ready, epoch) }
        gate.withLock {
            if (this.epoch == epoch) session = job else job.cancel()
        }
        return ready.await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runSession(
        connection: PcConnection,
        startAsReconnect: Boolean,
        ready: CompletableDeferred<Result<Unit>>,
        epoch: Int,
    ) {
        val endpoint = "${connection.host.value}:${connection.port.value}"
        var wasUp = false
        var reconnecting = startAsReconnect
        var backoff = INITIAL_RECONNECT_DELAY
        try {
            while (currentCoroutineContext().isActive && isCurrent(epoch)) {
                if (wasUp && !network.status.value.isUsableForLocalPc) {
                    publishState(epoch, ConnectionState.Reconnecting)
                    delay(NETWORK_WAIT)
                    continue
                }

                publishState(
                    epoch,
                    if (reconnecting || wasUp) ConnectionState.Reconnecting else ConnectionState.Connecting,
                )
                logInfo(TAG, "connecting to $endpoint")

                try {
                    holdUntilDrop(connection, ready, epoch) { status ->
                        acceptStatus(epoch, connection, endpoint, ready, status)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: SessionClosedByHost) {
                    logInfo(TAG, "session to $endpoint closed by the PC")
                    if (!ready.isCompleted) ready.complete(Result.failure(error))
                    publishActive(epoch, null)
                    publishState(epoch, ConnectionState.Disconnected)
                    return
                } catch (error: Throwable) {
                    logWarn(TAG, "session to $endpoint ended", error)
                    gate.withLock { if (this.epoch == epoch) lastFailure = error }
                    val established = ready.isCompleted && ready.getCompleted().isSuccess
                    if (!established) {
                        if (!ready.isCompleted) ready.complete(Result.failure(error))
                        publishActive(epoch, null)
                        publishState(epoch, ConnectionState.Failed)
                        return
                    }
                    wasUp = true
                }

                if (!isCurrent(epoch)) return
                reconnecting = true
                publishState(epoch, ConnectionState.Reconnecting)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_RECONNECT_DELAY)
            }
        } catch (error: CancellationException) {
            if (!ready.isCompleted) ready.complete(Result.failure(error))
            throw error
        }
    }

    private suspend fun acceptStatus(
        epoch: Int,
        connection: PcConnection,
        endpoint: String,
        ready: CompletableDeferred<Result<Unit>>,
        status: ServerStatus,
    ) {
        val accepted = gate.withLock {
            if (this.epoch != epoch) return@withLock false
            serverStatus.value = status
            if (ready.isCompleted) return@withLock false
            lastFailure = null
            true
        }
        if (!accepted) return
        publishActive(epoch, connection)
        publishState(epoch, ConnectionState.Connected)
        ready.complete(Result.success(Unit))
        logInfo(TAG, "connected to $endpoint")
    }

    private suspend fun holdUntilDrop(
        connection: PcConnection,
        ready: CompletableDeferred<Result<Unit>>,
        epoch: Int,
        onStatus: suspend (ServerStatus) -> Unit,
    ): Nothing {
        ServerTrust.fingerprint = connection.certFingerprint
        val link = transport.open(connection.host, connection.port, connection.accessCode)
        val attached = gate.withLock {
            if (this.epoch != epoch) false else {
                live = link
                true
            }
        }
        try {
            if (!attached) {
                throw CancellationException("Connection cancelled")
            }
            coroutineScope {
                val reader = launch {
                    link.readUntilClosed(onStatus)
                    if (!ready.isCompleted) {
                        ready.complete(Result.failure(IllegalStateException("WebSocket closed before status")))
                    }
                }
                try {
                    val first = withTimeoutOrNull(STATUS_TIMEOUT) { ready.await() }
                        ?: throw IllegalStateException("Timed out waiting for server status")
                    first.getOrThrow()
                    reader.join()
                } finally {
                    reader.cancel()
                }
            }
            error("WebSocket closed by the server")
        } finally {
            val clearStatus = gate.withLock {
                if (live === link) live = null
                this.epoch == epoch
            }
            link.close()
            if (clearStatus) serverStatus.value = null
        }
    }

    private suspend fun isCurrent(epoch: Int): Boolean = gate.withLock { this.epoch == epoch }

    private suspend fun publishState(epoch: Int, state: ConnectionState) {
        gate.withLock {
            if (this.epoch != epoch) return
            connectionState.value = state
        }
    }

    private suspend fun publishActive(epoch: Int, connection: PcConnection?) {
        gate.withLock {
            if (this.epoch != epoch) return
            activeConnection.value = connection
        }
    }

    private companion object {
        const val TAG = "RemoteConnection"
        val INITIAL_RECONNECT_DELAY = 1.seconds
        val MAX_RECONNECT_DELAY = 15.seconds
        val NETWORK_WAIT = 1.seconds
        val STATUS_TIMEOUT = 8.seconds
    }
}
