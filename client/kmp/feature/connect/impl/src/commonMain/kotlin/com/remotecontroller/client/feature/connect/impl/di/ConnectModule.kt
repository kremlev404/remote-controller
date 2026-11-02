package com.remotecontroller.client.feature.connect.impl.di

import com.remotecontroller.client.feature.connect.impl.ConnectViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val connectModule = module {
    viewModel { ConnectViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
}
