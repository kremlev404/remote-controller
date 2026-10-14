package com.remotecontroller.desktop

import com.remotecontroller.desktop.ui.RemoteServerAppIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.remotecontroller.desktop.di.desktopAppModule
import com.remotecontroller.desktop.AppVersion
import com.remotecontroller.desktop.server.ServerLifecycle
import com.remotecontroller.desktop.ui.DesktopApp
import org.koin.compose.KoinContext
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

    application {
        var windowVisible by remember { mutableStateOf(true) }

        ApplicationContent(
            windowVisible = windowVisible,
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ApplicationScope.ApplicationContent(
    windowVisible: Boolean,
    onHideWindow: () -> Unit,
    onExitApplication: () -> Unit,
    onShowWindow: () -> Unit,
) {
    KoinContext {
        val appIcon = rememberVectorPainter(RemoteServerAppIcon)

        if (windowVisible) {
            Window(
                onCloseRequest = onHideWindow,
                title = "${AppVersion.NAME} ${AppVersion.VERSION}",
                icon = appIcon,
                state = rememberWindowState(width = 480.dp, height = 916.dp),
            ) {
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
}
