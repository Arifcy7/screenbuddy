package com.screen.buddy.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY createdAtEpochMs ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    /** Newest first. Used to give a brand-new Live session the gist of the last conversation. */
    @Query("SELECT * FROM messages ORDER BY createdAtEpochMs DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}
