package com.remotecontroller.desktop.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class ServerLogLine(
    val time: String,
    val message: String,
    val error: Boolean,
)

internal object ServerLog {
    private const val CAPACITY = 40
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val _lines = MutableStateFlow<List<ServerLogLine>>(emptyList())
    val lines: StateFlow<List<ServerLogLine>> = _lines

    fun record(message: String, error: Boolean = false) {
        val line = ServerLogLine(
            time = LocalTime.now().format(clock),
            message = message,
            error = error,
        )
        _lines.update { current -> (current + line).takeLast(CAPACITY) }
    }
}
