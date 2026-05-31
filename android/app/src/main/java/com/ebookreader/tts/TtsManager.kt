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
    }

    fun loadSentences(sentences: List<SentenceSpan>) {
        this.sentences = sentences
        chunks = textChunker.splitIntoChunks(sentences)
        currentSentenceIndex = 0
        currentChunkIndex = 0
    }

    fun play() {
        when (state) {
            TtsState.IDLE, TtsState.STOPPED -> {
                currentSentenceIndex = 0
                currentChunkIndex = 0
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
        player.stop()
        setState(TtsState.STOPPED)
    }

    fun seekToSentence(index: Int) {
        if (index < 0 || index >= sentences.size) return
        currentSentenceIndex = index
        // Find which chunk this sentence belongs to
        currentChunkIndex = getChunkForSentence(index)

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

            // Check cache first
            val cached = audioCache?.get(sentence.text, voice, rate)
            val audioBytes = if (cached != null) {
                cached
            } else {
                val result = client.synthesize(sentence.text, voice, rate)
                if (result.isSuccess) {
                    val bytes = result.getOrThrow()
                    audioCache?.put(sentence.text, voice, rate, bytes)
                    bytes
                } else {
                    withContext(Dispatchers.Main) {
                        onError?.invoke(result.exceptionOrNull()?.message ?: "TTS failed")
                    }
                    setState(TtsState.IDLE)
                    return@launch
                }
            }

            withContext(Dispatchers.Main) {
                player.play(audioBytes)
                setState(TtsState.PLAYING)
            }
        }
    }

    private fun playNextSentence() {
        currentSentenceIndex++
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
