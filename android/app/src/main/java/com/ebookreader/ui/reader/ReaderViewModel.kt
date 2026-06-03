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

private val Application.readerDataStore by preferencesDataStore(name = "settings")

class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val parser = EpubParser()
    private val dataStore = application.readerDataStore

    val ttsClient = TtsClient()
    val ttsPlayer = TtsPlayer(application)
    val ttsManager = TtsManager(application, ttsClient, ttsPlayer)

    // ── Chapter metadata (always available, no content) ──
    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()

    // ── Lazy-loaded sentence cache ──
    private val sentenceCache = mutableMapOf<Int, List<SentenceSpan>>()

    // ── Display items (only loaded chapters) ──
    private val _displayItems = MutableStateFlow<List<DisplayItem>>(emptyList())
    val displayItems: StateFlow<List<DisplayItem>> = _displayItems.asStateFlow()

    // ── Sentence count per loaded chapter ──
    private var sentenceCountsPerChapter = mutableListOf<Int>()

    // ── Current position ──
    private val _currentChapterIndex = MutableStateFlow(0)
    val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

    private val _currentSentenceInChapter = MutableStateFlow(0)
    val currentSentenceInChapter: StateFlow<Int> = _currentSentenceInChapter.asStateFlow()

    /** Flat index for TTS (derived: sum of prev chapter counts + offset) */
    private val _currentSentenceIndex = MutableStateFlow(0)
    val currentSentenceIndex: StateFlow<Int> = _currentSentenceIndex.asStateFlow()
    private var currentFlatIndex: Int = 0

    private val _ttsState = MutableStateFlow(TtsState.IDLE)
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    private val _currentChapter = MutableStateFlow<Chapter?>(null)
    val currentChapter: StateFlow<Chapter?> = _currentChapter.asStateFlow()

    private var book: Book? = null
    private var rawChapters: List<Chapter> = emptyList()

    companion object {
        private val KEY_TTS_VOICE = stringPreferencesKey("tts_voice")
        private val KEY_TTS_RATE = stringPreferencesKey("tts_rate")
        private const val LOOKAHEAD_CHAPTERS = 2 // Number of chapters to pre-load ahead
    }

    init {
        ttsManager.onStateChanged = { state -> _ttsState.value = state }
        ttsManager.onSentenceChanged = { flatIndex, _ ->
            currentFlatIndex = flatIndex
            _currentSentenceIndex.value = flatIndex
            val (chIdx, offset) = flatIndexToChapterAndOffset(flatIndex)
            _currentChapterIndex.value = chIdx
            _currentSentenceInChapter.value = offset
            if (chIdx in rawChapters.indices) _currentChapter.value = rawChapters[chIdx]

            // Auto-load next chapter when nearing end
            val chCount = sentenceCountsPerChapter.getOrElse(chIdx) { 0 }
            if (offset >= chCount - 3) { // 3 sentences before end of chapter
                ensureChaptersLoaded(chIdx + 1)
            }
        }

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

            // Parse EPUB (fast: strip HTML only, no sentence splitting)
            val (chapters, _) = withContext(Dispatchers.IO) {
                parser.parse(getApplication(), book.filePath)
            }
            if (chapters.isEmpty()) return@launch
            rawChapters = chapters
            _chapters.value = chapters

            // Restore progress
            val progress = withContext(Dispatchers.IO) {
                db.readingProgressDao().getProgress(book.id)
            }
            val startChapter = progress?.chapterIndex ?: book.lastReadChapter
            val sentenceOffset = progress?.charOffset ?: 0

            // Load current chapter + lookahead
            ensureChaptersLoaded(startChapter)
            _currentChapterIndex.value = startChapter
            _currentSentenceInChapter.value = sentenceOffset
            if (startChapter in chapters.indices) _currentChapter.value = chapters[startChapter]
            currentFlatIndex = chapterToFlatIndex(startChapter, sentenceOffset)
            _currentSentenceIndex.value = currentFlatIndex
            ttsManager.seekToSentence(currentFlatIndex)
        }
    }

    /**
     * Ensure chapters FORWARD from the given index are loaded (xuôi).
     * Loads current chapter + 2 chapters ahead.
     * Không load ngược để tránh LazyColumn nhảy do prepend items.
     */
    private fun ensureChaptersLoaded(chapterIndex: Int) {
        val endIdx = (chapterIndex + 2).coerceAtMost(rawChapters.size - 1)
        var changed = false
        for (i in chapterIndex..endIdx) {
            if (i !in sentenceCache) {
                val chunker = TextChunker()
                val sents = chunker.splitSentences(rawChapters[i].content)
                sentenceCache[i] = sents
                changed = true
            }
        }
        // Cũng load 1 chương ngược nếu chưa có (chỉ 1, tránh nhảy nhiều)
        val prevIdx = chapterIndex - 1
        if (prevIdx >= 0 && prevIdx !in sentenceCache) {
            val chunker = TextChunker()
            val sents = chunker.splitSentences(rawChapters[prevIdx].content)
            sentenceCache[prevIdx] = sents
            changed = true
        }
        if (changed) rebuildDisplay()
    }

    /**
     * Rebuild display items + TTS sentences from ALL cached chapters.
     */
    private fun rebuildDisplay() {
        val items = mutableListOf<DisplayItem>()
        val allSpans = mutableListOf<SentenceSpan>()
        var flatIdx = 0

        val sortedChs = sentenceCache.keys.sorted()
        for (chIdx in sortedChs) {
            if (chIdx !in rawChapters.indices) continue
            items.add(DisplayItem.Header(chIdx, rawChapters[chIdx].title))
            val sents = sentenceCache[chIdx]!!
            for (s in sents) {
                items.add(DisplayItem.Sentence(flatIndex = flatIdx, chapterIndex = chIdx, span = s))
                allSpans.add(s)
                flatIdx++
            }
        }

        _displayItems.value = items
        ttsManager.loadSentences(allSpans)
    }

    /** Load and navigate to a chapter. */
    fun navigateToChapter(chapterIndex: Int) {
        if (chapterIndex < 0 || chapterIndex >= rawChapters.size) return
        saveProgress()
        ensureChaptersLoaded(chapterIndex)
        _currentChapterIndex.value = chapterIndex
        _currentSentenceInChapter.value = 0
        if (chapterIndex in rawChapters.indices) _currentChapter.value = rawChapters[chapterIndex]
        currentFlatIndex = chapterToFlatIndex(chapterIndex, 0)
        _currentSentenceIndex.value = currentFlatIndex
        ttsManager.seekToSentence(currentFlatIndex)
    }

    fun playPause() {
        when (_ttsState.value) {
            TtsState.IDLE, TtsState.STOPPED -> ttsManager.play()
            TtsState.PLAYING -> ttsManager.pause()
            TtsState.PAUSED -> ttsManager.play()
            else -> {}
        }
    }

    fun startPlaybackFrom(flatIndex: Int) {
        if (_ttsState.value == TtsState.IDLE || _ttsState.value == TtsState.STOPPED) {
            currentFlatIndex = flatIndex
            _currentSentenceIndex.value = flatIndex
            ttsManager.seekToSentence(flatIndex)
            ttsManager.play()
        }
    }

    fun stop() { ttsManager.stop() }

    /** Called by Screen when a chapter becomes visible (scroll detection). */
    fun onChapterViewed(chapterIndex: Int) {
        ensureChaptersLoaded(chapterIndex)
    }

    fun saveProgress() {
        book?.let { b ->
            val (chIdx, offset) = flatIndexToChapterAndOffset(currentFlatIndex)
            viewModelScope.launch {
                db.readingProgressDao().upsert(
                    ReadingProgress(bookId = b.id, chapterIndex = chIdx, charOffset = offset)
                )
            }
        }
    }

    fun updateTtsServerUrl(url: String) { ttsClient.updateServerUrl(url) }
    fun updateVoice(voice: String) { ttsManager.setVoice(voice) }
    fun updateRate(rate: String) { ttsManager.setRate(rate) }

    override fun onCleared() {
        super.onCleared()
        ttsManager.cleanup()
    }

    // ── Helpers ──

    /** Chapter index + offset → flat index (dùng loaded chapters order) */
    private fun chapterToFlatIndex(chapterIndex: Int, offset: Int): Int {
        var result = 0
        for (chIdx in sentenceCache.keys.sorted()) {
            if (chIdx >= chapterIndex) break
            result += sentenceCache[chIdx]!!.size
        }
        return result + offset
    }

    /** Flat index → chapter index + offset in chapter */
    private fun flatIndexToChapterAndOffset(flatIndex: Int): Pair<Int, Int> {
        val indices = sentenceCache.keys.sorted()
        if (flatIndex <= 0 || indices.isEmpty()) return Pair(indices.firstOrNull() ?: 0, 0)
        var remaining = flatIndex
        for (chIdx in indices) {
            val count = sentenceCache[chIdx]!!.size
            if (remaining < count) return Pair(chIdx, remaining)
            remaining -= count
        }
        val last = indices.last()
        return Pair(last, sentenceCache[last]?.size ?: 0)
    }
}
