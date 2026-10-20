package com.remotecontroller.client.storage.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.remotecontroller.client.storage.local.createConnectionPreferencesDataStore
import com.remotecontroller.client.storage.local.savedServerDatabaseBuilder
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformStorageModule(): Module = module {
    single { savedServerDatabaseBuilder() }
    single<DataStore<Preferences>> { createConnectionPreferencesDataStore() }
}
