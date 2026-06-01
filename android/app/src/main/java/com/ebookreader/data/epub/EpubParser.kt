package com.ebookreader.data.epub

import android.content.Context
import com.ebookreader.data.model.Chapter
import nl.siegmann.epublib.domain.Book
import nl.siegmann.epublib.epub.EpubReader
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

class EpubParser {

    companion object {
        // Compiled regexes - reuse instead of creating per call
        private val styleTagRegex = Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL)
        private val scriptTagRegex = Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL)
        private val htmlTagRegex = Regex("<[^>]*>")
        private val nbspRegex = Regex("&nbsp;")
        private val ampRegex = Regex("&amp;")
        private val ltRegex = Regex("&lt;")
        private val gtRegex = Regex("&gt;")
        private val quotRegex = Regex("&quot;")
        private val htmlEntityRegex = Regex("&#\\d+;")
        private val multiNewlineRegex = Regex("\\n{3,}")
    }

    fun parse(context: Context, filePath: String): Pair<List<Chapter>, BookMetadata> {
        val inputStream = FileInputStream(filePath)
        val book = EpubReader().readEpub(inputStream)
        inputStream.close()

        // Extract metadata
        val authorName = buildString {
            val authors = book.metadata.authors
            if (authors.isNotEmpty()) {
                val a = authors[0]
                val first = a.firstname ?: ""
                val last = a.lastname ?: ""
                if (first.isNotEmpty() || last.isNotEmpty()) {
                    append("$first $last".trim())
                }
            }
        }

        val coverBytes: ByteArray? = try {
            book.coverImage?.data
        } catch (e: Exception) {
            null
        }

        val metadata = BookMetadata(
            title = book.title ?: "Untitled",
            author = authorName.ifEmpty { "Unknown" },
            coverImage = coverBytes
        )

        val chapters = mutableListOf<Chapter>()

        // Get TOC references
        val tocRefs: List<nl.siegmann.epublib.domain.TOCReference> = try {
            book.tableOfContents.tocReferences
        } catch (e: Exception) {
            emptyList()
        }

        if (tocRefs.isNotEmpty()) {
            for ((i, ref) in tocRefs.withIndex()) {
                val resource = ref.resource
                val content = readResource(resource)
                if (content.isNotBlank()) {
                    chapters.add(Chapter(
                        index = i,
                        title = ref.title ?: "Chương ${i + 1}",
                        content = content
                    ))
                }
            }
        }

        // Fallback: no TOC content → read all resources
        if (chapters.isEmpty()) {
            var chapterIdx = 0
            val allResources = book.resources.getAll()
            for (resource in allResources) {
                val content = readResource(resource)
                if (content.isNotBlank()) {
                    chapters.add(Chapter(
                        index = chapterIdx,
                        title = "Chương ${chapterIdx + 1}",
                        content = content
                    ))
                    chapterIdx++
                }
            }
        }

        return Pair(chapters, metadata)
    }

    fun getChapterCount(filePath: String): Int {
        return try {
            val inputStream = FileInputStream(filePath)
            val book = EpubReader().readEpub(inputStream)
            inputStream.close()
            book.tableOfContents.tocReferences.size
        } catch (e: Exception) {
            0
        }
    }

    /** Read text content from an EPUB resource */
    private fun readResource(resource: nl.siegmann.epublib.domain.Resource?): String {
        if (resource == null) return ""
        return try {
            val reader = BufferedReader(InputStreamReader(resource.inputStream, Charsets.UTF_8))
            val text = reader.readText()
            reader.close()
            stripHtml(text)
        } catch (e: Exception) {
            try {
                val data = resource.data
                if (data != null) {
                    stripHtml(String(data, Charsets.UTF_8))
                } else ""
            } catch (e2: Exception) {
                ""
            }
        }
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(styleTagRegex, "")
            .replace(scriptTagRegex, "")
            .replace(htmlTagRegex, "")
            .replace(nbspRegex, " ")
            .replace(ampRegex, "&")
            .replace(ltRegex, "<")
            .replace(gtRegex, ">")
            .replace(quotRegex, "\"")
            .replace(htmlEntityRegex) { matchResult ->
                val code = matchResult.value.drop(2).dropLast(1).toIntOrNull()
                if (code != null) code.toChar().toString() else matchResult.value
            }
            .replace(multiNewlineRegex, "\n\n")
            .trim()
    }
}

data class BookMetadata(
    val title: String,
    val author: String,
    val coverImage: ByteArray?
)
