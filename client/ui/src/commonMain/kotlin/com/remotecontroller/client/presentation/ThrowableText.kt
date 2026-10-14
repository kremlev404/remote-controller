package com.remotecontroller.client.presentation

fun Throwable.describeForUi(): String =
    generateSequence(this) { it.cause }
        .take(8)
        .joinToString("\n") { error ->
            val name = error::class.simpleName ?: "Error"
            val message = error.message?.takeIf { it.isNotBlank() }
            if (message == null) name else "$name: $message"
        }
