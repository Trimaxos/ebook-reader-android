package com.ebookreader.data.prc

import android.content.Context
import com.ebookreader.data.model.Chapter
import com.ebookreader.data.epub.BookMetadata
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Parser for Mobipocket (.prc / .mobi) ebook format.
 *
 * PRC = Palm Database (PDB) container with Mobipocket format.
 * Fields are big-endian throughout.
 */
class PrcParser {

    companion object {
        private const val PDB_HEADER_SIZE = 78
        private const val RECORD_ENTRY_SIZE = 8

        // PalmDOC compression flags
        private const val PALMDOC_UNCOMPRESSED = 1
        private const val PALMDOC_STANDARD = 2

        // HTML stripping (same regex patterns as EpubParser)
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
        private val multiSpaceRegex = Regex("[ \\t]{2,}")
    }

    // ── Public API ──────────────────────────────────

    data class PrcMetadata(
        val title: String,
        val author: String,
        val coverImage: ByteArray?,
        val chapters: List<Chapter>
    )

    fun parse(context: Context, filePath: String): Pair<List<Chapter>, BookMetadata> {
        val bytes = File(filePath).readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return parseBuffer(buffer)
    }

    fun getChapterCount(filePath: String): Int {
        return try {
            val bytes = File(filePath).readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val (chapters, _) = parseBuffer(buffer)
            chapters.size
        } catch (e: Exception) {
            0
        }
    }

    // ── Low-level PDB / Mobipocket parsing ──────────

    private data class PdbHeader(
        val name: String,
        val attributes: Int,
        val version: Int,
        val type: String,
        val creator: String,
        val numRecords: Int
    )

    private data class RecordEntry(
        val offset: Int,
        val attributes: Int,
        val uniqueId: Int
    )

    private data class MobiHeader(
        val title: String,
        val author: String,
        val firstNonBookIndex: Int,    // first text record index
        val startRecordIndex: Int,      // first record with content
        val endRecordIndex: Int,        // last record index
        val coverIndex: Int,            // -1 if no cover
        val compressionType: Int        // 1=uncompressed, 2=PalmDOC
    )

    private fun parseBuffer(buffer: ByteBuffer): Pair<List<Chapter>, BookMetadata> {
        val pdb = readPdbHeader(buffer)
        val entries = readRecordEntries(buffer, pdb.numRecords)
        val prcMeta = readPrcMeta(buffer, pdb, entries)
        return Pair(prcMeta.chapters, BookMetadata(
            title = prcMeta.title,
            author = prcMeta.author,
            coverImage = prcMeta.coverImage
        ))
    }

    private fun readPdbHeader(buffer: ByteBuffer): PdbHeader {
        buffer.rewind()
        val nameBytes = ByteArray(32)
        buffer.get(nameBytes)
        val name = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000').trimEnd()
        val attributes = buffer.getShort().toInt() and 0xFFFF
        val version = buffer.getShort().toInt() and 0xFFFF
        buffer.position(buffer.position() + 4 * 4) // skip 4 date fields
        buffer.position(buffer.position() + 4)     // modification number
        buffer.position(buffer.position() + 4)     // app info ID
        buffer.position(buffer.position() + 4)     // sort info ID
        val type = readFixedString(buffer, 4)
        val creator = readFixedString(buffer, 4)
        buffer.position(buffer.position() + 4)     // unique ID seed
        buffer.position(buffer.position() + 4)     // next record list ID
        val numRecords = buffer.getShort().toInt() and 0xFFFF
        return PdbHeader(name, attributes, version, type, creator, numRecords)
    }

    private fun readRecordEntries(buffer: ByteBuffer, count: Int): List<RecordEntry> {
        val entries = mutableListOf<RecordEntry>()
        for (i in 0 until count) {
            val offset = buffer.getInt()
            val attr = buffer.get().toInt() and 0xFF
            val uid = (buffer.get().toInt() and 0xFF) shl 16 or
                      (buffer.get().toInt() and 0xFF) shl 8 or
                      (buffer.get().toInt() and 0xFF)
            entries.add(RecordEntry(offset, attr, uid))
        }
        return entries
    }

