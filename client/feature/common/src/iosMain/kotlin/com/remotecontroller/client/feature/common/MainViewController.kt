package com.remotecontroller.client.feature.common

import androidx.compose.ui.window.ComposeUIViewController
import com.remotecontroller.client.feature.common.di.appModule
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    if (KoinPlatform.getKoinOrNull() == null) {
        startKoin { modules(appModule) }
    }
    return ComposeUIViewController { App() }
}
