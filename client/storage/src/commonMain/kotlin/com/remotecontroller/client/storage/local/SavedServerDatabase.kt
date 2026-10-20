package com.remotecontroller.client.storage.local

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Database(entities = [SavedServerEntity::class], version = 1)
@ConstructedBy(SavedServerDatabaseConstructor::class)
abstract class SavedServerDatabase : RoomDatabase() {
    abstract fun savedServers(): SavedServerDao
}

@Suppress("KotlinNoActualForExpect")
expect object SavedServerDatabaseConstructor : RoomDatabaseConstructor<SavedServerDatabase> {
    override fun initialize(): SavedServerDatabase
}

fun buildSavedServerDatabase(builder: RoomDatabase.Builder<SavedServerDatabase>): SavedServerDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
