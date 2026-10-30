package com.remotecontroller.client.data.di

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.remotecontroller.client.data.remote.DeviceIdentity
import com.remotecontroller.client.data.network.AndroidNetworkMonitor
import com.remotecontroller.client.data.remote.remoteHttpClient
import com.remotecontroller.client.domain.model.LocalNetworkAccess
import com.remotecontroller.client.domain.model.NetworkMonitor
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose

private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

actual fun platformDataModule(): Module = module {
    single<DeviceIdentity> {
        DeviceIdentity {
            val context = androidContext()
            val configured = sequenceOf(
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME),
                // Android throws here for apps that target above API 31.
                runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_name") }.getOrNull(),
            ).firstOrNull { !it.isNullOrBlank() }?.trim()
            if (!configured.isNullOrBlank()) {
                configured
            } else {
                val manufacturer = Build.MANUFACTURER.orEmpty().trim()
                val model = Build.MODEL.orEmpty().trim()
                when {
                    model.isBlank() -> "Android"
                    manufacturer.isBlank() || model.startsWith(manufacturer, ignoreCase = true) -> model
                    else -> "$manufacturer $model"
                }
            }
        }
    }
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
}
