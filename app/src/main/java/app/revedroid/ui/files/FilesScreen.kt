package app.revedroid.ui.files

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.revedroid.R
import app.revedroid.container
import app.revedroid.data.api.CloudFile
import app.revedroid.data.store.FileViewMode
import app.revedroid.data.upload.UploadTask
import app.revedroid.ui.components.rememberNotificationPermission
import app.revedroid.ui.theme.DialogSystemBars
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

private sealed interface FilesDialog {
    data object NewFolder : FilesDialog

    data class Rename(
        val file: CloudFile,
    ) : FilesDialog

    data class Delete(
        val files: List<CloudFile>,
        val forever: Boolean,
    ) : FilesDialog

    data class Move(
        val files: List<CloudFile>,
    ) : FilesDialog

    data object EmptyTrash : FilesDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    viewModel: FilesViewModel,
    refreshSignal: Long,
    onPreview: (CloudFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val uploads by viewModel.uploads.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val withNotifications = rememberNotificationPermission()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    var sheetFile by remember { mutableStateOf<CloudFile?>(null) }
    var showNewSheet by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<FilesDialog?>(null) }

    val pickFiles =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) withNotifications { viewModel.upload(uris) }
        }

    LaunchedEffect(refreshSignal) { if (refreshSignal > 0) viewModel.refresh() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is FilesEvent.Message -> {
                    snackbar.showSnackbar(event.text)
                }

                is FilesEvent.ShareLink -> {
                    val send =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, event.name)
                            putExtra(Intent.EXTRA_TEXT, event.url)
                        }
                    context.startActivity(Intent.createChooser(send, event.name))
                }

                is FilesEvent.OpenWith -> {
                    context.openWith(event.uri, event.name)
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
    // Declared last so it wins: Back first leaves selection mode.
    BackHandler(enabled = state.selecting) { viewModel.clearSelection() }

    // Folders open in place and supported files in the viewer; anything else goes to another app.
    // While selecting, a tap adds or removes the file instead.
    val onFileClick: (CloudFile) -> Unit = {
        when {
            state.selecting -> viewModel.toggleSelection(it)
            state.isTrash -> sheetFile = it
            it.isFolder -> viewModel.open(it)
            previewKindOf(it) != null -> onPreview(it)
            else -> viewModel.openWith(it)
        }
    }

    val fabExpanded by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemIndex == 0 }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.selecting) {
                SelectionTopBar(
                    count = state.selected.size,
                    allSelected = state.selected.size == state.files.size,
                    onClose = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                )
            } else if (state.isSearching) {
                SearchTopBar(
                    initial = state.searchQuery.orEmpty(),
                    onSearch = viewModel::search,
                    onClose = viewModel::closeSearch,
                )
            } else {
                BrowseTopBar(state, viewMode, viewModel, onEmptyTrash = { dialog = FilesDialog.EmptyTrash }, scrollBehavior)
            }
        },
        floatingActionButtonPosition = if (state.selecting) FabPosition.Center else FabPosition.End,
        floatingActionButton = {
            AnimatedContent(
                targetState = state.selecting,
                transitionSpec = {
                    (fadeIn() + slideInVertically { it / 2 }) togetherWith (fadeOut() + slideOutVertically { it / 2 })
                },
                label = "fab-island",
            ) { selecting ->
                if (selecting) {
                    val picked = state.selectedFiles
                    SelectionIsland(
                        if (state.isTrash) {
                            listOf(
                                IslandAction(Icons.Rounded.RestoreFromTrash, stringResource(R.string.files_restore)) {
                                    viewModel.restore(picked)
                                },
                                IslandAction(Icons.Rounded.DeleteForever, stringResource(R.string.files_delete_short)) {
                                    dialog = FilesDialog.Delete(picked, forever = true)
                                },
                            )
                        } else {
                            listOf(
                                IslandAction(Icons.AutoMirrored.Rounded.DriveFileMove, stringResource(R.string.files_move)) {
                                    dialog = FilesDialog.Move(picked)
                                },
                                IslandAction(
                                    Icons.Rounded.Download,
                                    stringResource(R.string.files_download),
                                    enabled = picked.any { !it.isFolder },
                                ) { viewModel.download(picked) },
                                IslandAction(Icons.Rounded.DeleteOutline, stringResource(R.string.files_delete_short)) {
                                    dialog = FilesDialog.Delete(picked, forever = false)
                                },
                            )
                        },
                    )
                } else if (!state.isTrash && !state.isSearching) {
                    ExtendedFloatingActionButton(
                        onClick = { showNewSheet = true },
                        expanded = fabExpanded,
                        icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.files_new)) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!state.atRoot && !state.isSearching) Breadcrumbs(state.crumbs, state.isTrash, onNavigate = viewModel::navigate)
            if (!state.isTrash) UploadsPanel(uploads.tasks, onCancel = viewModel::cancelUpload, onDismiss = viewModel::dismissUpload)

            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    state.error != null && state.files.isEmpty() -> {
                        MessageState(
                            icon = Icons.Rounded.CloudOff,
                            text = state.error!!,
                            action = stringResource(R.string.action_retry),
                            onAction = viewModel::refresh,
                        )
                    }

                    state.files.isEmpty() -> {
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
                    }

                    viewMode == FileViewMode.Grid -> {
                        FileGrid(
                            state = state,
                            gridState = gridState,
                            onClick = onFileClick,
                            onLongClick = viewModel::toggleSelection,
                            onMore = { sheetFile = it },
                            onLoadMore = viewModel::loadMore,
                        )
                    }

                    else -> {
                        FileList(
                            state = state,
                            listState = listState,
                            onClick = onFileClick,
                            onLongClick = viewModel::toggleSelection,
                            onMore = { sheetFile = it },
                            onLoadMore = viewModel::loadMore,
                        )
                    }
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
                    FileAction.Preview -> onPreview(file)
                    FileAction.OpenWith -> viewModel.openWith(file)
                    FileAction.Download -> viewModel.download(listOf(file))
                    FileAction.Share -> viewModel.share(file)
                    FileAction.Move -> dialog = FilesDialog.Move(listOf(file))
                    FileAction.Select -> viewModel.toggleSelection(file)
                    FileAction.Rename -> dialog = FilesDialog.Rename(file)
                    FileAction.Delete -> dialog = FilesDialog.Delete(listOf(file), forever = false)
                    FileAction.DeleteForever -> dialog = FilesDialog.Delete(listOf(file), forever = true)
                    FileAction.Restore -> viewModel.restore(listOf(file))
                }
            },
        )
    }

    state.fetching?.let { FetchDialog(it, onCancel = viewModel::cancelFetch) }

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
        null -> {}

        FilesDialog.NewFolder -> {
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
        }

        is FilesDialog.Rename -> {
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
        }

        is FilesDialog.Delete -> {
            val single = d.files.singleOrNull()
            ConfirmDialog(
                title = stringResource(if (d.forever) R.string.files_delete_forever_title else R.string.files_delete_title),
                text =
                    if (single != null) {
                        stringResource(if (d.forever) R.string.files_delete_forever_body else R.string.files_delete_body, single.name)
                    } else {
                        pluralStringResource(
                            if (d.forever) R.plurals.files_delete_forever_many else R.plurals.files_delete_many,
                            d.files.size,
                            d.files.size,
                        )
                    },
                confirmLabel = stringResource(if (d.forever) R.string.files_delete_forever else R.string.files_delete),
                onConfirm = {
                    dialog = null
                    viewModel.delete(d.files)
                },
                onDismiss = { dialog = null },
            )
        }

        is FilesDialog.Move -> {
            val moving = remember(d) { d.files.filter { it.isFolder }.map { it.path } }
            val sourceFolder = state.uri
            val picker = viewModel<FolderPickerViewModel>(key = "move-picker") { FolderPickerViewModel(context.container, sourceFolder) }
            LaunchedEffect(d) { picker.open(sourceFolder) }
            Dialog(
                onDismissRequest = { dialog = null },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                DialogSystemBars()
                FolderPicker(
                    viewModel = picker,
                    title = pluralStringResource(R.plurals.files_move_title, d.files.size, d.files.size),
                    confirmLabel = stringResource(R.string.files_move_here),
                    onConfirm = { target ->
                        dialog = null
                        viewModel.move(d.files, target.uri)
                    },
                    onCancel = { dialog = null },
                    // A folder can't go inside itself, and moving to where the files already are does nothing.
                    blocked = { path -> moving.any { path == it || path.startsWith("$it/") } },
                    canChoose = { uri ->
                        uri.trimEnd('/') != sourceFolder.trimEnd('/') &&
                            moving.none { uri == it || uri.startsWith("$it/") }
                    },
                )
            }
        }

        FilesDialog.EmptyTrash -> {
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
internal fun Breadcrumbs(
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

/** In-app view of the upload queue; the same uploads also show in a notification. */
@Composable
private fun UploadsPanel(
    uploads: List<UploadTask>,
    onCancel: (Long) -> Unit,
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
                // Failures and the running upload first; a long queue is summed up.
                val ordered =
                    uploads.sortedBy {
                        if (it.error != null) {
                            0
                        } else if (it.running) {
                            1
                        } else {
                            2
                        }
                    }
                ordered.take(MAX_UPLOAD_ROWS).forEach { task ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                task.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (task.error == null) {
                                Text(
                                    if (task.running) "${(task.fraction * 100).toInt()}%" else stringResource(R.string.upload_waiting),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            IconButton(
                                onClick = { if (task.error != null) onDismiss(task.id) else onCancel(task.id) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription =
                                        stringResource(if (task.error != null) R.string.action_dismiss else R.string.action_cancel),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        when {
                            task.error != null -> {
                                Text(task.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }

                            task.running && task.total > 0 -> {
                                LinearProgressIndicator(progress = { task.fraction }, modifier = Modifier.fillMaxWidth())
                            }

                            task.running -> {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                if (ordered.size > MAX_UPLOAD_ROWS) {
                    val more = ordered.size - MAX_UPLOAD_ROWS
                    Text(
                        pluralStringResource(R.plurals.upload_more_waiting, more, more),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

private const val MAX_UPLOAD_ROWS = 3

/** Replaces the top bar while files are selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.files_clear_selection))
            }
        },
        title = { Text(pluralStringResource(R.plurals.files_selected, count, count)) },
        actions = {
            IconButton(onClick = onSelectAll, enabled = !allSelected) {
                Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.files_select_all))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

private class IslandAction(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** The floating bar of batch actions shown at the bottom while selecting. */
@Composable
private fun SelectionIsland(actions: List<IslandAction>) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 8.dp,
    ) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            actions.forEach { action ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(22.dp))
                            .clickable(enabled = action.enabled, onClick = action.onClick)
                            .alpha(if (action.enabled) 1f else 0.38f)
                            .widthIn(min = 76.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(action.icon, contentDescription = null)
                    Spacer(Modifier.height(2.dp))
                    Text(action.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

/** A check badge over a selected item's thumbnail; with [showEmpty], an empty ring on the others. */
@Composable
private fun SelectionMark(
    selected: Boolean,
    modifier: Modifier = Modifier,
    showEmpty: Boolean = false,
) {
    AnimatedVisibility(selected || showEmpty, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
        Box(
            modifier
                .clip(CircleShape)
                .then(
                    if (selected) {
                        Modifier.background(MaterialTheme.colorScheme.primary)
                    } else {
                        Modifier
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                            .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(
    state: FilesUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onClick: (CloudFile) -> Unit,
    onLongClick: (CloudFile) -> Unit,
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
            val selected = file.id in state.selected
            ListItem(
                modifier =
                    Modifier
                        .animateItem()
                        .combinedClickable(onClick = { onClick(file) }, onLongClick = { onLongClick(file) }),
                colors =
                    ListItemDefaults.colors(
                        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    ),
                leadingContent = {
                    Box {
                        FileThumbnail(file, Modifier.size(44.dp), iconSize = 24.dp, cornerRadius = 12.dp)
                        SelectionMark(selected, Modifier.size(44.dp))
                    }
                },
                headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(fileSubtitle(context, file), maxLines = 1) },
                trailingContent = {
                    if (!state.selecting) {
                        IconButton(onClick = { onMore(file) }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.files_more))
                        }
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
    onMore: (CloudFile) -> Unit,
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
            val selected = file.id in state.selected
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                modifier =
                    Modifier
                        .animateItem()
                        .combinedClickable(onClick = { onClick(file) }, onLongClick = { onLongClick(file) }),
            ) {
                Box {
                    FileThumbnail(
                        file,
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.25f)
                            .padding(6.dp),
                        iconSize = 48.dp,
                        cornerRadius = 10.dp,
                    )
                    if (state.selecting) {
                        SelectionMark(
                            selected,
                            Modifier
                                .padding(12.dp)
                                .size(28.dp),
                            showEmpty = true,
                        )
                    }
                }
                Row(Modifier.padding(start = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(bottom = 4.dp, end = if (state.selecting) 12.dp else 0.dp),
                    ) {
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
                    if (!state.selecting) {
                        IconButton(onClick = { onMore(file) }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.files_more),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
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
