package com.remotecontroller.client.data.di

import com.remotecontroller.client.data.AppScope
import com.remotecontroller.client.data.remote.PcSocketClient
import com.remotecontroller.client.data.remote.PcTransport
import com.remotecontroller.client.data.remote.ScreenStreamClient
import com.remotecontroller.client.data.repository.ClipboardRepositoryImpl
import com.remotecontroller.client.data.repository.RemoteRepositoryImpl
import com.remotecontroller.client.data.repository.ScreenRepositoryImpl
import com.remotecontroller.client.domain.repository.ClipboardRepository
import com.remotecontroller.client.domain.repository.RemoteRepository
import com.remotecontroller.client.domain.repository.ScreenRepository
import org.koin.core.module.Module
import org.koin.dsl.module

expect fun platformDataModule(): Module

val dataModule = module {
    includes(platformDataModule())
    single { AppScope() }
    single<PcTransport> { PcSocketClient(get(), get()) }
    single { ScreenStreamClient(get()) }
    single<RemoteRepository> { RemoteRepositoryImpl(get(), get(), get()) }
    single<ScreenRepository> { ScreenRepositoryImpl(get()) }
    single<ClipboardRepository> { ClipboardRepositoryImpl(get()) }
}
