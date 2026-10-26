package app.revedroid.ui.files

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.revedroid.AppContainer
import app.revedroid.data.api.CloudFile
import app.revedroid.data.api.ListResponse
import app.revedroid.data.api.StoragePolicy
import app.revedroid.data.repo.CloudUris
import app.revedroid.data.store.FileViewMode
import app.revedroid.ui.components.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

enum class SortField(
    val apiName: String,
) {
    Name("name"),
    Size("size"),
    Updated("updated_at"),
    Created("created_at"),
}

data class UploadTask(
    val id: Long,
    val name: String,
    val sent: Long = 0,
    val total: Long = 0,
    val error: String? = null,
) {
    val fraction: Float get() = if (total <= 0) 0f else (sent.toFloat() / total).coerceIn(0f, 1f)
}

data class FilesUiState(
    val root: String,
    val uri: String = root,
    val files: List<CloudFile> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val error: String? = null,
    val sortField: SortField = SortField.Name,
    val ascending: Boolean = true,
    val searchQuery: String? = null,
    val policy: StoragePolicy? = null,
    val uploads: List<UploadTask> = emptyList(),
    val fetching: FetchTask? = null,
) {
    val isTrash: Boolean get() = root == CloudUris.TRASH_ROOT
    val isSearching: Boolean get() = searchQuery != null
    val crumbs: List<Pair<String, String>> get() = CloudUris.crumbs(uri)
    val atRoot: Boolean get() = crumbs.size <= 1
}

sealed interface FilesEvent {
    data class Message(
        val text: String,
    ) : FilesEvent

    data class ShareLink(
        val url: String,
        val name: String,
    ) : FilesEvent

    data class OpenWith(
        val uri: Uri,
        val name: String,
    ) : FilesEvent
}

