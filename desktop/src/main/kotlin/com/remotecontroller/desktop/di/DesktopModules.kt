package com.remotecontroller.desktop.di

import com.remotecontroller.desktop.executor.CommandExecutor
import com.remotecontroller.desktop.executor.MouseSimulator
import com.remotecontroller.desktop.executor.VolumeController
import com.remotecontroller.desktop.platform.DesktopPlatform
import com.remotecontroller.desktop.server.CommandServer
import com.remotecontroller.desktop.server.IncomingFirewall
import com.remotecontroller.desktop.server.ServerKeepAlive
import com.remotecontroller.desktop.server.ServerLifecycle
import com.remotecontroller.desktop.startup.StartupManager
import com.remotecontroller.desktop.ui.DesktopViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

private val executorModule = module {
    single<VolumeController> { DesktopPlatform.volumeController() }
    single { DesktopPlatform.keyboardSimulator() }
    single { MouseSimulator() }
    single { CommandExecutor(get(), get(), get()) }
}

private val serverModule = module {
    single<IncomingFirewall> { DesktopPlatform.firewall() }
    single { CommandServer(get(), get(), DesktopPlatform.machineName()) }
    single<ServerKeepAlive> { DesktopPlatform.serverKeepAlive() }
    single { ServerLifecycle(get(), get()) }
    single<StartupManager> { DesktopPlatform.startupManager() }
}

private val presentationModule = module {
    viewModel { DesktopViewModel(get(), get()) }
}

val desktopAppModule = module {
    includes(executorModule, serverModule, presentationModule)
}
