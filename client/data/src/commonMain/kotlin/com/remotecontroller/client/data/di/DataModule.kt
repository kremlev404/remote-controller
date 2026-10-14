package com.remotecontroller.client.data.di

import com.remotecontroller.client.data.AppScope
import com.remotecontroller.client.data.local.ConnectionPreferencesStore
import com.remotecontroller.client.data.remote.PcSocketClient
import com.remotecontroller.client.data.remote.PcTransport
import com.remotecontroller.client.data.repository.ConnectionPreferencesRepositoryImpl
import com.remotecontroller.client.data.repository.RemoteRepositoryImpl
import com.remotecontroller.client.domain.repository.ConnectionPreferencesRepository
import com.remotecontroller.client.domain.repository.RemoteRepository
import org.koin.core.module.Module
import org.koin.dsl.module

expect fun platformDataModule(): Module

val dataModule = module {
    includes(platformDataModule())
    single { ConnectionPreferencesStore(get()) }
    single<ConnectionPreferencesRepository> { ConnectionPreferencesRepositoryImpl(get()) }
    single { AppScope() }
    single<PcTransport> { PcSocketClient(get(), get()) }
    single<RemoteRepository> { RemoteRepositoryImpl(get(), get(), get()) }
}
