package com.remotecontroller.desktop.server

import com.remotecontroller.desktop.platform.LinuxCommands

/**
 * Most desktop installs (Ubuntu, Mint, Debian) ship with no active firewall; Fedora runs firewalld.
 * Changing rules needs root, so this only points the user at the one command to run.
 */
internal object LinuxFirewall : IncomingFirewall {
    override fun allowIncomingTcp(port: Int): String? = accessWarning(port)

    override fun accessWarning(port: Int): String? {
        if (!LinuxCommands.exists("firewall-cmd")) return null
        if (LinuxCommands.run("firewall-cmd", "--state") != "running") return null
        val open = LinuxCommands.run("firewall-cmd", "--query-port=$port/tcp") == "yes"
        if (open) return null
        return "firewalld blocks port $port. Run: sudo firewall-cmd --permanent --add-port=$port/tcp && sudo firewall-cmd --reload"
    }

    override fun requestAccess(port: Int): String? {
        // pkexec shows the desktop's own password dialog.
        if (LinuxCommands.exists("pkexec") && accessWarning(port) != null) {
            LinuxCommands.run("pkexec", "firewall-cmd", "--permanent", "--add-port=$port/tcp")
            LinuxCommands.run("pkexec", "firewall-cmd", "--reload")
        }
        return accessWarning(port)
    }
}
