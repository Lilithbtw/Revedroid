package app.revedroid.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.revedroid.AppContainer
import app.revedroid.R
import app.revedroid.data.api.CloudFile
import app.revedroid.data.api.ListResponse
import app.revedroid.data.api.StoragePolicy
import app.revedroid.data.repo.CloudUris
import app.revedroid.ui.components.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FolderPickerState(
    val uri: String,
    val folders: List<CloudFile> = emptyList(),
    val policy: StoragePolicy? = null,
    val loading: Boolean = true,
    val error: String? = null,
) {
    val crumbs: List<Pair<String, String>> get() = CloudUris.crumbs(uri)
    val atRoot: Boolean get() = crumbs.size <= 1
}

/** Browses folders only, for choosing where files go. */
class FolderPickerViewModel(
    container: AppContainer,
    start: String,
) : ViewModel() {
    private val repo = container.fileRepository

    private val _state = MutableStateFlow(FolderPickerState(uri = start))
    val state: StateFlow<FolderPickerState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun open(uri: String) {
        _state.update { it.copy(uri = uri, folders = emptyList(), loading = true, error = null) }
        load()
    }

    /** Returns false at the top, so the caller can let Back through. */
    fun goUp(): Boolean {
        val s = _state.value
        if (s.atRoot) return false
        open(s.crumbs[s.crumbs.size - 2].second)
        return true
    }

    fun retry() = open(_state.value.uri)

    fun createFolder(name: String) {
        viewModelScope.launch {
            try {
                open(repo.createFolder(_state.value.uri, name.trim()).path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.userMessage())
            }
        }
    }

    private fun load() {
        loadJob?.cancel()
        val uri = _state.value.uri
        loadJob =
            viewModelScope.launch {
                try {
                    var page = repo.list(uri)
                    val policy = page.storagePolicy
                    val folders = page.files.filter { it.isFolder }.toMutableList()
                    // Listings put folders before files, so the first page with a file has the last folder.
                    while (page.files.all { it.isFolder }) {
                        val next = page.nextPage() ?: break
                        page = repo.list(uri, nextPageToken = next.first, page = next.second)
                        folders += page.files.filter { it.isFolder }
                    }
                    _state.update { it.copy(folders = folders, policy = policy, loading = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(loading = false, error = e.userMessage()) }
                }
            }
    }

    /** The next page's cursor, or its number for paged listings; null on the last page. */
    private fun ListResponse.nextPage(): Pair<String?, Int?>? {
        val p = pagination
        p.nextToken?.takeIf { it.isNotEmpty() }?.let { return it to null }
        val total = p.totalItems ?: return null
        return if ((p.page + 1) * p.pageSize < total) null to p.page + 1 else null
    }
}

/**
 * Full-screen folder chooser. [blocked] marks folders that can't be entered or
 * chosen (moving a folder into itself), [canChoose] decides whether the open
 * folder is a valid destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPicker(
    viewModel: FolderPickerViewModel,
    title: String,
    confirmLabel: String,
    onConfirm: (FolderPickerState) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    blocked: (String) -> Boolean = { false },
    canChoose: (String) -> Boolean = { true },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var naming by rememberSaveable { mutableStateOf(false) }
    val rootLabel = stringResource(R.string.nav_files)

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = !state.atRoot) { viewModel.goUp() }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                },
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (state.atRoot) rootLabel else state.crumbs.last().first,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { naming = true }, enabled = !state.loading && state.error == null) {
                        Icon(Icons.Rounded.CreateNewFolder, contentDescription = stringResource(R.string.files_new_folder))
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Button(
                    onClick = { onConfirm(state) },
                    enabled = !state.loading && state.error == null && canChoose(state.uri),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .height(52.dp),
                ) { Text(confirmLabel) }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Breadcrumbs(state.crumbs, inTrash = false, onNavigate = viewModel::open)
            when {
                state.loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }

                state.error != null -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(state.error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        FilledTonalButton(
                            onClick = { if (state.atRoot) viewModel.retry() else viewModel.open(CloudUris.MY_ROOT) },
                            modifier = Modifier.padding(top = 16.dp),
                        ) { Text(stringResource(if (state.atRoot) R.string.action_retry else R.string.picker_back_to_root)) }
                    }
                }

                state.folders.isEmpty() -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.FolderOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(56.dp),
                        )
                        Text(
                            stringResource(R.string.picker_no_folders),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }

                else -> {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(state.folders, key = { it.id }) { folder ->
                            val off = blocked(folder.path)
                            ListItem(
                                modifier =
                                    Modifier
                                        .alpha(if (off) 0.38f else 1f)
                                        .clickable(enabled = !off) { viewModel.open(folder.path) },
                                leadingContent = { FileThumbnail(folder, Modifier.size(40.dp), iconSize = 22.dp, cornerRadius = 12.dp) },
                                headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (naming) {
        NameDialog(
            title = stringResource(R.string.files_new_folder),
            initial = "",
            confirmLabel = stringResource(R.string.action_create),
            onConfirm = {
                naming = false
                viewModel.createFolder(it)
            },
            onDismiss = { naming = false },
        )
    }
}
