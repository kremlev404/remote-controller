package com.remotecontroller.client.presentation

import androidx.compose.ui.window.ComposeUIViewController
import com.remotecontroller.client.di.appModule
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    if (KoinPlatform.getKoinOrNull() == null) {
        startKoin { modules(appModule) }
    }
    return ComposeUIViewController { App() }
}
