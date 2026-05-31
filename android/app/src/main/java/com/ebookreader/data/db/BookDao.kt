package com.ebookreader.data.db

import androidx.room.*
import com.ebookreader.data.model.Book
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun getAllBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookById(id: Long): Book?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: Book): Long

    @Update
    suspend fun update(book: Book)

    @Delete
    suspend fun delete(book: Book)

    @Query("UPDATE books SET lastReadChapter = :chapter, lastReadPosition = :position WHERE id = :bookId")
    suspend fun updateProgress(bookId: Long, chapter: Int, position: Int)
}
