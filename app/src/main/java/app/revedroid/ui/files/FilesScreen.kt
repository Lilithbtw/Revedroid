package app.revedroid.ui.files

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.revedroid.R
import app.revedroid.data.api.CloudFile
import app.revedroid.data.store.FileViewMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

private sealed interface FilesDialog {
    data object NewFolder : FilesDialog

    data class Rename(
        val file: CloudFile,
    ) : FilesDialog

    data class Delete(
        val file: CloudFile,
        val forever: Boolean,
    ) : FilesDialog

    data object EmptyTrash : FilesDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    viewModel: FilesViewModel,
    refreshSignal: Long,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    var sheetFile by remember { mutableStateOf<CloudFile?>(null) }
    var showNewSheet by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<FilesDialog?>(null) }

    val pickFiles =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) viewModel.upload(uris)
        }

    LaunchedEffect(refreshSignal) { if (refreshSignal > 0) viewModel.refresh() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is FilesEvent.Message -> snackbar.showSnackbar(event.text)
                is FilesEvent.ShareLink -> {
                    val send =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, event.name)
                            putExtra(Intent.EXTRA_TEXT, event.url)
                        }
                    context.startActivity(Intent.createChooser(send, event.name))
                }
            }
        }
    }
    // Scroll back to the top when entering a different folder.
    LaunchedEffect(state.uri, state.searchQuery) {
        listState.scrollToItem(0)
        gridState.scrollToItem(0)
    }

    BackHandler(enabled = state.isSearching || !state.atRoot) { viewModel.goUp() }

    val fabExpanded by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemIndex == 0 }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.isSearching) {
                SearchTopBar(
                    initial = state.searchQuery.orEmpty(),
                    onSearch = viewModel::search,
                    onClose = viewModel::closeSearch,
                )
            } else {
                BrowseTopBar(state, viewMode, viewModel, onEmptyTrash = { dialog = FilesDialog.EmptyTrash }, scrollBehavior)
            }
        },
        floatingActionButton = {
            if (!state.isTrash && !state.isSearching) {
                ExtendedFloatingActionButton(
                    onClick = { showNewSheet = true },
                    expanded = fabExpanded,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.files_new)) },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!state.atRoot && !state.isSearching) Breadcrumbs(state.crumbs, state.isTrash, onNavigate = viewModel::navigate)
            UploadsPanel(state.uploads, onDismiss = viewModel::dismissUpload)

            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.loading ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    state.error != null && state.files.isEmpty() ->
                        MessageState(
                            icon = Icons.Rounded.CloudOff,
                            text = state.error!!,
                            action = stringResource(R.string.action_retry),
                            onAction = viewModel::refresh,
                        )
                    state.files.isEmpty() ->
                        MessageState(
                            icon =
                                when {
                                    state.isSearching -> Icons.Rounded.SearchOff
                                    state.isTrash -> Icons.Rounded.Delete
                                    else -> Icons.Rounded.FolderOpen
                                },
                            text =
                                stringResource(
                                    when {
                                        state.isSearching -> R.string.files_empty_search
                                        state.isTrash -> R.string.files_empty_trash
                                        else -> R.string.files_empty_folder
                                    },
                                ),
                        )
                    viewMode == FileViewMode.Grid ->
                        FileGrid(
                            state = state,
                            gridState = gridState,
                            onClick = { if (it.isFolder && !state.isTrash) viewModel.open(it) else sheetFile = it },
                            onLongClick = { sheetFile = it },
                            onLoadMore = viewModel::loadMore,
                        )
                    else ->
                        FileList(
                            state = state,
                            listState = listState,
                            onClick = { if (it.isFolder && !state.isTrash) viewModel.open(it) else sheetFile = it },
                            onMore = { sheetFile = it },
                            onLoadMore = viewModel::loadMore,
                        )
                }
            }
        }
    }

    sheetFile?.let { file ->
        FileActionsSheet(
            file = file,
            inTrash = state.isTrash,
            onDismiss = { sheetFile = null },
            onAction = { action ->
                sheetFile = null
                when (action) {
                    FileAction.Open -> viewModel.open(file)
                    FileAction.Download -> viewModel.download(file)
                    FileAction.Share -> viewModel.share(file)
                    FileAction.Rename -> dialog = FilesDialog.Rename(file)
                    FileAction.Delete -> dialog = FilesDialog.Delete(file, forever = false)
                    FileAction.DeleteForever -> dialog = FilesDialog.Delete(file, forever = true)
                    FileAction.Restore -> viewModel.restore(file)
                }
            },
        )
    }

    if (showNewSheet) {
        NewItemSheet(
            onUpload = {
                showNewSheet = false
                pickFiles.launch(arrayOf("*/*"))
            },
            onNewFolder = {
                showNewSheet = false
                dialog = FilesDialog.NewFolder
            },
            onDismiss = { showNewSheet = false },
        )
    }

    when (val d = dialog) {
        null -> Unit
        FilesDialog.NewFolder ->
            NameDialog(
                title = stringResource(R.string.files_new_folder),
                initial = "",
                confirmLabel = stringResource(R.string.action_create),
                onConfirm = {
                    dialog = null
                    viewModel.createFolder(it)
                },
                onDismiss = { dialog = null },
            )
        is FilesDialog.Rename ->
            NameDialog(
                title = stringResource(R.string.files_rename),
                initial = d.file.name,
                confirmLabel = stringResource(R.string.files_rename),
                onConfirm = {
                    dialog = null
                    viewModel.rename(d.file, it)
                },
                onDismiss = { dialog = null },
            )
        is FilesDialog.Delete ->
            ConfirmDialog(
                title = stringResource(if (d.forever) R.string.files_delete_forever_title else R.string.files_delete_title),
                text =
                    stringResource(
                        if (d.forever) R.string.files_delete_forever_body else R.string.files_delete_body,
                        d.file.name,
                    ),
                confirmLabel = stringResource(if (d.forever) R.string.files_delete_forever else R.string.files_delete),
                onConfirm = {
                    dialog = null
                    viewModel.delete(d.file)
                },
                onDismiss = { dialog = null },
            )
        FilesDialog.EmptyTrash ->
            ConfirmDialog(
                title = stringResource(R.string.files_empty_trash_title),
                text = stringResource(R.string.files_empty_trash_body),
                confirmLabel = stringResource(R.string.files_empty_trash_action),
                onConfirm = {
                    dialog = null
                    viewModel.emptyTrash()
                },
                onDismiss = { dialog = null },
            )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseTopBar(
    state: FilesUiState,
    viewMode: FileViewMode,
    viewModel: FilesViewModel,
    onEmptyTrash: () -> Unit,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
) {
    var sortOpen by remember { mutableStateOf(false) }
    val rootTitle = stringResource(if (state.isTrash) R.string.nav_trash else R.string.nav_files)
    TopAppBar(
        scrollBehavior = scrollBehavior,
        title = {
            Text(
                if (state.atRoot) rootTitle else state.crumbs.last().first,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            if (!state.atRoot) {
                IconButton(onClick = { viewModel.goUp() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = {
            if (!state.isTrash) {
                IconButton(onClick = viewModel::startSearch) {
                    Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.files_search))
                }
            }
            IconButton(onClick = viewModel::toggleViewMode) {
                Icon(
                    if (viewMode == FileViewMode.List) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.ViewList,
                    contentDescription = stringResource(R.string.files_toggle_view),
                )
            }
            Box {
                IconButton(onClick = { sortOpen = true }) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = stringResource(R.string.files_sort))
                }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    SortField.entries.forEach { field ->
                        DropdownMenuItem(
                            text = { Text(stringResource(field.label())) },
                            onClick = {
                                sortOpen = false
                                viewModel.setSort(field)
                            },
                            trailingIcon = {
                                if (state.sortField == field) {
                                    Icon(
                                        if (state.ascending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                                        contentDescription = null,
                                    )
                                }
                            },
                        )
                    }
                }
            }
            if (state.isTrash) {
                IconButton(onClick = onEmptyTrash, enabled = state.files.isNotEmpty()) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.files_empty_trash_action))
                }
            }
        },
    )
}

