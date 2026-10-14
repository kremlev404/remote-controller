package com.remotecontroller.desktop.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds

class ServerLifecycle(
    private val commandServer: CommandServer,
    private val keepAlive: ServerKeepAlive,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    fun start() {
        if (started) return
        started = true
        keepAlive.start()

        scope.launch {
            commandServer.start()
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS.milliseconds)
                try {
                    commandServer.ensureRunning()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    println("[CommandServer] ensureRunning failed: ${error.message}")
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(NETWORK_REFRESH_INTERVAL_MS.milliseconds)
                try {
                    commandServer.refreshStatus()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    println("[CommandServer] refreshStatus failed: ${error.message}")
                }
            }
        }

        scope.launch {
            commandServer.observeSystemVolume().collect(commandServer::shareStatus)
        }
    }

    fun shutdown() {
        if (!started) return
        started = false
        runBlocking(Dispatchers.IO) {
            commandServer.stop()
            keepAlive.stop()
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    private companion object {
        const val HEALTH_CHECK_INTERVAL_MS = 2_000L
        const val NETWORK_REFRESH_INTERVAL_MS = 2_000L
    }
}
