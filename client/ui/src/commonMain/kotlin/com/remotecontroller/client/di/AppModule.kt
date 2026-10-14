package com.remotecontroller.client.di

import com.remotecontroller.client.data.di.dataModule
import com.remotecontroller.client.domain.di.domainModule
import com.remotecontroller.client.presentation.connect.ConnectViewModel
import com.remotecontroller.client.presentation.remote.RemoteViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val presentationModule = module {
    viewModel { ConnectViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { RemoteViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}

val appModule = module {
    includes(domainModule, dataModule, presentationModule)
}
