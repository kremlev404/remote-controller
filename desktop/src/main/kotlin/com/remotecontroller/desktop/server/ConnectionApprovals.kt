package com.remotecontroller.desktop.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

data class ApprovalRequest(
    val id: Long,
    val name: String,
    val address: String,
    val checkCode: String,
    val viaRelay: Boolean,
)

/**
 * Connections without an access code wait here until the owner presses Allow or Deny.
 * Unanswered requests are denied after a minute; extra requests beyond a few are denied at once.
 */
class ConnectionApprovals {
    private val answers = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val nextId = AtomicLong(1)

    val pending: StateFlow<List<ApprovalRequest>>
        field = MutableStateFlow(emptyList())

    suspend fun ask(name: String, address: String, checkCode: String, viaRelay: Boolean): Boolean {
        if (pending.value.size >= MAX_PENDING) return false
        val request = ApprovalRequest(nextId.getAndIncrement(), name, address, checkCode, viaRelay)
        val answer = CompletableDeferred<Boolean>()
        answers[request.id] = answer
        pending.update { it + request }
        return try {
            withTimeoutOrNull(TIMEOUT) { answer.await() } ?: false
        } finally {
            answers.remove(request.id)
            pending.update { list -> list.filterNot { it.id == request.id } }
        }
    }

    fun answer(id: Long, allow: Boolean) {
        answers[id]?.complete(allow)
    }

    fun denyAll() {
        answers.values.forEach { it.complete(false) }
    }

    private companion object {
        const val MAX_PENDING = 3
        val TIMEOUT = 60.seconds
    }
}
