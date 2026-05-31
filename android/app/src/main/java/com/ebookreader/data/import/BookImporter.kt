package com.ebookreader.data.import

import android.content.Context
import android.net.Uri
import com.ebookreader.data.db.AppDatabase
import com.ebookreader.data.epub.EpubParser
import com.ebookreader.data.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class BookImporter(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val parser = EpubParser()

    suspend fun importEpub(uri: Uri): Result<Book> = withContext(Dispatchers.IO) {
        try {
            // Copy file to app storage
            val fileName = "book_${System.currentTimeMillis()}.epub"
            val destDir = File(context.filesDir, "books")
            destDir.mkdirs()
            val destFile = File(destDir, fileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(Exception("Cannot open file"))

            // Parse metadata
            val (_, metadata) = parser.parse(context, destFile.absolutePath)

            // Save cover image
            var coverPath: String? = null
            metadata.coverImage?.let { bytes ->
                val coverFile = File(destDir, "${fileName}_cover.jpg")
                coverFile.writeBytes(bytes)
                coverPath = coverFile.absolutePath
            }

            // Create book entity
            val book = Book(
                title = metadata.title,
                author = metadata.author,
                filePath = destFile.absolutePath,
                coverPath = coverPath,
                totalChapters = parser.getChapterCount(destFile.absolutePath)
            )

            // Save to DB
            val id = db.bookDao().insert(book)
            Result.success(book.copy(id = id))

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun deleteBook(book: Book) {
        // Delete files
        File(book.filePath).delete()
        book.coverPath?.let { File(it).delete() }

        // Delete from DB
        kotlinx.coroutines.runBlocking {
            db.bookDao().delete(book)
        }
    }
}
