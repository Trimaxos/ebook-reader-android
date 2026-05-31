package com.ebookreader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ebookreader.data.model.Book
import com.ebookreader.data.model.Bookmark
import com.ebookreader.data.model.ReadingProgress

@Database(
    entities = [Book::class, Bookmark::class, ReadingProgress::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun readingProgressDao(): ReadingProgressDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ebook_reader.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
