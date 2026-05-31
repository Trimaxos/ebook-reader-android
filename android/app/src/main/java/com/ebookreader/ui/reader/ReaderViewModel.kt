package com.ebookreader.ui.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.db.AppDatabase
import com.ebookreader.data.epub.EpubParser
import com.ebookreader.data.model.Book
import com.ebookreader.data.model.Chapter
import com.ebookreader.data.model.ReadingProgress
import com.ebookreader.tts.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val parser = EpubParser()

    val ttsClient = TtsClient()
    val ttsPlayer = TtsPlayer(application)
    val ttsManager = TtsManager(application, ttsClient, ttsPlayer)

    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()

    private val _currentChapter = MutableStateFlow<Chapter?>(null)
    val currentChapter: StateFlow<Chapter?> = _currentChapter.asStateFlow()

    private val _currentChapterIndex = MutableStateFlow(0)
    val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

    private val _currentSentenceIndex = MutableStateFlow(0)
    val currentSentenceIndex: StateFlow<Int> = _currentSentenceIndex.asStateFlow()

    private val _ttsState = MutableStateFlow(TtsState.IDLE)
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    private val _sentences = MutableStateFlow<List<SentenceSpan>>(emptyList())
    val sentences: StateFlow<List<SentenceSpan>> = _sentences.asStateFlow()

    private var book: Book? = null

    init {
        ttsManager.onStateChanged = { state ->
            _ttsState.value = state
        }
        ttsManager.onSentenceChanged = { index, _ ->
            _currentSentenceIndex.value = index
        }
    }

    fun loadBook(book: Book) {
        this.book = book
        viewModelScope.launch {
            val (chapters, _) = parser.parse(getApplication(), book.filePath)
            _chapters.value = chapters

            // Restore progress
            val progress = db.readingProgressDao().getProgress(book.id)
            val startChapter = progress?.chapterIndex ?: book.lastReadChapter
            loadChapter(startChapter)
        }
    }

    fun loadChapter(index: Int) {
        val chaps = _chapters.value
        if (index < 0 || index >= chaps.size) return

        _currentChapterIndex.value = index
        val chapter = chaps[index]
        _currentChapter.value = chapter

        // Prepare TTS
        val sentences = TextChunker().splitSentences(chapter.content)
        _sentences.value = sentences
        ttsManager.loadSentences(sentences)
    }

    fun playPause() {
        when (_ttsState.value) {
            TtsState.IDLE, TtsState.STOPPED -> ttsManager.play()
            TtsState.PLAYING -> ttsManager.pause()
            TtsState.PAUSED -> ttsManager.play()
            else -> {}
        }
    }

    fun stop() {
        ttsManager.stop()
    }

    fun saveProgress() {
        book?.let { b ->
            viewModelScope.launch {
                db.readingProgressDao().upsert(
                    ReadingProgress(
                        bookId = b.id,
                        chapterIndex = _currentChapterIndex.value,
                        charOffset = _currentSentenceIndex.value
                    )
                )
            }
        }
    }

    fun updateTtsServerUrl(url: String) {
        ttsClient.updateServerUrl(url)
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.cleanup()
    }
}