    /**
     * Parse the Mobipocket header from the first PDB record, then extract
     * metadata and text from the record data.
     */
    private fun readPrcMeta(buffer: ByteBuffer, pdb: PdbHeader, entries: List<RecordEntry>): PrcMetadata {
        val fileBytes = buffer.array()
        // The first record contains the Mobipocket header
        val firstRecOff = entries[0].offset
        var mobiHeader: MobiHeader? = null

        // Look for "MOBI" magic in the first record
        val searchStart = firstRecOff
        val mobiMagicPos = findBytes(fileBytes, "MOBI".toByteArray(), searchStart)
        var title = pdb.name
        var author = "Unknown"
        var coverIndex = -1
        var firstNonBookIdx = 0
        var startRecord = 0
        var endRecord = 0
        var compressionType = PALMDOC_STANDARD

        if (mobiMagicPos >= 0) {
            val mobiStart = mobiMagicPos
            // length of mobi header (at offset 8 from MOBI)
            val mobiLength = readInt32(fileBytes, mobiStart + 8)
            // mobi type (offset 0): 2=mobipocket book, 3=text, 4=etc
            val mobiType = readInt32(fileBytes, mobiStart)
            if (mobiType == 2) {
                // Encoding (offset 10)
                // title offset from start of MOBI header (varies by version)
                // In MOBI header structure:
                // offset 0: MOBI identifier
                // offset 4: header length
                // offset 8: mobi type
                // offset 12: text encoding
                // offset 16: unique ID
                // offset 20: file version
                // ...
                // For standard mobi:
                val encoding = readInt32(fileBytes, mobiStart + 12)
                firstNonBookIdx = readInt32(fileBytes, mobiStart + 20)
                startRecord = readInt32(fileBytes, mobiStart + 76)
                endRecord = readInt32(fileBytes, mobiStart + 80)

                // Compression (v3+): check at v3-specific offsets
                // For simplicity, use the standard offset for v3+
                compressionType = if (mobiLength >= 196) {
                    readInt32(fileBytes, mobiStart + 120)
                } else PALMDOC_STANDARD

                // Cover index (at offset 104 in v3+)
                if (mobiLength >= 112) {
                    coverIndex = readInt32(fileBytes, mobiStart + 104)
                }

                // Title offset (at offset 84 from MOBI start = from PDB record start)
                // Actually, the EXTH header contains the title and author
                // EXTH starts at mobiStart + mobiLength

                // Try to get title/author from EXTH header
                val exthOff = mobiStart + mobiLength
                if (exthOff + 12 <= fileBytes.size) {
                    // EXTH starts with "EXTH"
                    if (fileBytes[exthOff].toInt() == 'E'.code &&
                        fileBytes[exthOff + 1].toInt() == 'X'.code &&
                        fileBytes[exthOff + 2].toInt() == 'T'.code &&
                        fileBytes[exthOff + 3].toInt() == 'H'.code) {

                        // EXTH record count at offset 8
                        val exthCount = readInt32(fileBytes, exthOff + 8)
                        var exthPos = exthOff + 12
                        for (i in 0 until exthCount) {
                            if (exthPos + 8 > fileBytes.size) break
                            val recType = readInt32(fileBytes, exthPos)
                            val recLen = readInt32(fileBytes, exthPos + 4)
                            if (exthPos + recLen > fileBytes.size) break
                            val recData = exthPos + 8
                            val strLen = recLen - 8

                            when (recType) {
                                100 -> { // Author
                                    author = String(fileBytes, recData, strLen, Charsets.UTF_8)
                                        .trimEnd('\u0000').trim()
                                        .ifEmpty { "Unknown" }
                                }
                                105 -> { // Title
                                    val t = String(fileBytes, recData, strLen, Charsets.UTF_8)
                                        .trimEnd('\u0000').trim()
                                    if (t.isNotEmpty()) title = t
                                }
                                201 -> { // Cover Offset
                                    // Points to a PDB record index
                                    val coverRecord = readInt32(fileBytes, recData)
                                    if (coverRecord >= 0 && coverRecord < entries.size) {
                                        coverIndex = coverRecord
                                    }
                                }
                            }
                            exthPos += recLen
                        }
                    }
                }
            }
            mobiHeader = MobiHeader(
                title = title,
                author = author,
                firstNonBookIndex = firstNonBookIdx,
                startRecordIndex = startRecord,
                endRecordIndex = endRecord,
                coverIndex = coverIndex,
                compressionType = compressionType
            )
        }

        if (mobiHeader == null) {
            // No MOBI header found — try to extract text from remaining records
            mobiHeader = MobiHeader(title, author, entries.size, 1, entries.size, -1, PALMDOC_STANDARD)
        }

        // Extract cover image
        var coverBytes: ByteArray? = null
        if (mobiHeader.coverIndex in 0 until entries.size &&
            mobiHeader.coverIndex > 0) { // cover is not in first record
            val coverEntry = entries[mobiHeader.coverIndex]
            // Calculate cover size from next record offset or end of file
            val coverStart = coverEntry.offset
            val coverEnd = if (mobiHeader.coverIndex + 1 < entries.size)
                entries[mobiHeader.coverIndex + 1].offset
            else fileBytes.size
            if (coverEnd > coverStart) {
                coverBytes = fileBytes.copyOfRange(coverStart, coverEnd)
                // Verify it's an image (starts with image magic bytes)
                if (coverBytes.size < 4 ||
                    !(coverBytes[0] == 0xFF.toByte() && coverBytes[1] == 0xD8.toByte())) {
                    coverBytes = null
                }
            }
        }

        // Extract text records
        val textRecords = mutableListOf<ByteArray>()
        for (i in mobiHeader.startRecordIndex..mobiHeader.endRecordIndex) {
            if (i < 0 || i >= entries.size) continue
            val entry = entries[i]
            val recStart = entry.offset
            val recEnd = if (i + 1 < entries.size) entries[i + 1].offset else fileBytes.size
            if (recEnd > recStart) {
                textRecords.add(fileBytes.copyOfRange(recStart, recEnd))
            }
        }

        // Decompress text records
        val fullText = StringBuilder()
        for (rec in textRecords) {
            val text = when (mobiHeader.compressionType) {
                PALMDOC_STANDARD -> decompressPalmDoc(rec)
                else -> String(rec, Charsets.UTF_8)
            }
            fullText.append(text).append("\n")
        }

        // Strip HTML and split into chapters
        val cleanHtml = stripHtml(fullText.toString())
        val chapters = splitIntoChapters(cleanHtml, title)

        return PrcMetadata(title, author, coverBytes, chapters)
    }

