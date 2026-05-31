package com.ebookreader.tts

import android.content.Context
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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

    // Pre-buffer: keep N sentences ready in advance
    private val prebufferMap = ConcurrentHashMap<Int, ByteArray>()
    private val prebufferJobs = ConcurrentHashMap<Int, Job>()
    private var prebufferScope: CoroutineScope? = null

    companion object {
        /** Number of sentences to pre-buffer ahead of current */
        private const val PREBUFFER_DEPTH = 5
    }

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
        currentSentenceIndex = 0
        clearPrebuffer()
    }

    fun loadSentences(sentences: List<SentenceSpan>) {
        this.sentences = sentences
        currentSentenceIndex = 0
        clearPrebuffer()
    }

    fun play() {
        when (state) {
            TtsState.IDLE, TtsState.STOPPED -> {
                clearPrebuffer()
                // Play from currentSentenceIndex (may have been set by seekToSentence)
                playSentence(currentSentenceIndex)
                // In parallel, pre-buffer sentences 1 through 5
                fillPrebuffer()
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
        clearPrebuffer()
        player.stop()
        setState(TtsState.STOPPED)
    }

    fun seekToSentence(index: Int) {
        if (index < 0 || index >= sentences.size) return
        currentSentenceIndex = index
        clearPrebuffer()

        if (state == TtsState.PLAYING || state == TtsState.PAUSED) {
            player.stop()
            playSentence(index)
            fillPrebuffer()
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
        clearPrebuffer()
        scope.cancel()
        player.cleanup()
    }

    // ── Playback ──────────────────────────────────

    private fun playSentence(index: Int) {
        if (index >= sentences.size) {
            setState(TtsState.IDLE)
            return
        }

        playJob?.cancel()
        playJob = scope.launch {
            setState(TtsState.LOADING)

            val sentence = sentences[index]
            onSentenceChanged?.invoke(index, sentence)

            // Check pre-buffer first
            val audioBytes = prebufferMap.remove(index)

            val bytes = if (audioBytes != null) {
                audioBytes
            } else {
                // Fallback: synthesize inline (first sentence or seek)
                synthesizeSentence(sentence) ?: return@launch
            }

            withContext(Dispatchers.Main) {
                player.play(bytes)
                setState(TtsState.PLAYING)
            }
        }
    }

    /**
     * Called by ExoPlayer when a sentence finishes playing.
     * Advances to the next sentence immediately.
     */
    private fun playNextSentence() {
        currentSentenceIndex++

        if (currentSentenceIndex >= sentences.size) {
            setState(TtsState.IDLE)
            return
        }

        // Re-fill pre-buffer to maintain depth
        fillPrebuffer()

        // Play next sentence (should already be pre-buffered)
        playSentence(currentSentenceIndex)
    }

    /**
     * Keep exactly PREBUFFER_DEPTH sentences pre-buffered ahead of current.
     * Launches parallel coroutines for each missing sentence.
     */
    private fun fillPrebuffer() {
        // Create a child scope so pre-buffer jobs survive playJob cancellation
        if (prebufferScope?.isActive != true) {
            prebufferScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }

        val targetLast = currentSentenceIndex + PREBUFFER_DEPTH
        for (idx in (currentSentenceIndex + 1)..targetLast) {
            if (idx >= sentences.size) break
            // Already buffered or already being fetched → skip
            if (prebufferMap.containsKey(idx)) continue
            if (prebufferJobs.containsKey(idx)) continue

            val job = prebufferScope!!.launch {
                val sentence = sentences[idx]
                val bytes = synthesizeSentence(sentence)
                if (bytes != null) {
                    prebufferMap[idx] = bytes
                    prebufferJobs.remove(idx)
                }
            }
            prebufferJobs[idx] = job
        }
    }

    /**
     * Synthesize a single sentence via TTS server, with audio cache.
     */
    private suspend fun synthesizeSentence(sentence: SentenceSpan): ByteArray? {
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
            null
        }
    }

    /** Clear all pre-buffered data and cancel in-flight jobs. */
    private fun clearPrebuffer() {
        prebufferMap.clear()
        prebufferJobs.values.forEach { it.cancel() }
        prebufferJobs.clear()
        prebufferScope?.cancel()
        prebufferScope = null
    }

    private fun setState(newState: TtsState) {
        state = newState
        onStateChanged?.invoke(newState)
    }
}
