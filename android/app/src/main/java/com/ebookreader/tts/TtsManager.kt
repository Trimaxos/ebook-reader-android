package com.ebookreader.tts

import android.content.Context
import kotlinx.coroutines.*
import java.io.File

enum class TtsState {
    IDLE, PLAYING, PAUSED, STOPPED, LOADING
}

class TtsManager(
    private val context: Context,
    private val client: TtsClient,
    private val player: TtsPlayer,
    private val textChunker: TextChunker = TextChunker()
) {
    private var audioCache: AudioCache? = null
    private var sentences: List<SentenceSpan> = emptyList()
    private var chunks: List<List<SentenceSpan>> = emptyList()
    private var currentChunkIndex = 0

    // Absolute sentence index across ALL sentences (for highlighting)
    private var currentSentenceIndex = 0

    // Pre-buffer state
    private var nextChunkAudio: ByteArray? = null
    private var nextChunkIndex: Int = -1
    private var prebufferJob: Job? = null

    // Sentence tracking during chunk playback
    private var trackingJob: Job? = null

    var state: TtsState = TtsState.IDLE
        private set

    var onStateChanged: ((TtsState) -> Unit)? = null
    var onSentenceChanged: ((Int, SentenceSpan) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var playJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var voice = "vi-VN-HoaiMyNeural"
    private var rate = "+0%"

    init {
        audioCache = AudioCache(File(context.cacheDir, "tts_cache"))

        player.onPlaybackComplete = {
            onChunkComplete()
        }
    }

    fun setVoice(voice: String) { this.voice = voice }
    fun setRate(rate: String) { this.rate = rate }

    fun loadText(text: String) {
        sentences = textChunker.splitSentences(text)
        chunks = textChunker.splitIntoChunks(sentences)
        currentChunkIndex = 0
        currentSentenceIndex = 0
        nextChunkAudio = null
        nextChunkIndex = -1
        prebufferJob?.cancel()
    }

    fun loadSentences(sentences: List<SentenceSpan>) {
        this.sentences = sentences
        chunks = textChunker.splitIntoChunks(sentences)
        currentChunkIndex = 0
        currentSentenceIndex = 0
        nextChunkAudio = null
        nextChunkIndex = -1
        prebufferJob?.cancel()
    }

    fun play() {
        when (state) {
            TtsState.IDLE, TtsState.STOPPED -> {
                currentChunkIndex = if (currentSentenceIndex > 0) {
                    getChunkForSentence(currentSentenceIndex)
                } else 0
                nextChunkAudio = null
                nextChunkIndex = -1
                prebufferJob?.cancel()
                playCurrentChunk()
            }
            TtsState.PAUSED -> {
                player.resume()
                setState(TtsState.PLAYING)
                startSentenceTracking()
            }
            else -> {}
        }
    }

    fun pause() {
        if (state == TtsState.PLAYING) {
            trackingJob?.cancel()
            player.pause()
            setState(TtsState.PAUSED)
        }
    }

    fun stop() {
        playJob?.cancel()
        prebufferJob?.cancel()
        trackingJob?.cancel()
        nextChunkAudio = null
        nextChunkIndex = -1
        player.stop()
        setState(TtsState.STOPPED)
    }

    fun seekToSentence(index: Int) {
        if (index < 0 || index >= sentences.size) return
        currentSentenceIndex = index
        currentChunkIndex = getChunkForSentence(index)
        nextChunkAudio = null
        nextChunkIndex = -1
        prebufferJob?.cancel()
        trackingJob?.cancel()

        if (state == TtsState.PLAYING || state == TtsState.PAUSED) {
            player.stop()
            playCurrentChunk()
        }
    }

    fun getCurrentSentenceIndex(): Int = currentSentenceIndex
    fun getSentenceCount(): Int = sentences.size
    fun getSentences(): List<SentenceSpan> = sentences

    fun seekForward() {
        seekToSentence(currentSentenceIndex + 1)
    }

    fun seekBackward() {
        seekToSentence(currentSentenceIndex - 1)
    }

    fun cleanup() {
        playJob?.cancel()
        prebufferJob?.cancel()
        trackingJob?.cancel()
        scope.cancel()
        player.cleanup()
    }

    /**
     * Play the current chunk: synthesize ALL sentences in the chunk as ONE request.
     */
    private fun playCurrentChunk() {
        if (currentSentenceIndex >= sentences.size) {
            setState(TtsState.IDLE)
            return
        }

        playJob?.cancel()
        playJob = scope.launch {
            setState(TtsState.LOADING)

            val chunkSentences = getSentencesForCurrentChunk()
            if (chunkSentences.isEmpty()) {
                setState(TtsState.IDLE)
                return@launch
            }

            // Highlight first sentence of chunk
            onSentenceChanged?.invoke(currentSentenceIndex, sentences[currentSentenceIndex])

            // Build chunk text: combine all sentences in this chunk
            val chunkText = chunkSentences.joinToString(" ") { it.text }

            // Use pre-buffered audio if available
            val audioBytes = if (nextChunkAudio != null && nextChunkIndex == currentChunkIndex) {
                val bytes = nextChunkAudio!!
                nextChunkAudio = null
                bytes
            } else {
                synthesizeChunk(chunkText) ?: return@launch
            }

            withContext(Dispatchers.Main) {
                player.play(audioBytes)
                setState(TtsState.PLAYING)
                // Track sentence position during playback
                startSentenceTracking()
            }

            // Pre-buffer next chunk in background while current plays
            startPrebuffer()
        }
    }

    /**
     * Called when chunk audio finishes playing.
     */
    private fun onChunkComplete() {
        if (state != TtsState.PLAYING) return

        // Advance to next chunk
        val chunkSentences = getSentencesForCurrentChunk()
        currentSentenceIndex += chunkSentences.size
        currentChunkIndex++

        if (currentSentenceIndex >= sentences.size) {
            setState(TtsState.IDLE)
            return
        }

        playCurrentChunk()
    }

    /**
     * Start a coroutine that polls player position to advance sentence highlighting.
     * Uses char-count proportion within the current chunk for timing estimation.
     */
    private fun startSentenceTracking() {
        trackingJob?.cancel()
        trackingJob = scope.launch {
            val chunkSentences = getSentencesForCurrentChunk()
            if (chunkSentences.isEmpty()) return@launch

            val totalChars = chunkSentences.sumOf { it.text.length }.toFloat()
            if (totalChars <= 0f) return@launch

            val chunkStartSentenceIndex = currentSentenceIndex
            var lastReportedSentenceIndex = currentSentenceIndex

            while (isActive && state == TtsState.PLAYING) {
                val currentPos = player.getCurrentPosition()
                val totalDuration = player.getDuration()
                if (totalDuration <= 0) {
                    delay(250)
                    continue
                }

                val progress = (currentPos.toFloat() / totalDuration.toFloat())
                    .coerceIn(0f, 1f)

                // Map progress to sentence index based on char-count proportion
                var accum = 0f
                var targetIdx = chunkStartSentenceIndex
                for (s in chunkSentences) {
                    val nextAccum = accum + s.text.length
                    val sentenceProgress = nextAccum / totalChars
                    if (progress < sentenceProgress) break
                    accum = nextAccum
                    targetIdx++
                }

                if (targetIdx != lastReportedSentenceIndex) {
                    lastReportedSentenceIndex = targetIdx
                    if (targetIdx < sentences.size) {
                        onSentenceChanged?.invoke(targetIdx, sentences[targetIdx])
                    }
                }

                delay(250) // 4x per second
            }
        }
    }

    /**
     * Synthesize chunk text as ONE request to the TTS server.
     * AudioCache MD5-hashes the full text internally, so pass raw text.
     */
    private suspend fun synthesizeChunk(text: String): ByteArray? {
        val cached = audioCache?.get(text, voice, rate)
        if (cached != null) return cached

        val result = client.synthesize(text, voice, rate)
        if (result.isSuccess) {
            val bytes = result.getOrThrow()
            audioCache?.put(text, voice, rate, bytes)
            return bytes
        } else {
            withContext(Dispatchers.Main) {
                onError?.invoke(result.exceptionOrNull()?.message ?: "TTS failed")
            }
            setState(TtsState.IDLE)
            return null
        }
    }

    /**
     * Start synthesizing the NEXT chunk while current one plays.
     */
    private fun startPrebuffer() {
        val nextChunkIdx = currentChunkIndex + 1
        if (nextChunkIdx >= chunks.size) return

        prebufferJob?.cancel()
        nextChunkAudio = null
        nextChunkIndex = -1

        prebufferJob = scope.launch {
            val nextSentences = chunks[nextChunkIdx]
            val chunkText = nextSentences.joinToString(" ") { it.text }

            val cached = audioCache?.get(chunkText, voice, rate)
            if (cached != null) {
                nextChunkAudio = cached
                nextChunkIndex = nextChunkIdx
                return@launch
            }

            val result = client.synthesize(chunkText, voice, rate)
            if (result.isSuccess) {
                val bytes = result.getOrThrow()
                audioCache?.put(chunkText, voice, rate, bytes)
                nextChunkAudio = bytes
                nextChunkIndex = nextChunkIdx
            }
            // On failure, playCurrentChunk will synthesize inline
        }
    }

    /**
     * Get sentences belonging to the current chunk, starting from currentSentenceIndex.
     */
    private fun getSentencesForCurrentChunk(): List<SentenceSpan> {
        if (currentChunkIndex >= chunks.size || chunks.isEmpty()) return emptyList()
        val chunk = chunks[currentChunkIndex]
        // Adjust: if we're mid-chunk (after seek), only return remaining sentences
        val offsetInChunk = currentSentenceIndex - getChunkStartSentenceIndex(currentChunkIndex)
        if (offsetInChunk >= chunk.size) return emptyList()
        return chunk.drop(offsetInChunk)
    }

    private fun getChunkStartSentenceIndex(chunkIdx: Int): Int {
        var count = 0
        for (i in 0 until chunkIdx.coerceAtMost(chunks.size)) {
            count += chunks[i].size
        }
        return count
    }

    private fun getChunkForSentence(sentenceIndex: Int): Int {
        var count = 0
        for ((chunkIdx, chunk) in chunks.withIndex()) {
            count += chunk.size
            if (sentenceIndex < count) return chunkIdx
        }
        return (chunks.size - 1).coerceAtLeast(0)
    }

    private fun setState(newState: TtsState) {
        state = newState
        onStateChanged?.invoke(newState)
    }
}
