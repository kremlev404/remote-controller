package com.remotecontroller.client.data.di

import com.remotecontroller.client.data.network.IosLocalNetwork
import com.remotecontroller.client.data.network.IosNetworkMonitor
import com.remotecontroller.client.data.remote.DeviceIdentity
import com.remotecontroller.client.data.remote.remoteHttpClient
import platform.UIKit.UIDevice
import com.remotecontroller.client.domain.model.LocalNetworkAccess
import com.remotecontroller.client.domain.model.NetworkMonitor
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose

actual fun platformDataModule(): Module = module {
    single<DeviceIdentity> {
        DeviceIdentity {
            val device = UIDevice.currentDevice
            device.name.takeIf { it.isNotBlank() } ?: device.model
        }
    }
    single {
        LocalNetworkAccess(IosLocalNetwork.PERMISSION) { IosLocalNetwork.isGranted() }
    }
    single<NetworkMonitor> { IosNetworkMonitor() }
    single { remoteHttpClient() } onClose { it?.close() }
}
