package com.remotecontroller.client.storage.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.remotecontroller.client.storage.local.savedServerDatabaseBuilder
import okio.Path.Companion.toPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformStorageModule(): Module = module {
    single { savedServerDatabaseBuilder(androidContext()) }
    single {
        val filesDir = androidContext().filesDir
        PreferenceDataStoreFactory.createWithPath(
            produceFile = {
                filesDir.resolve("datastore/connection_preferences.preferences_pb").absolutePath.toPath()
            },
        )
    }
}
