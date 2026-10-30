package com.remotecontroller.client.domain.di

import com.remotecontroller.client.domain.usecase.ConnectToPcUseCase
import com.remotecontroller.client.domain.usecase.ObserveClipboardUseCase
import com.remotecontroller.client.domain.usecase.SendClipboardUseCase
import com.remotecontroller.client.domain.usecase.DisconnectFromPcUseCase
import com.remotecontroller.client.domain.usecase.ObserveActiveConnectionUseCase
import com.remotecontroller.client.domain.usecase.ObserveApprovalCodeUseCase
import com.remotecontroller.client.domain.usecase.ObserveConnectionStateUseCase
import com.remotecontroller.client.domain.usecase.ObserveNetworkStatusUseCase
import com.remotecontroller.client.domain.usecase.ObserveSavedConnectionUseCase
import com.remotecontroller.client.domain.usecase.ObserveSavedServersUseCase
import com.remotecontroller.client.domain.usecase.ObserveScreenUseCase
import com.remotecontroller.client.domain.usecase.ObserveServerStatusUseCase
import com.remotecontroller.client.domain.usecase.RetryConnectionUseCase
import com.remotecontroller.client.domain.usecase.SaveConnectionUseCase
import com.remotecontroller.client.domain.usecase.SendKeyCommandUseCase
import com.remotecontroller.client.domain.usecase.SendMouseCommandUseCase
import com.remotecontroller.client.domain.usecase.SendPointerCommandUseCase
import com.remotecontroller.client.domain.usecase.SendRemoteCommandUseCase
import com.remotecontroller.client.domain.usecase.ObservePointerDpiUseCase
import com.remotecontroller.client.domain.usecase.ObserveScreenTouchModeUseCase
import com.remotecontroller.client.domain.usecase.ObserveThemeModeUseCase
import com.remotecontroller.client.domain.usecase.SendVolumeCommandUseCase
import com.remotecontroller.client.domain.usecase.SetPointerDpiUseCase
import com.remotecontroller.client.domain.usecase.SetScreenTouchModeUseCase
import com.remotecontroller.client.domain.usecase.SetThemeModeUseCase
import org.koin.dsl.module

val domainModule = module {
    factory { ConnectToPcUseCase(get()) }
    factory { DisconnectFromPcUseCase(get()) }
    factory { RetryConnectionUseCase(get()) }
    factory { ObserveConnectionStateUseCase(get()) }
    factory { ObserveApprovalCodeUseCase(get()) }
    factory { ObserveServerStatusUseCase(get()) }
    factory { ObserveActiveConnectionUseCase(get()) }
    factory { ObserveScreenUseCase(get()) }
    factory { ObserveClipboardUseCase(get()) }
    factory { SendClipboardUseCase(get()) }
    factory { ObserveNetworkStatusUseCase(get()) }
    factory { ObserveSavedConnectionUseCase(get()) }
    factory { ObserveSavedServersUseCase(get()) }
    factory { SaveConnectionUseCase(get()) }
    factory { SendRemoteCommandUseCase(get()) }
    factory { SendVolumeCommandUseCase(get()) }
    factory { SendKeyCommandUseCase(get()) }
    factory { SendMouseCommandUseCase(get()) }
    factory { SendPointerCommandUseCase(get()) }
    factory { ObserveThemeModeUseCase(get()) }
    factory { SetThemeModeUseCase(get()) }
    factory { ObservePointerDpiUseCase(get()) }
    factory { SetPointerDpiUseCase(get()) }
    factory { ObserveScreenTouchModeUseCase(get()) }
    factory { SetScreenTouchModeUseCase(get()) }
}
