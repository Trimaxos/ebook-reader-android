package com.ebookreader.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "",
    val filePath: String,
    val coverPath: String? = null,
    val totalChapters: Int = 0,
    val lastReadChapter: Int = 0,
    val lastReadPosition: Int = 0,
    val addedAt: Long = System.currentTimeMillis()
)
