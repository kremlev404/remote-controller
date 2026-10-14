package com.remotecontroller.client.data.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.remotecontroller.client.data.local.createConnectionPreferencesDataStore
import com.remotecontroller.client.data.network.IosLocalNetwork
import com.remotecontroller.client.data.network.IosNetworkMonitor
import com.remotecontroller.client.data.remote.remoteHttpClient
import com.remotecontroller.client.domain.model.LocalNetworkAccess
import com.remotecontroller.client.domain.model.NetworkMonitor
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose

actual fun platformDataModule(): Module = module {
    single {
        LocalNetworkAccess(IosLocalNetwork.PERMISSION) { IosLocalNetwork.isGranted() }
    }
    single<NetworkMonitor> { IosNetworkMonitor() }
    single { remoteHttpClient() } onClose { it?.close() }
    single<DataStore<Preferences>> { createConnectionPreferencesDataStore() }
}
