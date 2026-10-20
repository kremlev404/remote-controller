package com.remotecontroller.client.feature.common.di

import com.remotecontroller.client.data.di.dataModule
import com.remotecontroller.client.domain.di.domainModule
import com.remotecontroller.client.feature.umbrella.umbrellaModule
import org.koin.dsl.module

val appModule = module {
    includes(domainModule, dataModule, umbrellaModule)
}
