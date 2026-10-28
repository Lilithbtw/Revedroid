package app.revedroid.ui.files

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.revedroid.AppContainer
import app.revedroid.data.api.CloudFile
import app.revedroid.data.api.ListResponse
import app.revedroid.data.api.StoragePolicy
import app.revedroid.data.repo.CloudUris
import app.revedroid.data.repo.ShareExpiry
import app.revedroid.data.store.FileViewMode
import app.revedroid.data.upload.UploadsState
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

enum class SortField(
    val apiName: String,
) {
    Name("name"),
    Size("size"),
    Updated("updated_at"),
    Created("created_at"),
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
    val fetching: FetchTask? = null,
    /** Ids of the files picked for a batch action; empty when not selecting. */
    val selected: Set<String> = emptySet(),
) {
    val isTrash: Boolean get() = root == CloudUris.TRASH_ROOT
    val selecting: Boolean get() = selected.isNotEmpty()
    val selectedFiles: List<CloudFile> get() = files.filter { it.id in selected }
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

    val shareExpiry: StateFlow<ShareExpiry> =
        container.store.settings
            .map { it.shareExpiry }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ShareExpiry.Never)

    /** Uploads run app-wide, so every folder sees the same list. */
    val uploads: StateFlow<UploadsState> = container.uploads.state

    private val _events = Channel<FilesEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private var fetchJob: Job? = null
    private var nextToken: String? = null
    private var nextPage: Int? = null
    private var listedUri: String = root

    init {
        load()
        viewModelScope.launch {
            container.uploads.finished.collect { parent -> if (parent == _state.value.uri && !_state.value.isSearching) load() }
        }
    }

    fun open(folder: CloudFile) = navigate(folder.path)

    fun navigate(uri: String) {
        _state.update { it.copy(uri = uri, searchQuery = null, files = emptyList(), loading = true, selected = emptySet(), policy = null) }
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
        _state.update { it.copy(searchQuery = q, files = emptyList(), loading = true, selected = emptySet()) }
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
            val files = if (append) (it.files + response.files).distinctBy(CloudFile::id) else response.files
            it.copy(
                files = files,
                canLoadMore = nextToken != null || nextPage != null,
                policy = response.storagePolicy ?: it.policy,
                error = null,
                // Drop picks that vanished from the folder since they were made.
                selected = if (append) it.selected else it.selected.intersect(files.mapTo(HashSet(), CloudFile::id)),
            )
        }
    }

    fun toggleSelection(file: CloudFile) =
        _state.update { it.copy(selected = if (file.id in it.selected) it.selected - file.id else it.selected + file.id) }

    fun selectAll() = _state.update { it.copy(selected = it.files.mapTo(HashSet(), CloudFile::id)) }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

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

    fun delete(files: List<CloudFile>) =
        act {
            val permanent = _state.value.isTrash
            repo.delete(files, permanently = permanent)
            removeLocally(files)
            val what = describe(files)
            _events.send(FilesEvent.Message(if (permanent) "Deleted $what" else "Moved $what to trash"))
        }

    fun restore(files: List<CloudFile>) =
        act {
            repo.restore(files)
            removeLocally(files)
            _events.send(FilesEvent.Message("Restored ${describe(files)}"))
        }

    fun move(
        files: List<CloudFile>,
        destination: String,
    ) = act {
        repo.move(files, destination)
        removeLocally(files)
        val folder = CloudUris.crumbs(destination).let { if (it.size <= 1) "My files" else it.last().first }
        _events.send(FilesEvent.Message("Moved ${describe(files)} to $folder"))
    }

    fun emptyTrash() =
        act {
            repo.emptyTrash()
            _state.update { it.copy(files = emptyList()) }
            _events.send(FilesEvent.Message("Trash emptied"))
        }

    /** Folders are skipped: the download manager only fetches single files. */
    fun download(files: List<CloudFile>) =
        act {
            val wanted = files.filterNot { it.isFolder }
            if (wanted.isEmpty()) return@act
            wanted.forEach { container.downloader.enqueue(repo.downloadUrl(it), it.name) }
            clearSelection()
            _events.send(FilesEvent.Message("Downloading ${describe(wanted)}"))
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

    fun share(
        file: CloudFile,
        expiry: ShareExpiry,
        protected: Boolean,
    ) = act {
        _events.send(FilesEvent.ShareLink(container.shareRepository.create(file, expiry, protected), file.name))
    }

    /** Shares several items with one link, through a new folder in the current one. */
    fun shareMany(
        files: List<CloudFile>,
        folder: ShareFolder,
        expiry: ShareExpiry,
        protected: Boolean,
    ) = act {
        val (link, name) = container.shareRepository.createForMany(files, _state.value.uri, folder.name, folder.move, expiry, protected)
        clearSelection()
        load()
        _events.send(FilesEvent.ShareLink(link, name))
    }

    fun upload(sources: List<Uri>) {
        val s = _state.value
        container.uploads.enqueue(s.uri, sources, s.policy)
    }

    fun cancelUpload(id: Long) = container.uploads.cancel(id)

    fun dismissUpload(id: Long) = container.uploads.dismiss(id)

    private fun removeLocally(files: List<CloudFile>) {
        val ids = files.mapTo(HashSet(), CloudFile::id)
        _state.update { s -> s.copy(files = s.files.filterNot { it.id in ids }, selected = s.selected - ids) }
    }

    private fun describe(files: List<CloudFile>): String = files.singleOrNull()?.name ?: "${files.size} items"

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
