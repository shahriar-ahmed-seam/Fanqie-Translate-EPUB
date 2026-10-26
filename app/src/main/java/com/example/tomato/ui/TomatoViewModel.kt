package com.example.tomato.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.TranslatorApplication
import com.example.tomato.EngineSetup
import com.example.tomato.LookupResult
import com.example.tomato.TomatoBook
import com.example.tomato.TomatoRepository
import com.example.tomato.data.TomatoBookEntity
import com.example.tomato.engine.EngineCallException
import com.example.tomato.engine.EngineUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface LookupUi {
    data object Idle : LookupUi
    data object Loading : LookupUi
    data class Results(val books: List<TomatoBook>, val byId: Boolean) : LookupUi
    data class Message(val text: String, val isError: Boolean) : LookupUi
}

data class EngineUpdateUi(val running: Boolean, val progress: Float?, val message: String?)

@OptIn(ExperimentalCoroutinesApi::class)
class TomatoViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: TomatoRepository = (application as TranslatorApplication).tomatoGraph.repository

    val engineSetup: StateFlow<EngineSetup> = repository.engineSetup
    val books: StateFlow<List<TomatoBookEntity>> =
        repository.books.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _lookup = MutableStateFlow<LookupUi>(LookupUi.Idle)
    val lookup: StateFlow<LookupUi> = _lookup.asStateFlow()

    /** Book shown on the detail page (from a search, the later list or downloads). */
    private val _detail = MutableStateFlow<TomatoBook?>(null)
    val detail: StateFlow<TomatoBook?> = _detail.asStateFlow()

    val detailEntity: StateFlow<TomatoBookEntity?> = _detail
        .flatMapLatest { book -> if (book == null) flowOf(null) else repository.observe(book.bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    /** Books with an action in progress (buttons show a spinner and don't repeat it). */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _engineUpdate = MutableStateFlow<EngineUpdateUi?>(null)
    val engineUpdate: StateFlow<EngineUpdateUi?> = _engineUpdate.asStateFlow()

    init {
        repository.refreshEngineSetup()
        viewModelScope.launch { repository.verifyLibraryLinks() }
    }

    fun setQuery(text: String) {
        _query.value = text
    }

    fun clearMessage() {
        _message.value = null
    }

    fun search() {
        val text = _query.value.trim()
        if (text.isEmpty() || _lookup.value == LookupUi.Loading) return
        _lookup.value = LookupUi.Loading
        viewModelScope.launch {
            _lookup.value = try {
                when (val result = repository.lookup(text)) {
                    is LookupResult.Found -> LookupUi.Results(result.books, result.byId)
                    is LookupResult.NotFound -> LookupUi.Message(result.message, isError = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LookupUi.Message(readable(e), isError = true)
            }
        }
    }

    fun clearResults() {
        _lookup.value = LookupUi.Idle
    }

    fun openDetail(book: TomatoBook) {
        _detail.value = book
    }

    fun openDetail(entity: TomatoBookEntity) {
        _detail.value = entity.toBook()
        // Saved books may have new chapters since; refresh quietly.
        if (engineSetup.value is EngineSetup.Ready) {
            viewModelScope.launch {
                repository.refreshBookInfo(entity.bookId)
                repository.observeOnce(entity.bookId)?.let { refreshed ->
                    if (_detail.value?.bookId == refreshed.bookId) _detail.value = refreshed.toBook()
                }
            }
        }
    }

    fun closeDetail() {
        _detail.value = null
    }

    fun downloadNow(book: TomatoBook) = act(book.bookId) {
        repository.download(book).onFailure { _message.value = "Download didn't start: ${readable(it)}" }
    }

    fun saveForLater(book: TomatoBook) = act(book.bookId) {
        repository.saveForLater(book)
        _message.value = "Saved to Download later"
    }

    fun removeFromLater(bookId: String) = act(bookId) { repository.removeFromLater(bookId) }

    fun cancel(bookId: String) = act(bookId) { repository.cancel(bookId) }

    fun redownload(bookId: String) = act(bookId) {
        repository.redownload(bookId).onFailure { _message.value = "Download didn't start: ${readable(it)}" }
    }

    fun delete(bookId: String) = act(bookId) {
        repository.delete(bookId)
        if (_detail.value?.bookId == bookId) _detail.value = null
        _message.value = "Deleted the download"
    }

    fun translate(bookId: String) = act(bookId) {
        repository.translate(bookId)
            .onSuccess { _message.value = "Added to the translation queue. It appears in your library." }
            .onFailure { _message.value = "Couldn't queue the translation: ${readable(it)}" }
    }

    fun addToBookshelf(bookId: String) = act(bookId) {
        repository.addToBookshelf(bookId)
            .onSuccess { _message.value = "Added to your library (Local Books)" }
            .onFailure { _message.value = "Couldn't add it to the library: ${readable(it)}" }
    }

    fun export(bookId: String, destination: Uri) = act(bookId) {
        repository.exportTo(bookId, destination)
            .onSuccess { _message.value = "EPUB saved" }
            .onFailure { _message.value = "Couldn't save the EPUB: ${readable(it)}" }
    }

    fun installEngine() = repository.installEngine()

    fun updateEngine() {
        if (_engineUpdate.value?.running == true) return
        _engineUpdate.value = EngineUpdateUi(running = true, progress = null, message = "Checking for a newer engine…")
        viewModelScope.launch {
            val message = try {
                repository.updateEngine { progress ->
                    _engineUpdate.value = EngineUpdateUi(running = true, progress = progress, message = "Downloading the new engine…")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Engine update failed: ${readable(e)}"
            }
            _engineUpdate.value = EngineUpdateUi(running = false, progress = null, message = message)
        }
    }

    fun dismissEngineUpdate() {
        if (_engineUpdate.value?.running != true) _engineUpdate.value = null
    }

    fun removeEngine() {
        viewModelScope.launch {
            repository.removeEngine()
            _message.value = "Engine removed. Your downloaded books are kept."
        }
    }

    fun coverFile(bookId: String): File = repository.coverFile(bookId)

    private fun act(bookId: String, block: suspend () -> Unit) {
        if (bookId in _busy.value) return
        _busy.update { it + bookId }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = readable(e)
            } finally {
                _busy.update { it - bookId }
            }
        }
    }

    private fun readable(e: Throwable): String = when (e) {
        is EngineUnavailableException, is EngineCallException -> e.message ?: "The Tomato engine failed"
        else -> e.message ?: "Something went wrong"
    }
}

fun TomatoBookEntity.toBook() = TomatoBook(
    bookId = bookId,
    title = title,
    author = author,
    abstractText = abstractText,
    coverUrl = coverUrl,
    category = category,
    serialStatus = serialStatus,
    wordCount = wordCount,
    chapterCount = chapterCount,
    readCount = 0,
    lastChapterTime = lastChapterTime
)
