package com.remotecontroller.client.storage.local

import androidx.room3.Room
import androidx.room3.RoomDatabase
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

fun savedServerDatabaseBuilder(): RoomDatabase.Builder<SavedServerDatabase> {
    val dbFilePath = documentDirectory() + "/saved_servers.db"
    return Room.databaseBuilder(
        name = dbFilePath,
        factory = SavedServerDatabaseConstructor::initialize,
    )
}

private fun documentDirectory(): String {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    return requireNotNull(documentDirectory?.path)
}
