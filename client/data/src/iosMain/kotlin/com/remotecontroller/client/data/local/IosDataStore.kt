@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.remotecontroller.client.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

internal fun createConnectionPreferencesDataStore(): DataStore<Preferences> {
    val documents = NSSearchPathForDirectoriesInDomains(
        directory = NSDocumentDirectory,
        domainMask = NSUserDomainMask,
        expandTilde = true,
    ).firstOrNull() as? String ?: error("Documents directory is unavailable")
    val directory = "$documents/datastore"
    val created = NSFileManager.defaultManager.createDirectoryAtPath(
        path = directory,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    check(created) { "Could not create $directory" }
    return PreferenceDataStoreFactory.createWithPath(
        produceFile = { "$directory/connection_preferences.preferences_pb".toPath() },
    )
}