private fun SortField.label(): Int =
    when (this) {
        SortField.Name -> R.string.sort_name
        SortField.Size -> R.string.sort_size
        SortField.Updated -> R.string.sort_updated
        SortField.Created -> R.string.sort_created
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    initial: String,
    onSearch: (String) -> Unit,
    onClose: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (initial.isEmpty()) focus.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        title = {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(text) }),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text(
                            stringResource(R.string.files_search_hint),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                },
            )
        },
        actions = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { text = "" }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear))
                }
            }
        },
    )
}

@Composable
private fun Breadcrumbs(
    crumbs: List<Pair<String, String>>,
    inTrash: Boolean,
    onNavigate: (String) -> Unit,
) {
    val rowState = rememberLazyListState()
    LaunchedEffect(crumbs.size) { rowState.animateScrollToItem(crumbs.lastIndex) }
    val rootLabel = stringResource(if (inTrash) R.string.nav_trash else R.string.nav_files)
    LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(40.dp),
    ) {
        itemsIndexed(crumbs) { index, (label, uri) ->
            if (index > 0) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            val last = index == crumbs.lastIndex
            TextButton(onClick = { onNavigate(uri) }, enabled = !last, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(
                    if (index == 0) rootLabel else label,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun UploadsPanel(
    uploads: List<UploadTask>,
    onDismiss: (Long) -> Unit,
) {
    AnimatedVisibility(uploads.isNotEmpty()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                uploads.forEach { task ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                task.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (task.error != null) {
                                IconButton(onClick = { onDismiss(task.id) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_dismiss))
                                }
                            } else {
                                Text("${(task.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (task.error != null) {
                            Text(task.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        } else {
                            Spacer(Modifier.height(4.dp))
                            if (task.total > 0) {
                                LinearProgressIndicator(progress = { task.fraction }, modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(
    state: FilesUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onClick: (CloudFile) -> Unit,
    onMore: (CloudFile) -> Unit,
    onLoadMore: () -> Unit,
) {
    val context = LocalContext.current
    LoadMoreEffect(
        canLoadMore = state.canLoadMore,
        position = {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index to listState.layoutInfo.totalItemsCount
        },
        onLoadMore = onLoadMore,
    )
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
        items(state.files, key = { it.id }) { file ->
            ListItem(
                modifier =
                    Modifier
                        .animateItem()
                        .combinedClickable(onClick = { onClick(file) }, onLongClick = { onMore(file) }),
                leadingContent = { FileThumbnail(file, Modifier.size(44.dp), iconSize = 24.dp, cornerRadius = 12.dp) },
                headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(fileSubtitle(context, file), maxLines = 1) },
                trailingContent = {
                    IconButton(onClick = { onMore(file) }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.files_more))
                    }
                },
            )
        }
        if (state.loadingMore) item { LoadingMoreRow() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGrid(
    state: FilesUiState,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onClick: (CloudFile) -> Unit,
    onLongClick: (CloudFile) -> Unit,
    onLoadMore: () -> Unit,
) {
    val context = LocalContext.current
    LoadMoreEffect(
        canLoadMore = state.canLoadMore,
        position = {
            gridState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index to gridState.layoutInfo.totalItemsCount
        },
        onLoadMore = onLoadMore,
    )
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 152.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.files, key = { it.id }) { file ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier =
                    Modifier
                        .animateItem()
                        .combinedClickable(onClick = { onClick(file) }, onLongClick = { onLongClick(file) }),
            ) {
                FileThumbnail(
                    file,
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.25f)
                        .padding(6.dp),
                    iconSize = 48.dp,
                    cornerRadius = 10.dp,
                )
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        fileSubtitle(context, file),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        if (state.loadingMore) item(span = { GridItemSpan(maxLineSpan) }) { LoadingMoreRow() }
    }
}

@Composable
private fun LoadMoreEffect(
    canLoadMore: Boolean,
    position: () -> Pair<Int?, Int>,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(canLoadMore) {
        if (!canLoadMore) return@LaunchedEffect
        snapshotFlow(position)
            .filter { (last, total) -> last != null && total > 0 && last >= total - LOAD_MORE_THRESHOLD }
            .distinctUntilChanged()
            .collect { onLoadMore() }
    }
}

private const val LOAD_MORE_THRESHOLD = 12

@Composable
private fun LoadingMoreRow() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator(Modifier.size(28.dp)) }
}

@Composable
private fun MessageState(
    icon: ImageVector,
    text: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    // Scrollable so pull-to-refresh still works on empty folders.
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(96.dp))
        Icon(icon, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}
