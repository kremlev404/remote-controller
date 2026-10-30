package com.remotecontroller.client.storage.local

import androidx.room3.Entity

@Entity(tableName = "saved_server", primaryKeys = ["host", "port"])
data class SavedServerEntity(
    val host: String,
    val port: Int,
    val hint: String,
    val savedAt: Long,
)
