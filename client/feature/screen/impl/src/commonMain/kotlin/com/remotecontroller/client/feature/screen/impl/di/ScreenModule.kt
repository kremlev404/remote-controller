package com.remotecontroller.client.feature.screen.impl.di

import com.remotecontroller.client.feature.screen.impl.ScreenViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val screenModule = module {
    viewModel { ScreenViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
}
