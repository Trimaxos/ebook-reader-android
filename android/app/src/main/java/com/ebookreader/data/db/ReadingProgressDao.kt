package com.ebookreader.data.db

import androidx.room.*
import com.ebookreader.data.model.ReadingProgress

@Dao
interface ReadingProgressDao {
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun getProgress(bookId: Long): ReadingProgress?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ReadingProgress)

    @Delete
    suspend fun delete(progress: ReadingProgress)
}
