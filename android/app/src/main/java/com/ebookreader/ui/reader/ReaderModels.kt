package com.ebookreader.ui.reader

import com.ebookreader.tts.SentenceSpan

/** Một câu trong danh sách phẳng (tất cả chương gộp lại) */
data class FlatSentence(
    val flatIndex: Int,       // chỉ mục toàn cục (xuyên suốt các chương)
    val chapterIndex: Int,    // chương chứa câu này
    val chapterTitle: String, // tên chương
    val span: SentenceSpan    // nội dung câu
)

/** Item hiển thị trong LazyColumn (header chương hoặc câu) */
sealed class DisplayItem {
    data class Header(val chapterIndex: Int, val title: String) : DisplayItem()
    data class Sentence(
        val flatIndex: Int,
        val chapterIndex: Int,
        val span: SentenceSpan
    ) : DisplayItem()
}