    // ── PalmDOC decompression ───────────────────────

    /**
     * Decompress PalmDOC (simple LZ77-like compression).
     *
     * Reference: https://wiki.mobileread.com/wiki/PalmDOC
     */
    private fun decompressPalmDoc(data: ByteArray): String {
        if (data.size < 8) return ""
        // First 2 bytes: compression type (unused here, we already know it)
        // Next 2 bytes: uncompressed length
        // The compressed data starts at offset 4 (or sometimes 16 with full header)

        // Skip the PalmDOC header (16 bytes if present, else 8)
        // The first 2 bytes tell us the record count in the full file
        // but for individual record decompression, we start at 16
        var pos = 16
        if (pos >= data.size) pos = 8
        if (pos >= data.size) pos = 2
        if (pos >= data.size) return ""

        val output = ByteArrayOutputStream(data.size * 2) // over-allocate

        while (pos < data.size) {
            val b = data[pos++].toInt() and 0xFF

            when {
                b == 0 -> {
                    // Literal: next byte
                    if (pos < data.size) {
                        output.write(data[pos++].toInt() and 0xFF)
                    }
                }
                b in 1..8 -> {
                    // Back-reference: distance and length encoded in following bytes
                    // This is a simplified implementation
                    // For b=1..8, the distance is 1-byte or 2-byte
                    if (pos >= data.size) break
                    val dist: Int
                    val len: Int
                    when (b) {
                        1 -> {
                            dist = (data[pos++].toInt() and 0xFF) or
                                   ((if (pos < data.size) data[pos++].toInt() and 0xFF else 0) shl 8)
                            len = if (pos < data.size) (data[pos++].toInt() and 0xFF) else 1
                        }
                        else -> {
                            dist = (data[pos++].toInt() and 0xFF) or
                                   ((if (pos < data.size) data[pos++].toInt() and 0xFF else 0) shl 8)
                            len = b - 1
                        }
                    }
                    copyBackReference(output, dist, len)
                }
                b in 0x09..0x7F -> {
                    // Literal byte
                    output.write(b)
                }
                b in 0x80..0xBF -> {
                    // 2-byte back reference
                    if (pos >= data.size) break
                    val next = data[pos++].toInt() and 0xFF
                    val dist = ((b and 0x03) shl 8) or next + 1
                    val len = ((b shr 2) and 0x07) + 3
                    copyBackReference(output, dist, len)
                }
                b in 0xC0..0xFF -> {
                    // Literal byte (0x80 subtracted)
                    output.write(b - 0x80)
                }
            }
        }

        val uncompressed = output.toByteArray()
        // Convert to string — try UTF-8 first, then fallback to raw
        return String(uncompressed, Charsets.UTF_8)
                    .replace('\u0000', ' ')
                    .trim()
    }

