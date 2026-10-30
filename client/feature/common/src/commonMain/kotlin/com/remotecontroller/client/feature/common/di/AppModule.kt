package com.remotecontroller.client.feature.common.di

import com.remotecontroller.client.data.di.dataModule
import com.remotecontroller.client.domain.di.domainModule
import com.remotecontroller.client.storage.di.storageModule
import com.remotecontroller.client.feature.common.theme.AppThemeViewModel
import com.remotecontroller.client.feature.umbrella.umbrellaModule
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    includes(domainModule, storageModule, dataModule, umbrellaModule)
    viewModel { AppThemeViewModel(get()) }
}
