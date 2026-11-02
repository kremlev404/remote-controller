package com.remotecontroller.client.storage.di

import com.remotecontroller.client.domain.repository.ConnectionPreferencesRepository
import com.remotecontroller.client.domain.repository.DeviceGrantRepository
import com.remotecontroller.client.domain.repository.ThemePreferencesRepository
import com.remotecontroller.client.storage.local.DeviceGrantStore
import com.remotecontroller.client.storage.local.SavedServerDatabase
import com.remotecontroller.client.storage.local.SavedServerStore
import com.remotecontroller.client.storage.local.ThemePreferencesStore
import com.remotecontroller.client.storage.local.buildSavedServerDatabase
import com.remotecontroller.client.storage.repository.ConnectionPreferencesRepositoryImpl
import com.remotecontroller.client.storage.repository.ThemePreferencesRepositoryImpl
import org.koin.core.module.Module
import org.koin.dsl.module

expect fun platformStorageModule(): Module

val storageModule = module {
    includes(platformStorageModule())
    single { buildSavedServerDatabase(get()) }
    single { get<SavedServerDatabase>().savedServers() }
    single { SavedServerStore(get(), get()) }
    single { ThemePreferencesStore(get()) }
    single<ConnectionPreferencesRepository> { ConnectionPreferencesRepositoryImpl(get()) }
    single<DeviceGrantRepository> { DeviceGrantStore(get()) }
    single<ThemePreferencesRepository> { ThemePreferencesRepositoryImpl(get()) }
}
