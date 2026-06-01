package com.ebookreader.tts

data class SentenceSpan(
    val text: String,
    val startOffset: Int,
    val endOffset: Int
)

class TextChunker {

    companion object {
        private const val MAX_CHUNK_LENGTH = 3000
        private const val MERGE_THRESHOLD = 30
        private val sentenceSplitRegex = Regex(
            "(?<=[.!?])\\s+|(?<=[.!?])(?=\\n)|(?<=\\n)\\s*"
        )
    }

    fun splitSentences(text: String): List<SentenceSpan> {
        val sentences = mutableListOf<SentenceSpan>()
        val parts = text.split(sentenceSplitRegex)
        var offset = 0

        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isBlank()) {
                offset += part.length
                continue
            }

            val startIdx = text.indexOf(trimmed, offset)
            if (startIdx >= 0) {
                sentences.add(SentenceSpan(
                    text = trimmed,
                    startOffset = startIdx,
                    endOffset = startIdx + trimmed.length
                ))
                offset = startIdx + trimmed.length
            }
        }

        // Merge very short sentences with next
        return mergeShortSentences(sentences)
    }

    fun splitIntoChunks(sentences: List<SentenceSpan>): List<List<SentenceSpan>> {
        val chunks = mutableListOf<List<SentenceSpan>>()
        val currentChunk = mutableListOf<SentenceSpan>()
        var currentLength = 0

        for (sentence in sentences) {
            if (currentLength + sentence.text.length > MAX_CHUNK_LENGTH && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.toList())
                currentChunk.clear()
                currentLength = 0
            }
            currentChunk.add(sentence)
            currentLength += sentence.text.length
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk)
        }

        return chunks
    }

    private fun mergeShortSentences(sentences: List<SentenceSpan>): List<SentenceSpan> {
        if (sentences.isEmpty()) return emptyList()

        val result = mutableListOf<SentenceSpan>()
        var current = sentences[0]

        for (i in 1 until sentences.size) {
            val next = sentences[i]
            if (current.text.length < MERGE_THRESHOLD) {
                current = SentenceSpan(
                    text = "${current.text} ${next.text}",
                    startOffset = current.startOffset,
                    endOffset = next.endOffset
                )
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return result
    }
}