class FilesViewModel(
    private val container: AppContainer,
    root: String,
) : ViewModel() {
    private val repo = container.fileRepository

    private val _state = MutableStateFlow(FilesUiState(root = root))
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    val viewMode: StateFlow<FileViewMode> =
        container.store.settings
            .map { it.viewMode }
            .stateIn(viewModelScope, SharingStarted.Eagerly, FileViewMode.List)

    private val _events = Channel<FilesEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private var fetchJob: Job? = null
    private var nextToken: String? = null
    private var nextPage: Int? = null
    private var listedUri: String = root

    private val uploadQueue = Channel<Pair<String, Uri>>(Channel.UNLIMITED)
    private val uploadIds = AtomicLong()

    init {
        load()
        viewModelScope.launch { for ((parent, source) in uploadQueue) runUpload(parent, source) }
    }

    fun open(folder: CloudFile) = navigate(folder.path)

    fun navigate(uri: String) {
        _state.update { it.copy(uri = uri, searchQuery = null, files = emptyList(), loading = true) }
        load()
    }

    /** Returns false when already at the top so the caller can let Back through. */
    fun goUp(): Boolean {
        val s = _state.value
        if (s.isSearching) {
            closeSearch()
            return true
        }
        if (s.atRoot) return false
        navigate(s.crumbs[s.crumbs.size - 2].second)
        return true
    }

    fun refresh() = load(pullToRefresh = true)

    fun setSort(field: SortField) {
        _state.update {
            if (it.sortField == field) it.copy(ascending = !it.ascending) else it.copy(sortField = field, ascending = true)
        }
        load()
    }

    fun toggleViewMode() {
        val next = if (viewMode.value == FileViewMode.List) FileViewMode.Grid else FileViewMode.List
        viewModelScope.launch { container.store.setViewMode(next) }
    }

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return closeSearch()
        _state.update { it.copy(searchQuery = q, files = emptyList(), loading = true) }
        load()
    }

    fun startSearch() = _state.update { it.copy(searchQuery = "") }

    fun closeSearch() {
        val wasActive = !_state.value.searchQuery.isNullOrEmpty()
        _state.update { it.copy(searchQuery = null) }
        if (wasActive) load()
    }

    private fun currentListUri(s: FilesUiState): String =
        if (!s.searchQuery.isNullOrEmpty()) CloudUris.search(s.uri, s.searchQuery) else s.uri

    private fun load(pullToRefresh: Boolean = false) {
        loadJob?.cancel()
        val s = _state.value
        val uri = currentListUri(s)
        _state.update { it.copy(refreshing = pullToRefresh, loading = !pullToRefresh && it.files.isEmpty(), error = null) }
        loadJob =
            viewModelScope.launch {
                try {
                    val response = repo.list(uri, orderBy = s.sortField.apiName, orderDirection = if (s.ascending) "asc" else "desc")
                    listedUri = uri
                    applyPage(response, append = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.userMessage()) }
                } finally {
                    _state.update { it.copy(loading = false, refreshing = false) }
                }
            }
    }

    fun loadMore() {
        val s = _state.value
        if (!s.canLoadMore || s.loadingMore || s.loading) return
        _state.update { it.copy(loadingMore = true) }
        loadJob =
            viewModelScope.launch {
                try {
                    val response =
                        repo.list(
                            listedUri,
                            nextPageToken = nextToken,
                            page = if (nextToken == null) nextPage else null,
                            orderBy = s.sortField.apiName,
                            orderDirection = if (s.ascending) "asc" else "desc",
                        )
                    applyPage(response, append = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _events.send(FilesEvent.Message(e.userMessage()))
                } finally {
                    _state.update { it.copy(loadingMore = false) }
                }
            }
    }

    private fun applyPage(
        response: ListResponse,
        append: Boolean,
    ) {
        val p = response.pagination
        nextToken = p.nextToken?.takeIf { it.isNotEmpty() }
        val total = p.totalItems
        nextPage = if (nextToken == null && total != null && (p.page + 1) * p.pageSize < total) p.page + 1 else null
        _state.update {
            it.copy(
                files = if (append) (it.files + response.files).distinctBy(CloudFile::id) else response.files,
                canLoadMore = nextToken != null || nextPage != null,
                policy = response.storagePolicy ?: it.policy,
                error = null,
            )
        }
    }

    fun createFolder(name: String) =
        act {
            repo.createFolder(_state.value.uri, name.trim())
            load()
        }

    fun rename(
        file: CloudFile,
        newName: String,
    ) = act {
        repo.rename(file, newName.trim())
        load()
    }

    fun delete(file: CloudFile) =
        act {
            val permanent = _state.value.isTrash
            repo.delete(listOf(file), permanently = permanent)
            removeLocally(file)
            _events.send(FilesEvent.Message(if (permanent) "Deleted ${file.name}" else "Moved ${file.name} to trash"))
        }

    fun restore(file: CloudFile) =
        act {
            repo.restore(listOf(file))
            removeLocally(file)
            _events.send(FilesEvent.Message("Restored ${file.name}"))
        }

    fun emptyTrash() =
        act {
            repo.emptyTrash()
            _state.update { it.copy(files = emptyList()) }
            _events.send(FilesEvent.Message("Trash emptied"))
        }

    fun download(file: CloudFile) =
        act {
            val url = repo.downloadUrl(file)
            container.downloader.enqueue(url, file.name)
            _events.send(FilesEvent.Message("Downloading ${file.name}"))
        }

    /** Copies [file] to the device, then asks the screen to show the app picker. */
    fun openWith(file: CloudFile) {
        if (fetchJob?.isActive == true) return
        fetchJob =
            viewModelScope.launch {
                _state.update { it.copy(fetching = FetchTask(file.name)) }
                try {
                    val local =
                        container.fileCache.fetch(file) { p ->
                            _state.update { it.copy(fetching = it.fetching?.copy(sent = p.sent, total = p.total)) }
                        }
                    _events.send(FilesEvent.OpenWith(container.fileCache.uriFor(local), file.name))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _events.send(FilesEvent.Message(e.userMessage()))
                } finally {
                    _state.update { it.copy(fetching = null) }
                }
            }
    }

    fun cancelFetch() {
        fetchJob?.cancel()
    }

    fun share(file: CloudFile) =
        act {
            _events.send(FilesEvent.ShareLink(repo.createShareLink(file), file.name))
        }

    fun upload(sources: List<Uri>) {
        val parent = _state.value.uri
        sources.forEach { uploadQueue.trySend(parent to it) }
    }

    fun dismissUpload(id: Long) = _state.update { s -> s.copy(uploads = s.uploads.filterNot { it.id == id }) }

    private suspend fun runUpload(
        parent: String,
        source: Uri,
    ) {
        val id = uploadIds.incrementAndGet()
        val name = repo.displayName(source)
        _state.update { it.copy(uploads = it.uploads + UploadTask(id, name)) }

        fun patch(f: (UploadTask) -> UploadTask) = _state.update { s -> s.copy(uploads = s.uploads.map { if (it.id == id) f(it) else it }) }
        try {
            val policy =
                _state.value.policy?.takeIf { parent == _state.value.uri }
                    ?: repo.list(parent).storagePolicy
                    ?: error("This folder doesn't accept uploads")
            repo.upload(parent, source, policy) { p -> patch { it.copy(sent = p.sent, total = p.total) } }
            _state.update { s -> s.copy(uploads = s.uploads.filterNot { it.id == id }) }
            if (_state.value.uri == parent) load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            patch { it.copy(error = e.userMessage()) }
        }
    }

    private fun removeLocally(file: CloudFile) = _state.update { s -> s.copy(files = s.files.filterNot { it.id == file.id }) }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(FilesEvent.Message(e.userMessage()))
            }
        }
    }
}
