package com.ebookreader.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val charOffset: Int,
    val text: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
