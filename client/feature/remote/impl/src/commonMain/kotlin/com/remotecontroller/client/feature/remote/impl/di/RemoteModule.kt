package com.remotecontroller.client.feature.remote.impl.di

import com.remotecontroller.client.feature.remote.impl.RemoteViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val remoteModule = module {
    viewModel { RemoteViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
