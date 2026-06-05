package com.ebookreader.data.import

import android.content.Context
import android.net.Uri
import com.ebookreader.data.db.AppDatabase
import com.ebookreader.data.epub.BookMetadata
import com.ebookreader.data.epub.EpubParser
import com.ebookreader.data.model.Book
import com.ebookreader.data.prc.PrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class BookImporter(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val epubParser = EpubParser()
    private val prcParser = PrcParser()

    suspend fun importBook(uri: Uri): Result<Book> = withContext(Dispatchers.IO) {
        try {
            // Detect file extension from URI
            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "book_${System.currentTimeMillis()}"
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val isPrc = ext == "prc" || ext == "mobi"

            val destName = "book_${System.currentTimeMillis()}.$ext"
            val destDir = File(context.filesDir, "books")
            destDir.mkdirs()
            val destFile = File(destDir, destName)

            // Copy file to app storage
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(Exception("Cannot open file"))

            // Parse metadata
            val (chapters, metadata) = if (isPrc) {
                prcParser.parse(context, destFile.absolutePath)
            } else {
                epubParser.parse(context, destFile.absolutePath)
            }

            // Save cover image
            var coverPath: String? = null
            metadata.coverImage?.let { bytes ->
                val coverFile = File(destDir, "${destName}_cover.jpg")
                coverFile.writeBytes(bytes)
                coverPath = coverFile.absolutePath
            }

            // Create book entity
            val book = Book(
                title = metadata.title,
                author = metadata.author,
                filePath = destFile.absolutePath,
                coverPath = coverPath,
                totalChapters = chapters.size
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
