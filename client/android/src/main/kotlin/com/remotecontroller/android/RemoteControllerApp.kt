package com.remotecontroller.android

import android.app.Application
import com.remotecontroller.client.feature.common.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class RemoteControllerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@RemoteControllerApp)
            modules(appModule)
        }
    }
}
