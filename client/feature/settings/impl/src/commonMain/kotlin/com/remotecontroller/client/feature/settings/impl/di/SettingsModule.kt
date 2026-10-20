package com.remotecontroller.client.feature.settings.impl.di

import com.remotecontroller.client.feature.settings.impl.SettingsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val settingsModule = module {
    viewModel { SettingsViewModel(get(), get(), get(), get()) }
}
