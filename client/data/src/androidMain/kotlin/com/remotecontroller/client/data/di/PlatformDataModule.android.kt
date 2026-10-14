package com.remotecontroller.client.data.di

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.remotecontroller.client.data.network.AndroidNetworkMonitor
import com.remotecontroller.client.data.remote.remoteHttpClient
import com.remotecontroller.client.domain.model.LocalNetworkAccess
import com.remotecontroller.client.domain.model.NetworkMonitor
import okio.Path.Companion.toPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose

private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

actual fun platformDataModule(): Module = module {
    single {
        LocalNetworkAccess(LOCAL_NETWORK_PERMISSION) { permission ->
            Build.VERSION.SDK_INT < 37 ||
                ContextCompat.checkSelfPermission(androidContext(), permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    single {
        AndroidNetworkMonitor(
            connectivity = androidContext().getSystemService(ConnectivityManager::class.java)
                ?: error("ConnectivityManager is unavailable"),
            localNetwork = get(),
        )
    }
    single<NetworkMonitor> { get<AndroidNetworkMonitor>() }
    single { remoteHttpClient(get()) } onClose { it?.close() }
    single {
        val filesDir = androidContext().filesDir
        PreferenceDataStoreFactory.createWithPath(
            produceFile = {
                filesDir.resolve("datastore/connection_preferences.preferences_pb").absolutePath.toPath()
            },
        )
    }
}
