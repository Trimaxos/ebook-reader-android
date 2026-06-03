package com.ebookreader.ui.reader

import android.app.Application
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.db.AppDatabase
import com.ebookreader.data.epub.EpubParser
import com.ebookreader.data.model.Book
import com.ebookreader.data.model.Chapter
import com.ebookreader.data.model.ReadingProgress
import com.ebookreader.tts.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Shared DataStore instance (same key as SettingsViewModel)
private val Application.readerDataStore by preferencesDataStore(name = "settings")

class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val parser = EpubParser()
    private val bookCache = BookCache(application)
    private val dataStore = application.readerDataStore

    val ttsClient = TtsClient()
    val ttsPlayer = TtsPlayer(application)
    val ttsManager = TtsManager(application, ttsClient, ttsPlayer)

    // Chapters metadata
    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()

    // Display items: chapter headers + sentences (flat, multi-chapter)
    private val _displayItems = MutableStateFlow<List<DisplayItem>>(emptyList())
    val displayItems: StateFlow<List<DisplayItem>> = _displayItems.asStateFlow()

    // Sentence count per chapter (for flat index ↔ chapter mapping)
    private var sentenceCountsPerChapter: List<Int> = emptyList()

    // Current position (flat index across all chapters)
    private val _currentSentenceIndex = MutableStateFlow(0)
    val currentSentenceIndex: StateFlow<Int> = _currentSentenceIndex.asStateFlow()

    // Current chapter (derived from flat index)
    private val _currentChapterIndex = MutableStateFlow(0)
    val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

    private val _currentChapter = MutableStateFlow<Chapter?>(null)
    val currentChapter: StateFlow<Chapter?> = _currentChapter.asStateFlow()

    private val _ttsState = MutableStateFlow(TtsState.IDLE)
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    // All sentence spans (flat, for TTS)
    private val _allSentenceSpans = MutableStateFlow<List<SentenceSpan>>(emptyList())
    val sentences: StateFlow<List<SentenceSpan>> = _allSentenceSpans.asStateFlow()

    private var book: Book? = null

    companion object {
        private val KEY_TTS_VOICE = stringPreferencesKey("tts_voice")
        private val KEY_TTS_RATE = stringPreferencesKey("tts_rate")
    }

    init {
        ttsManager.onStateChanged = { state ->
            _ttsState.value = state
        }
        ttsManager.onSentenceChanged = { flatIndex, _ ->
            _currentSentenceIndex.value = flatIndex
            // Update current chapter based on flat index
            updateCurrentChapter(flatIndex)
        }

        // Load voice/rate from settings and apply
        viewModelScope.launch {
            dataStore.data.collect { prefs ->
                val voice = prefs[KEY_TTS_VOICE] ?: "vi-VN-HoaiMyNeural"
                val rate = prefs[KEY_TTS_RATE] ?: "+0%"
                ttsManager.setVoice(voice)
                ttsManager.setRate(rate)
            }
        }
    }

    fun loadBook(book: Book) {
        this.book = book
        viewModelScope.launch {
            _currentChapter.value = null

            // Parse EPUB + split sentences
            val (chapters, _) = withContext(Dispatchers.IO) {
                parser.parse(getApplication(), book.filePath)
            }
            if (chapters.isEmpty()) return@launch
            _chapters.value = chapters

            // Build flat display list from ALL chapters
            val allSpans = mutableListOf<SentenceSpan>()
            val displayItems = mutableListOf<DisplayItem>()
            val counts = mutableListOf<Int>()
            val chunker = TextChunker()

            for ((chIdx, ch) in chapters.withIndex()) {
                displayItems.add(DisplayItem.Header(chIdx, ch.title))
                val chSents = chunker.splitSentences(ch.content)
                counts.add(chSents.size)
                for (s in chSents) {
                    val flatIdx = allSpans.size
                    allSpans.add(s)
                    displayItems.add(
                        DisplayItem.Sentence(flatIndex = flatIdx, chapterIndex = chIdx, span = s)
                    )
                }
            }

            sentenceCountsPerChapter = counts
            _allSentenceSpans.value = allSpans
            _displayItems.value = displayItems

            // Save to cache for next time (best-effort, không crash nếu lỗi)
            try {
                withContext(Dispatchers.IO) {
                    bookCache.put(book.id, book.filePath, BookCache.CachedBook(
                        chapters = chapters,
                        displayItems = displayItems,
                        sentenceCountsPerChapter = counts,
                        allSentenceSpans = allSpans
                    ))
                }
            } catch (_: Exception) { }

            // Restore progress (inline, không launch riêng)
            val progress = withContext(Dispatchers.IO) {
                db.readingProgressDao().getProgress(book.id)
            }
            val startChapter = progress?.chapterIndex ?: book.lastReadChapter
            val sentenceOffset = progress?.charOffset ?: 0

            val flatIndex = chapterAndOffsetToFlatIndex(startChapter, sentenceOffset)
            _currentSentenceIndex.value = flatIndex
            updateCurrentChapter(flatIndex)

            ttsManager.loadSentences(allSpans)
            ttsManager.seekToSentence(flatIndex)
        }
    }

    /**
     * Apply cached book data (load from cache, skip parsing).
     */
    private fun applyCachedData(book: Book, cached: BookCache.CachedBook) {
        _chapters.value = cached.chapters
        sentenceCountsPerChapter = cached.sentenceCountsPerChapter
        _allSentenceSpans.value = cached.allSentenceSpans
        _displayItems.value = cached.displayItems
        restoreProgress(book)
    }

    /**
     * Restore reading progress after display items are ready.
     */
    private fun restoreProgress(book: Book) {
        viewModelScope.launch {
            val progress = withContext(Dispatchers.IO) {
                db.readingProgressDao().getProgress(book.id)
            }
            val startChapter = progress?.chapterIndex ?: book.lastReadChapter
            val sentenceOffset = progress?.charOffset ?: 0

            val flatIndex = chapterAndOffsetToFlatIndex(startChapter, sentenceOffset)
            _currentSentenceIndex.value = flatIndex
            updateCurrentChapter(flatIndex)

            ttsManager.loadSentences(_allSentenceSpans.value)
            ttsManager.seekToSentence(flatIndex)
        }
    }

    /**
     * Navigate to a chapter (scroll to its first sentence).
     * No reload needed — all chapters are already flat-loaded.
     */
    fun navigateToChapter(chapterIndex: Int) {
        val chaps = _chapters.value
        if (chapterIndex < 0 || chapterIndex >= chaps.size) return
        saveProgress()
        val flatIndex = chapterAndOffsetToFlatIndex(chapterIndex, 0)
        _currentSentenceIndex.value = flatIndex
        updateCurrentChapter(flatIndex)
        ttsManager.seekToSentence(flatIndex)
    }

    fun playPause() {
        when (_ttsState.value) {
            TtsState.IDLE, TtsState.STOPPED -> ttsManager.play()
            TtsState.PLAYING -> ttsManager.pause()
            TtsState.PAUSED -> ttsManager.play()
            else -> {}
        }
    }

    /**
     * Start playback from a specific flat sentence index (visible on screen).
     */
    fun startPlaybackFrom(flatIndex: Int) {
        if (_ttsState.value == TtsState.IDLE || _ttsState.value == TtsState.STOPPED) {
            ttsManager.seekToSentence(flatIndex)
            _currentSentenceIndex.value = flatIndex
            updateCurrentChapter(flatIndex)
            ttsManager.play()
        }
    }

    fun stop() {
        ttsManager.stop()
    }

    fun saveProgress() {
        book?.let { b ->
            val flatIdx = _currentSentenceIndex.value
            val (chIdx, offset) = flatIndexToChapterAndOffset(flatIdx)
            viewModelScope.launch {
                db.readingProgressDao().upsert(
                    ReadingProgress(
                        bookId = b.id,
                        chapterIndex = chIdx,
                        charOffset = offset
                    )
                )
            }
        }
    }

    fun updateTtsServerUrl(url: String) {
        ttsClient.updateServerUrl(url)
    }

    fun updateVoice(voice: String) {
        ttsManager.setVoice(voice)
    }

    fun updateRate(rate: String) {
        ttsManager.setRate(rate)
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.cleanup()
    }

    // ── Private helpers ──

    private fun updateCurrentChapter(flatIndex: Int) {
        val (chIdx, _) = flatIndexToChapterAndOffset(flatIndex)
        _currentChapterIndex.value = chIdx
        val chaps = _chapters.value
        if (chIdx in chaps.indices) {
            _currentChapter.value = chaps[chIdx]
        }
    }

    private fun chapterAndOffsetToFlatIndex(chapterIndex: Int, offset: Int): Int {
        var result = 0
        for (i in 0 until chapterIndex.coerceAtMost(sentenceCountsPerChapter.size)) {
            result += sentenceCountsPerChapter[i]
        }
        return result + offset
    }

    private fun flatIndexToChapterAndOffset(flatIndex: Int): Pair<Int, Int> {
        if (flatIndex <= 0 || sentenceCountsPerChapter.isEmpty()) return Pair(0, 0)
        var remaining = flatIndex
        for ((chIdx, count) in sentenceCountsPerChapter.withIndex()) {
            if (remaining < count) return Pair(chIdx, remaining)
            remaining -= count
        }
        // Fallback: last chapter
        val lastIdx = sentenceCountsPerChapter.size - 1
        return Pair(lastIdx, sentenceCountsPerChapter.getOrElse(lastIdx) { 0 })
    }
}
