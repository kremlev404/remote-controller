package com.remotecontroller.client.domain.di

import com.remotecontroller.client.domain.usecase.ConnectToPcUseCase
import com.remotecontroller.client.domain.usecase.DisconnectFromPcUseCase
import com.remotecontroller.client.domain.usecase.ObserveActiveConnectionUseCase
import com.remotecontroller.client.domain.usecase.ObserveConnectionStateUseCase
import com.remotecontroller.client.domain.usecase.ObserveNetworkStatusUseCase
import com.remotecontroller.client.domain.usecase.ObserveSavedConnectionUseCase
import com.remotecontroller.client.domain.usecase.ObserveSavedServersUseCase
import com.remotecontroller.client.domain.usecase.ObserveServerStatusUseCase
import com.remotecontroller.client.domain.usecase.RetryConnectionUseCase
import com.remotecontroller.client.domain.usecase.SaveConnectionUseCase
import com.remotecontroller.client.domain.usecase.SendKeyCommandUseCase
import com.remotecontroller.client.domain.usecase.SendMouseCommandUseCase
import com.remotecontroller.client.domain.usecase.SendRemoteCommandUseCase
import com.remotecontroller.client.domain.usecase.ObservePointerDpiUseCase
import com.remotecontroller.client.domain.usecase.ObserveThemeModeUseCase
import com.remotecontroller.client.domain.usecase.SendVolumeCommandUseCase
import com.remotecontroller.client.domain.usecase.SetPointerDpiUseCase
import com.remotecontroller.client.domain.usecase.SetThemeModeUseCase
import org.koin.dsl.module

val domainModule = module {
    factory { ConnectToPcUseCase(get()) }
    factory { DisconnectFromPcUseCase(get()) }
    factory { RetryConnectionUseCase(get()) }
    factory { ObserveConnectionStateUseCase(get()) }
    factory { ObserveServerStatusUseCase(get()) }
    factory { ObserveActiveConnectionUseCase(get()) }
    factory { ObserveNetworkStatusUseCase(get()) }
    factory { ObserveSavedConnectionUseCase(get()) }
    factory { ObserveSavedServersUseCase(get()) }
    factory { SaveConnectionUseCase(get()) }
    factory { SendRemoteCommandUseCase(get()) }
    factory { SendVolumeCommandUseCase(get()) }
    factory { SendKeyCommandUseCase(get()) }
    factory { SendMouseCommandUseCase(get()) }
    factory { ObserveThemeModeUseCase(get()) }
    factory { SetThemeModeUseCase(get()) }
    factory { ObservePointerDpiUseCase(get()) }
    factory { SetPointerDpiUseCase(get()) }
}
