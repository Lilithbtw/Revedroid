package app.revedroid.ui.shares

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.revedroid.AppContainer
import app.revedroid.R
import app.revedroid.container
import app.revedroid.data.api.Share
import app.revedroid.data.api.ShareListResponse
import app.revedroid.ui.components.userMessage
import app.revedroid.ui.files.ConfirmDialog
import app.revedroid.ui.files.FileThumbnail
import app.revedroid.ui.files.sendLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class SharesUiState(
    val shares: List<Share> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val error: String? = null,
)

class SharesViewModel(
    container: AppContainer,
) : ViewModel() {
    private val repo = container.shareRepository

    private val _state = MutableStateFlow(SharesUiState())
    val state: StateFlow<SharesUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var loadJob: Job? = null
    private var nextToken: String? = null

    init {
        load(pullToRefresh = false)
    }

    fun refresh() = load(pullToRefresh = true)

    private fun load(pullToRefresh: Boolean) {
        loadJob?.cancel()
        _state.update { it.copy(refreshing = pullToRefresh, loading = !pullToRefresh && it.shares.isEmpty(), error = null) }
        loadJob =
            viewModelScope.launch {
                try {
                    apply(repo.list(), append = false)
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
        val token = nextToken ?: return
        if (_state.value.loadingMore || _state.value.loading) return
        _state.update { it.copy(loadingMore = true) }
        loadJob =
            viewModelScope.launch {
                try {
                    apply(repo.list(token), append = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _messages.send(e.userMessage())
                } finally {
                    _state.update { it.copy(loadingMore = false) }
                }
            }
    }

    fun delete(share: Share) {
        viewModelScope.launch {
            try {
                repo.delete(share)
                _state.update { s -> s.copy(shares = s.shares.filterNot { it.id == share.id }) }
                _messages.send("Deleted the link to ${share.name}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.userMessage())
            }
        }
    }

    private fun apply(
        response: ShareListResponse,
        append: Boolean,
    ) {
        nextToken = response.pagination.nextToken?.takeIf { it.isNotEmpty() }
        _state.update {
            it.copy(
                shares = if (append) (it.shares + response.shares).distinctBy(Share::id) else response.shares,
                canLoadMore = nextToken != null,
                error = null,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharesScreen(
    viewModel: SharesViewModel,
    refreshSignal: Long,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var sheetShare by remember { mutableStateOf<Share?>(null) }
    var deleting by remember { mutableStateOf<Share?>(null) }
    val copiedText = stringResource(R.string.shares_copied)

    LaunchedEffect(refreshSignal) { if (refreshSignal > 0) viewModel.refresh() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(state.canLoadMore) {
        if (!state.canLoadMore) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index to listState.layoutInfo.totalItemsCount
        }.filter { (last, total) -> last != null && total > 0 && last >= total - 8 }
            .distinctUntilChanged()
            .collect { viewModel.loadMore() }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_shares)) }) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            when {
                state.loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }

                state.error != null && state.shares.isEmpty() -> {
                    EmptyState(Icons.Rounded.CloudOff, state.error!!, stringResource(R.string.action_retry), viewModel::refresh)
                }

                state.shares.isEmpty() -> {
                    EmptyState(Icons.Rounded.LinkOff, stringResource(R.string.shares_empty))
                }

                else -> {
                    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                        items(state.shares, key = { it.id }) { share ->
                            ShareRow(share, onClick = { sheetShare = share })
                        }
                        if (state.loadingMore) {
                            item {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) { CircularProgressIndicator(Modifier.size(28.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }

    sheetShare?.let { share ->
        ShareActionsSheet(
            share = share,
            onDismiss = { sheetShare = null },
            onCopy = {
                sheetShare = null
                context.copyToClipboard(share.name, share.url)
                // Android 13+ confirms copies itself.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar(copiedText) }
            },
            onSend = {
                sheetShare = null
                context.sendLink(share.url, share.name)
            },
            onOpen = {
                sheetShare = null
                context.startActivity(Intent(Intent.ACTION_VIEW, share.url.toUri()))
            },
            onDelete = {
                sheetShare = null
                deleting = share
            },
        )
    }

    deleting?.let { share ->
        ConfirmDialog(
            title = stringResource(R.string.shares_delete_title),
            text = stringResource(R.string.shares_delete_body, share.name),
            confirmLabel = stringResource(R.string.shares_delete),
            onConfirm = {
                deleting = null
                viewModel.delete(share)
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ShareRow(
    share: Share,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val expired = share.isExpired()
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            FileThumbnail(share.asFile(), Modifier.size(44.dp), iconSize = 24.dp, cornerRadius = 12.dp, thumbnail = false)
        },
        headlineContent = { Text(share.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        expiryText(context, share),
                        color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(shareStats(share), maxLines = 1)
            }
        },
        trailingContent = {
            IconButton(onClick = onClick) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.files_more))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareActionsSheet(
    share: Share,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSend: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FileThumbnail(share.asFile(), Modifier.size(56.dp), iconSize = 28.dp, cornerRadius = 16.dp, thumbnail = false)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(share.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        share.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DetailLine(Icons.Rounded.Schedule, expiryText(context, share), error = share.isExpired())
            share.createdAtInstant?.let {
                DetailLine(Icons.Rounded.Link, stringResource(R.string.shares_created, formatDate(context, it)))
            }
            share.password?.takeIf { it.isNotEmpty() }?.let {
                DetailLine(Icons.Rounded.Key, stringResource(R.string.shares_password, it))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SheetRow(Icons.Rounded.ContentCopy, stringResource(R.string.shares_copy), onClick = onCopy)
            SheetRow(Icons.Rounded.Share, stringResource(R.string.shares_send), onClick = onSend)
            SheetRow(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.shares_open), onClick = onOpen)
            SheetRow(Icons.Rounded.Delete, stringResource(R.string.shares_delete), destructive = true, onClick = onDelete)
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun DetailLine(
    icon: ImageVector,
    text: String,
    error: Boolean = false,
) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun SheetRow(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified
    ListItem(
        headlineContent = { Text(label, color = color) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = if (destructive) color else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .clickable(onClick = onClick),
    )
}

@Composable
private fun EmptyState(
    icon: ImageVector,
    text: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    // Scrollable so pull-to-refresh still works with nothing to show.
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

@Composable
private fun shareStats(share: Share): String =
    listOfNotNull(
        pluralStringResource(R.plurals.shares_views, share.visited.toInt(), share.visited.toInt()),
        share.remainingDownloads?.let { pluralStringResource(R.plurals.shares_downloads_left, it.toInt(), it.toInt()) },
        if (share.isPrivate) stringResource(R.string.shares_protected) else null,
    ).joinToString(" · ")

private fun expiryText(
    context: Context,
    share: Share,
): String {
    val expires = share.expiresAt
    return when {
        share.isExpired() && expires != null && expires.isBefore(Instant.now()) -> {
            context.getString(R.string.shares_expired_on, formatDate(context, expires))
        }

        share.isExpired() -> {
            context.getString(R.string.shares_expired)
        }

        expires != null -> {
            context.getString(R.string.shares_expires_on, formatDate(context, expires))
        }

        else -> {
            context.getString(R.string.shares_never_expires)
        }
    }
}

private fun formatDate(
    context: Context,
    instant: Instant,
): String =
    DateUtils.formatDateTime(
        context,
        instant.toEpochMilli(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )

private fun Context.copyToClipboard(
    label: String,
    text: String,
) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}
