package com.screen.buddy.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Text transcripts only — never store images. Wired in M6. */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val text: String,
    val createdAtEpochMs: Long,
)
