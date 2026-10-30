package com.remotecontroller.client.storage.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedServerDao {
    @Query("SELECT * FROM saved_server ORDER BY savedAt DESC")
    fun observeAll(): Flow<List<SavedServerEntity>>

    @Query("SELECT COUNT(*) FROM saved_server")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: SavedServerEntity)

    @Query(
        """
        DELETE FROM saved_server
        WHERE savedAt NOT IN (
            SELECT savedAt FROM saved_server ORDER BY savedAt DESC LIMIT :keep
        )
        """,
    )
    suspend fun trimTo(keep: Int)
}
