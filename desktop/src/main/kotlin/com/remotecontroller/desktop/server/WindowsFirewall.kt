package com.remotecontroller.desktop.server

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Best-effort inbound rule so a phone on the LAN can reach the server.
 * Adding a rule needs an elevated process; when that fails the caller surfaces a warning.
 */
internal object WindowsFirewall : IncomingFirewall {
    override fun accessWarning(port: Int): String? =
        if (alreadyAllowed()) null else warning(port)

    override fun allowIncomingTcp(port: Int): String? {
        if (alreadyAllowed()) return null
        val added = runNetsh(
            "advfirewall", "firewall", "add", "rule",
            "name=$RULE_NAME",
            "dir=in",
            "action=allow",
            "protocol=TCP",
            "localport=$port",
            "profile=private",
        )
        return if (added == 0 || alreadyAllowed()) null else warning(port)
    }

    override fun requestAccess(port: Int): String? {
        if (alreadyAllowed()) return null
        launchElevatedRule(port)
        return if (alreadyAllowed()) null else warning(port)
    }

    private fun alreadyAllowed(): Boolean =
        hasNamedRule(RULE_NAME) || currentProgramAlreadyAllowed()

    private fun warning(port: Int): String =
        "Windows Firewall may block port $port. Allow Remote Controller Server on private networks, otherwise the phone cannot connect."

    private fun launchElevatedRule(port: Int) {
        val netsh = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32/netsh.exe").absolutePath
        val command = "Start-Process -FilePath '$netsh' -Verb RunAs -WindowStyle Hidden -ArgumentList " +
            "'advfirewall','firewall','add','rule','name=$RULE_NAME','dir=in','action=allow'," +
            "'protocol=TCP','localport=$port','profile=private'"
        runCatching {
            val process = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-Command",
                command,
            )
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!process.waitFor(ELEVATION_WAIT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                println("[CommandServer] firewall elevation timed out")
            }
        }.onFailure { error ->
            println("[CommandServer] firewall elevation failed: ${error.message}")
        }
    }

    private fun hasNamedRule(ruleName: String): Boolean =
        runNetsh("advfirewall", "firewall", "show", "rule", "name=$ruleName", quiet = true) == 0

    /**
     * The Windows "Allow" prompt creates a rule named after the exe, not [RULE_NAME].
     * That rule already lets the phone connect, so it must not raise the warning.
     */
    private fun currentProgramAlreadyAllowed(): Boolean {
        val executable = ProcessHandle.current().info().command().orElse("")
        val fileName = File(executable).name
        if (fileName.isEmpty()) return false
        val output = netshOutput(
            "advfirewall", "firewall", "show", "rule", "name=$fileName", "verbose",
            quiet = true,
        ) ?: return false
        return output.split("Rule Name:")
            .drop(1)
            .any { block -> allowsThisProgram(block, executable) }
    }

    private fun allowsThisProgram(block: String, executable: String): Boolean {
        val enabled = field(block, "Enabled").equals("Yes", ignoreCase = true)
        val inbound = field(block, "Direction").equals("In", ignoreCase = true)
        val allow = field(block, "Action").equals("Allow", ignoreCase = true)
        val protocol = field(block, "Protocol")
        val tcp = protocol.equals("TCP", ignoreCase = true) || protocol.equals("Any", ignoreCase = true)
        val profiles = field(block, "Profiles").orEmpty()
        val privateNetwork = profiles.equals("Any", ignoreCase = true) ||
            profiles.split(',').any { it.trim().equals("Private", ignoreCase = true) }
        val program = field(block, "Program") ?: return false
        return enabled && inbound && allow && tcp && privateNetwork && samePath(program, executable)
    }

    private fun field(block: String, label: String): String? =
        block.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$label:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun samePath(left: String, right: String): Boolean =
        File(left).path.equals(File(right).path, ignoreCase = true)

    private fun netshOutput(vararg args: String, quiet: Boolean = false): String? {
        val output = StringBuilder()
        return if (runNetsh(output, quiet, *args) == 0) output.toString() else null
    }

    private fun runNetsh(vararg args: String, quiet: Boolean = false): Int = runNetsh(null, quiet, *args)

    private fun runNetsh(output: StringBuilder?, quiet: Boolean, vararg args: String): Int {
        return runCatching {
            val process = ProcessBuilder(listOf("netsh", *args))
                .redirectErrorStream(true)
                .start()
            val text = StringBuilder()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { text.append(it.readText()) }
                }
            }
            reader.isDaemon = true
            reader.start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                reader.join(1_000)
                println("[CommandServer] netsh timed out: ${args.joinToString(" ")}")
                return -1
            }
            reader.join(1_000)
            output?.append(text)
            val code = process.exitValue()
            if (code != 0 && !quiet) {
                println("[CommandServer] netsh ${args.joinToString(" ")} exited $code")
            }
            code
        }.getOrElse { error ->
            println("[CommandServer] netsh failed: ${error.message}")
            -1
        }
    }

    private const val RULE_NAME = "Remote Controller Server"
    private const val ELEVATION_WAIT_SECONDS = 120L
}