    private fun copyBackReference(output: ByteArrayOutputStream, distance: Int, length: Int) {
        val buf = output.toByteArray()
        val start = buf.size - distance
        if (start < 0) return
        for (i in 0 until length.coerceAtMost(1024)) {
            val srcIdx = start + (i % distance.coerceAtLeast(1))
            if (srcIdx < buf.size) {
                output.write(buf[srcIdx].toInt() and 0xFF)
            }
        }
    }

    // ── Text processing ──────────────────────────────

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
            .replace(multiSpaceRegex, " ")
            .trim()
    }

    /**
     * Split cleaned text into chapters.
     * Looks for patterns like "Chapter 1", "Chương 1", or "<h1-6>" tags.
     */
    private fun splitIntoChapters(text: String, bookTitle: String): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        if (text.isBlank()) return chapters

        // Try to find chapter markers
        // Patterns: "Chapter 1", "Chương 1", "Chapter One", etc.
        val chapterPattern = Regex(
            "(?:^|\\n)\\s*(?:(?:Chapter|Chương|Chapitre|Capítulo|Kapitel)\\s+" +
            "|(?:\\d+[.)]\\s*)|(?:Phần|Tập|Quyển)\\s+\\d+)",
            RegexOption.IGNORE_CASE
        )

        val matches = chapterPattern.findAll(text).toList()

        if (matches.isNotEmpty()) {
            for ((i, match) in matches.withIndex()) {
                val start = match.range.first
                val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
                val rawContent = text.substring(start, end).trim()
                // Use first line as title
                val lines = rawContent.split("\n")
                val titleLine = lines.firstOrNull()?.trim()?.take(100) ?: "Chương ${i + 1}"
                val content = lines.drop(1).joinToString("\n").trim()

                if (content.isNotBlank()) {
                    chapters.add(Chapter(
                        index = i,
                        title = titleLine.ifEmpty { "Chương ${i + 1}" },
                        content = content
                    ))
                }
            }
        } else {
            // No chapter markers found → whole text as one chapter
            if (text.isNotBlank()) {
                chapters.add(Chapter(
                    index = 0,
                    title = bookTitle,
                    content = text
                ))
            }
        }

        // Fallback: if no content was extracted via chapter titles,
        // try splitting by blank lines
        if (chapters.isEmpty() && text.length > 100) {
            val blocks = text.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
            for ((i, block) in blocks.withIndex()) {
                chapters.add(Chapter(
                    index = i,
                    title = "Chương ${i + 1}",
                    content = block
                ))
            }
        }

        return chapters
    }

    // ── Binary helpers ───────────────────────────────

    private fun readFixedString(buffer: ByteBuffer, length: Int): String {
        val b = ByteArray(length)
        buffer.get(b)
        return String(b, Charsets.UTF_8).trimEnd('\u0000').trim()
    }

    private fun readInt32(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
               ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
               ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
               (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun findBytes(haystack: ByteArray, needle: ByteArray, startOffset: Int): Int {
        if (needle.isEmpty()) return -1
        val end = haystack.size - needle.size
        for (i in startOffset..end) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}
