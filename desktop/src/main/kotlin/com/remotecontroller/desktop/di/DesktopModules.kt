package com.remotecontroller.desktop.di

import com.remotecontroller.desktop.executor.CommandExecutor
import com.remotecontroller.desktop.executor.KeyboardSimulator
import com.remotecontroller.desktop.executor.MouseSimulator
import com.remotecontroller.desktop.executor.VolumeController
import com.remotecontroller.desktop.executor.WindowsVolumeController
import com.remotecontroller.desktop.server.CommandServer
import com.remotecontroller.desktop.server.ServerLifecycle
import com.remotecontroller.desktop.startup.StartupManager
import com.remotecontroller.desktop.startup.WindowsStartupManager
import com.remotecontroller.desktop.ui.DesktopViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

private val executorModule = module {
    single<VolumeController> { WindowsVolumeController() }
    single { KeyboardSimulator() }
    single { MouseSimulator() }
    single { CommandExecutor(get(), get(), get()) }
}

private val serverModule = module {
    single { CommandServer(get()) }
    single { ServerLifecycle(get()) }
    single<StartupManager> { WindowsStartupManager() }
}

private val presentationModule = module {
    viewModel { DesktopViewModel(get(), get()) }
}

val desktopAppModule = module {
    includes(executorModule, serverModule, presentationModule)
}
