package com.ebookreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.db.AppDatabase
import com.ebookreader.data.import.BookImporter
import com.ebookreader.data.model.Book
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val importer = BookImporter(application)

    val books: StateFlow<List<Book>> = db.bookDao()
        .getAllBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            _importState.value = ImportState.Importing
            val result = importer.importEpub(uri)
            _importState.value = if (result.isSuccess) {
                ImportState.Success(result.getOrThrow())
            } else {
                ImportState.Error(result.exceptionOrNull()?.message ?: "Import failed")
            }
        }
    }

    fun deleteBook(book: Book) {
        importer.deleteBook(book)
    }

    fun clearImportState() {
        _importState.value = ImportState.Idle
    }
}

sealed class ImportState {
    object Idle : ImportState()
    object Importing : ImportState()
    data class Success(val book: Book) : ImportState()
    data class Error(val message: String) : ImportState()
}
