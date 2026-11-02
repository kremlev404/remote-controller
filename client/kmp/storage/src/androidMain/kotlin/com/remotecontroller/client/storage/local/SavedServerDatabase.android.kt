package com.remotecontroller.client.storage.local

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase

fun savedServerDatabaseBuilder(context: Context): RoomDatabase.Builder<SavedServerDatabase> {
    val dbFile = context.applicationContext.getDatabasePath("saved_servers.db")
    return Room.databaseBuilder(
        name = dbFile.absolutePath,
        factory = SavedServerDatabaseConstructor::initialize,
    )
}
