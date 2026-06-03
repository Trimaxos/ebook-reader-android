package com.ebookreader.ui.reader

import android.content.Context
import com.ebookreader.data.model.Chapter
import com.ebookreader.tts.SentenceSpan
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * File-based cache for parsed book data (chapters + display items).
 * Lần 2 mở sách sẽ load từ cache thay vì parse lại EPUB + split sentences.
 */
class BookCache(private val context: Context) {

    private val cacheDir = File(context.cacheDir, "book_cache")

    init {
        cacheDir.mkdirs()
    }

    data class CachedBook(
        val chapters: List<Chapter>,
        val displayItems: List<DisplayItem>,
        val sentenceCountsPerChapter: List<Int>,
        val allSentenceSpans: List<SentenceSpan>
    )

    fun get(bookId: Long, filePath: String): CachedBook? {
        val file = getCacheFile(bookId, filePath)
        if (!file.exists()) return null
        // Check if cache is newer than file
        val bookFile = File(filePath)
        if (bookFile.exists() && bookFile.lastModified() > file.lastModified()) {
            file.delete() // stale cache
            return null
        }
        return try {
            val json = JSONObject(file.readText())
            parseFromJson(json)
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    fun put(bookId: Long, filePath: String, data: CachedBook) {
        try {
            val json = buildJson(data)
            val file = getCacheFile(bookId, filePath)
            file.writeText(json.toString(2))
        } catch (_: Exception) {
            // Cache failure is non-critical
        }
    }

    private fun getCacheFile(bookId: Long, filePath: String): File {
        val hash = filePath.hashCode().toLong()
        val name = "book_${bookId}_${hash}.json"
        return File(cacheDir, name)
    }

    private fun buildJson(data: CachedBook): JSONObject {
        val root = JSONObject()

        // Chapters
        val chaptersArr = JSONArray()
        for (ch in data.chapters) {
            chaptersArr.put(JSONObject().apply {
                put("index", ch.index)
                put("title", ch.title)
                put("content", ch.content)
            })
        }
        root.put("chapters", chaptersArr)

        // Display items
        val displayArr = JSONArray()
        for (item in data.displayItems) {
            displayArr.put(when (item) {
                is DisplayItem.Header -> JSONObject().apply {
                    put("type", "header")
                    put("chapterIndex", item.chapterIndex)
                    put("title", item.title)
                }
                is DisplayItem.Sentence -> JSONObject().apply {
                    put("type", "sentence")
                    put("flatIndex", item.flatIndex)
                    put("chapterIndex", item.chapterIndex)
                    put("text", item.span.text)
                    put("startOffset", item.span.startOffset)
                    put("endOffset", item.span.endOffset)
                }
            })
        }
        root.put("displayItems", displayArr)

        // Sentence counts per chapter
        root.put("sentenceCounts", JSONArray(data.sentenceCountsPerChapter))

        return root
    }

    private fun parseFromJson(root: JSONObject): CachedBook {
        // Chapters
        val chaptersArr = root.getJSONArray("chapters")
        val chapters = mutableListOf<Chapter>()
        for (i in 0 until chaptersArr.length()) {
            val obj = chaptersArr.getJSONObject(i)
            chapters.add(Chapter(
                index = obj.getInt("index"),
                title = obj.getString("title"),
                content = obj.getString("content")
            ))
        }

        // Display items
        val displayArr = root.getJSONArray("displayItems")
        val displayItems = mutableListOf<DisplayItem>()
        val allSpans = mutableListOf<SentenceSpan>()
        for (i in 0 until displayArr.length()) {
            val obj = displayArr.getJSONObject(i)
            when (obj.getString("type")) {
                "header" -> displayItems.add(DisplayItem.Header(
                    chapterIndex = obj.getInt("chapterIndex"),
                    title = obj.getString("title")
                ))
                "sentence" -> {
                    val span = SentenceSpan(
                        text = obj.getString("text"),
                        startOffset = obj.getInt("startOffset"),
                        endOffset = obj.getInt("endOffset")
                    )
                    allSpans.add(span)
                    displayItems.add(DisplayItem.Sentence(
                        flatIndex = obj.getInt("flatIndex"),
                        chapterIndex = obj.getInt("chapterIndex"),
                        span = span
                    ))
                }
            }
        }

        // Sentence counts
        val countsArr = root.getJSONArray("sentenceCounts")
        val counts = mutableListOf<Int>()
        for (i in 0 until countsArr.length()) {
            counts.add(countsArr.getInt(i))
        }

        return CachedBook(chapters, displayItems, counts, allSpans)
    }
}
