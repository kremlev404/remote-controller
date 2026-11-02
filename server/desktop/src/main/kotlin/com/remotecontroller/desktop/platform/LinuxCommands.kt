package com.remotecontroller.desktop.platform

import java.io.File
import java.util.concurrent.TimeUnit

/** Small helpers for the command-line tools Linux desktops already ship (wpctl, pactl, playerctl…). */
internal object LinuxCommands {
    private val path: List<File> = System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map(::File)

    fun exists(tool: String): Boolean = path.any { File(it, tool).canExecute() }

    /** Output of [command], or null if it failed, timed out, or the tool is missing. */
    fun run(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(command.toList())
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.exitValue() != 0) null else output
    }.getOrNull()
}
