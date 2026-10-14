package com.remotecontroller.desktop.server

import java.util.concurrent.TimeUnit

/**
 * Best-effort inbound rule so a phone on the LAN can reach the server.
 * Adding a rule needs an elevated process; when that fails the caller surfaces a warning.
 */
internal object WindowsFirewall {
    fun allowIncomingTcp(port: Int): String? {
        val ruleName = "Remote Controller Server"
        if (runNetsh("advfirewall", "firewall", "show", "rule", "name=$ruleName") == 0) {
            return null
        }

        val added = runNetsh(
            "advfirewall", "firewall", "add", "rule",
            "name=$ruleName",
            "dir=in",
            "action=allow",
            "protocol=TCP",
            "localport=$port",
            "profile=any",
        )
        return if (added == 0) {
            null
        } else {
            "Windows Firewall may block port $port. Allow Remote Controller Server on private networks, otherwise the phone cannot connect."
        }
    }

    private fun runNetsh(vararg args: String): Int {
        return runCatching {
            val process = ProcessBuilder(listOf("netsh", *args))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                println("[CommandServer] netsh timed out: ${args.joinToString(" ")}")
                return -1
            }
            val code = process.exitValue()
            if (code != 0) {
                println("[CommandServer] netsh ${args.joinToString(" ")} exited $code")
            }
            code
        }.getOrElse { error ->
            println("[CommandServer] netsh failed: ${error.message}")
            -1
        }
    }
}
