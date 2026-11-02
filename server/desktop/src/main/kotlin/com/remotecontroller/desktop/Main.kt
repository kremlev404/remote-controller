package com.remotecontroller.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.remotecontroller.desktop.di.desktopAppModule
import com.remotecontroller.desktop.platform.applySystemTitleBar
import com.remotecontroller.desktop.server.ConnectionApprovals
import com.remotecontroller.desktop.server.DesktopSettings
import com.remotecontroller.desktop.server.ServerLifecycle
import com.remotecontroller.desktop.ui.DesktopApp
import com.remotecontroller.desktop.ui.RemoteServerAppIcon
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

fun main() {
    startKoin {
        modules(desktopAppModule)
    }

    val serverLifecycle = GlobalContext.get().get<ServerLifecycle>()
    serverLifecycle.start()
    Runtime.getRuntime().addShutdownHook(Thread {
        serverLifecycle.shutdown()
    })

    val approvals = GlobalContext.get().get<ConnectionApprovals>()
    val settings = GlobalContext.get().get<DesktopSettings>()

    application {
        var windowVisible by remember { mutableStateOf(true) }
        val pending by approvals.pending.collectAsState()
        val darkTheme by settings.darkTheme.collectAsState()
        // A device is waiting for Allow: show the window even if it sits in the tray.
        LaunchedEffect(pending.size) {
            if (pending.isNotEmpty()) windowVisible = true
        }

        ApplicationContent(
            windowVisible = windowVisible,
            approvalsWaiting = pending.size,
            chosenDarkTheme = darkTheme,
            onHideWindow = { windowVisible = false },
            onExitApplication = {
                serverLifecycle.shutdown()
                stopKoin()
                exitApplication()
            },
            onShowWindow = { windowVisible = true },
        )
    }
}

@Composable
private fun ApplicationScope.ApplicationContent(
    windowVisible: Boolean,
    approvalsWaiting: Int,
    chosenDarkTheme: Boolean?,
    onHideWindow: () -> Unit,
    onExitApplication: () -> Unit,
    onShowWindow: () -> Unit,
) {
    val appIcon = rememberVectorPainter(RemoteServerAppIcon)

    if (windowVisible) {
        Window(
            onCloseRequest = onHideWindow,
            title = "${AppVersion.NAME} ${AppVersion.VERSION}",
            icon = appIcon,
            state = rememberWindowState(width = 480.dp, height = 916.dp),
        ) {
            val darkTheme = chosenDarkTheme ?: isSystemInDarkTheme()
            LaunchedEffect(darkTheme) {
                window.applySystemTitleBar(darkTheme)
            }
            LaunchedEffect(approvalsWaiting) {
                if (approvalsWaiting > 0) window.toFront()
            }
            DesktopApp()
        }
    }

    Tray(
        icon = appIcon,
        tooltip = "Remote Controller Server is running",
        onAction = onShowWindow,
        menu = {
            Item("Show", onClick = onShowWindow)
            Item("Exit", onClick = onExitApplication)
        },
    )
}
