package com.ebookreader.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reading_progress")
data class ReadingProgress(
    @PrimaryKey val bookId: Long,
    val chapterIndex: Int,
    val charOffset: Int,
    val updatedAt: Long = System.currentTimeMillis()
)
