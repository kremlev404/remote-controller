package com.remotecontroller.client.feature.umbrella

import com.remotecontroller.client.feature.connect.impl.di.connectModule
import com.remotecontroller.client.feature.remote.impl.di.remoteModule
import org.koin.dsl.module

val umbrellaModule = module {
    includes(
        connectModule,
        remoteModule,
    )
}
