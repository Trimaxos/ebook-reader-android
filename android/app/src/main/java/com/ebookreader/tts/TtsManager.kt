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
    private var currentSentenceIndex = 0
    private var currentChunkIndex = 0
    private var chunks: List<List<SentenceSpan>> = emptyList()

    // Pre-buffer: next sentence audio ready to play
    private var nextAudioBytes: ByteArray? = null
    private var nextSentenceIndex: Int = -1
    private var prebufferJob: Job? = null

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
            playNextSentence()
        }
    }

    fun setVoice(voice: String) { this.voice = voice }
    fun setRate(rate: String) { this.rate = rate }

    fun loadText(text: String) {
        sentences = textChunker.splitSentences(text)
        chunks = textChunker.splitIntoChunks(sentences)
        currentSentenceIndex = 0
        currentChunkIndex = 0
        nextAudioBytes = null
        prebufferJob?.cancel()
    }

    fun loadSentences(sentences: List<SentenceSpan>) {
        this.sentences = sentences
        chunks = textChunker.splitIntoChunks(sentences)
        currentSentenceIndex = 0
        currentChunkIndex = 0
        nextAudioBytes = null
        prebufferJob?.cancel()
    }

    fun play() {
        when (state) {
            TtsState.IDLE, TtsState.STOPPED -> {
                currentSentenceIndex = 0
                currentChunkIndex = 0
                nextAudioBytes = null
                prebufferJob?.cancel()
                playCurrentSentence()
            }
            TtsState.PAUSED -> {
                player.resume()
                setState(TtsState.PLAYING)
            }
            else -> {}
        }
    }

    fun pause() {
        if (state == TtsState.PLAYING) {
            player.pause()
            setState(TtsState.PAUSED)
        }
    }

    fun stop() {
        playJob?.cancel()
        prebufferJob?.cancel()
        nextAudioBytes = null
        player.stop()
        setState(TtsState.STOPPED)
    }

    fun seekToSentence(index: Int) {
        if (index < 0 || index >= sentences.size) return
        currentSentenceIndex = index
        currentChunkIndex = getChunkForSentence(index)
        nextAudioBytes = null
        prebufferJob?.cancel()

        if (state == TtsState.PLAYING || state == TtsState.PAUSED) {
            player.stop()
            playCurrentSentence()
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
        scope.cancel()
        player.cleanup()
    }

    private fun playCurrentSentence() {
        if (currentSentenceIndex >= sentences.size) {
            setState(TtsState.IDLE)
            return
        }

        playJob?.cancel()
        playJob = scope.launch {
            setState(TtsState.LOADING)

            val sentence = sentences[currentSentenceIndex]
            onSentenceChanged?.invoke(currentSentenceIndex, sentence)

            // Use pre-buffered audio if available
            val audioBytes = if (nextAudioBytes != null && nextSentenceIndex == currentSentenceIndex) {
                val bytes = nextAudioBytes!!
                nextAudioBytes = null
                bytes
            } else {
                synthesizeSentence(sentence) ?: return@launch
            }

            withContext(Dispatchers.Main) {
                player.play(audioBytes)
                setState(TtsState.PLAYING)
            }

            // Start pre-buffering the next sentence immediately
            startPrebuffer()
        }
    }

    private suspend fun synthesizeSentence(sentence: SentenceSpan): ByteArray? {
        // Check cache first
        val cached = audioCache?.get(sentence.text, voice, rate)
        if (cached != null) return cached

        val result = client.synthesize(sentence.text, voice, rate)
        return if (result.isSuccess) {
            val bytes = result.getOrThrow()
            audioCache?.put(sentence.text, voice, rate, bytes)
            bytes
        } else {
            withContext(Dispatchers.Main) {
                onError?.invoke(result.exceptionOrNull()?.message ?: "TTS failed")
            }
            setState(TtsState.IDLE)
            null
        }
    }

    /**
     * Start synthesizing the NEXT sentence in background while current one plays.
     */
    private fun startPrebuffer() {
        val nextIdx = currentSentenceIndex + 1
        if (nextIdx >= sentences.size) return

        prebufferJob?.cancel()
        nextAudioBytes = null
        nextSentenceIndex = -1

        prebufferJob = scope.launch {
            val sentence = sentences[nextIdx]
            // Check cache first (fast path)
            val cached = audioCache?.get(sentence.text, voice, rate)
            if (cached != null) {
                nextAudioBytes = cached
                nextSentenceIndex = nextIdx
                return@launch
            }

            // Synthesize in background
            val result = client.synthesize(sentence.text, voice, rate)
            if (result.isSuccess) {
                val bytes = result.getOrThrow()
                audioCache?.put(sentence.text, voice, rate, bytes)
                nextAudioBytes = bytes
                nextSentenceIndex = nextIdx
            }
            // On failure, nextSentenceIndex stays -1, playNextSentence will synthesize inline
        }
    }

    private fun playNextSentence() {
        currentSentenceIndex++
        // Start pre-buffering the one after next
        startPrebuffer()
        playCurrentSentence()
    }

    private fun getChunkForSentence(sentenceIndex: Int): Int {
        var count = 0
        for ((chunkIdx, chunk) in chunks.withIndex()) {
            count += chunk.size
            if (sentenceIndex < count) return chunkIdx
        }
        return chunks.size - 1
    }

    private fun setState(newState: TtsState) {
        state = newState
        onStateChanged?.invoke(newState)
    }
}
