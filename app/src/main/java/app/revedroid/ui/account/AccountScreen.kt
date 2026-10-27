package app.revedroid.ui.account

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.revedroid.AppContainer
import app.revedroid.BuildConfig
import app.revedroid.R
import app.revedroid.data.api.Capacity
import app.revedroid.data.api.User
import app.revedroid.data.net.ServerUrls
import app.revedroid.data.repo.ShareExpiry
import app.revedroid.data.store.AppSettings
import app.revedroid.data.store.StoredSession
import app.revedroid.data.store.ThemeMode
import app.revedroid.ui.components.userMessage
import app.revedroid.ui.files.ConfirmDialog
import app.revedroid.ui.files.formatSize
import app.revedroid.ui.files.label
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AccountUiState(
    val user: User? = null,
    val capacity: Capacity? = null,
    val error: String? = null,
    /** Bytes of downloaded files and thumbnails kept on the device; null while measuring. */
    val cacheBytes: Long? = null,
)

class AccountViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            AccountUiState(
                user =
                    container.sessions.session.value
                        ?.user,
            ),
        )
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    val settings: StateFlow<AppSettings> =
        container.store.settings
            .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val accounts: StateFlow<List<StoredSession>> = container.sessions.accounts

    val activeAccount: StateFlow<StoredSession?> = container.sessions.session

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    val server: String get() = container.serverUrl.orEmpty()

    fun refresh() {
        viewModelScope.launch {
            try {
                val user = container.authRepository.me()
                container.sessions.updateUser(user)
                val capacity = container.authRepository.capacity()
                _state.update { it.copy(user = user, capacity = capacity, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage()) }
            }
        }
        measureCache()
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { container.store.setThemeMode(mode) }

    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { container.store.setDynamicColor(enabled) }

    fun setShareExpiry(expiry: ShareExpiry) = viewModelScope.launch { container.store.setShareExpiry(expiry) }

    fun clearCache() {
        viewModelScope.launch {
            container.fileCache.clear()
            SingletonImageLoader.get(container.context).let { loader ->
                loader.memoryCache?.clear()
                withContext(Dispatchers.IO) { loader.diskCache?.clear() }
            }
            measureCache()
            _messages.send("Cache cleared")
        }
    }

    private fun measureCache() {
        viewModelScope.launch {
            val images = withContext(Dispatchers.IO) { SingletonImageLoader.get(container.context).diskCache?.size ?: 0L }
            _state.update { it.copy(cacheBytes = container.fileCache.size() + images) }
        }
    }

    /** Switching mid-upload would send the remaining chunks with the wrong account. */
    private suspend fun uploadsBusy(): Boolean {
        if (!container.uploads.state.value.busy) return false
        _messages.send("Wait for the uploads to finish, or cancel them, first")
        return true
    }

    fun switchAccount(
        account: StoredSession,
        then: () -> Unit,
    ) = viewModelScope.launch {
        if (uploadsBusy()) return@launch
        container.sessions.switchTo(account.key) ?: return@launch
        container.store.setServerUrl(account.serverUrl)
        then()
    }

    fun addAccount(then: () -> Unit) =
        viewModelScope.launch {
            if (uploadsBusy()) return@launch
            container.sessions.deactivate()
            then()
        }

    fun removeAccount(account: StoredSession) = viewModelScope.launch { container.authRepository.remove(account) }

    /** [then] learns whether another account is left to continue with. */
    fun signOut(then: (Boolean) -> Unit) =
        viewModelScope.launch {
            if (uploadsBusy()) return@launch
            val next = container.authRepository.signOut()
            if (next != null) {
                container.sessions.switchTo(next.key)
                container.store.setServerUrl(next.serverUrl)
            }
            then(next != null)
        }
}

private sealed interface SettingsDialog {
    data class RemoveAccount(
        val account: StoredSession,
    ) : SettingsDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    viewModel: AccountViewModel,
    refreshSignal: Long,
    onSignedOut: (anotherAccount: Boolean) -> Unit,
    onSwitchServer: () -> Unit,
    onAccountSwitched: () -> Unit,
    onAddAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val active by viewModel.activeAccount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    LaunchedEffect(refreshSignal) { viewModel.refresh() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProfileCard(state, viewModel.server)

            state.capacity?.let { capacity ->
                SectionCard {
                    Column(Modifier.padding(20.dp)) {
                        Text(stringResource(R.string.account_storage), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        val fraction = if (capacity.total > 0) (capacity.used.toFloat() / capacity.total).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(
                                R.string.account_storage_used,
                                formatSize(context, capacity.used),
                                formatSize(context, capacity.total),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Section(stringResource(R.string.settings_accounts)) {
                accounts.forEach { account ->
                    val isActive = account.key == active?.key
                    ListItem(
                        modifier = Modifier.clickable(enabled = !isActive) { viewModel.switchAccount(account, onAccountSwitched) },
                        leadingContent = { Avatar(account.user, account.serverUrl, Modifier.size(40.dp)) },
                        headlineContent = { Text(account.user.nickname.ifBlank { account.user.email.orEmpty() }, maxLines = 1) },
                        supportingContent = {
                            Text(
                                listOfNotNull(account.user.email, ServerUrls.displayHost(account.serverUrl)).joinToString(" · "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            if (isActive) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = stringResource(R.string.settings_account_active),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                IconButton(onClick = { dialog = SettingsDialog.RemoveAccount(account) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.settings_account_remove))
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                SettingRow(
                    icon = Icons.Rounded.PersonAdd,
                    title = stringResource(R.string.settings_account_add),
                    onClick = { viewModel.addAccount(onAddAccount) },
                )
            }

            Section(stringResource(R.string.settings_appearance)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.account_theme)) },
                    leadingContent = { Icon(Icons.Rounded.Palette, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                SingleChoiceSegmentedButtonRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    val modes = ThemeMode.entries
                    modes.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        ) {
                            Text(
                                stringResource(
                                    when (mode) {
                                        ThemeMode.System -> R.string.theme_system
                                        ThemeMode.Light -> R.string.theme_light
                                        ThemeMode.Dark -> R.string.theme_dark
                                    },
                                ),
                            )
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.account_dynamic_color)) },
                        supportingContent = { Text(stringResource(R.string.account_dynamic_color_body)) },
                        trailingContent = {
                            Switch(checked = settings.dynamicColor, onCheckedChange = viewModel::setDynamicColor)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }

            Section(stringResource(R.string.settings_files)) {
                SettingChoice(
                    icon = Icons.Rounded.Link,
                    title = stringResource(R.string.settings_share_expiry),
                    options = ShareExpiry.entries,
                    selected = settings.shareExpiry,
                    label = { stringResource(it.label()) },
                    onSelect = viewModel::setShareExpiry,
                )
                SettingRow(
                    icon = Icons.Rounded.DeleteSweep,
                    title = stringResource(R.string.settings_clear_cache),
                    body =
                        state.cacheBytes?.let { stringResource(R.string.settings_cache_size, formatSize(context, it)) }
                            ?: stringResource(R.string.settings_cache_measuring),
                    onClick = viewModel::clearCache,
                )
            }

            Section(stringResource(R.string.settings_updates)) {
                SettingRow(
                    icon = Icons.Rounded.Info,
                    title = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                )
                SettingRow(
                    icon = Icons.Rounded.Code,
                    title = stringResource(R.string.settings_source),
                    body = SOURCE_URL.removePrefix("https://"),
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri())) },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onSwitchServer, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.account_switch_server))
                }
                OutlinedButton(onClick = { viewModel.signOut(onSignedOut) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.account_sign_out))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    when (val d = dialog) {
        null -> {}

        is SettingsDialog.RemoveAccount -> {
            ConfirmDialog(
                title = stringResource(R.string.settings_account_remove_title),
                text =
                    stringResource(
                        R.string.settings_account_remove_body,
                        d.account.user.email ?: d.account.user.nickname,
                        ServerUrls.displayHost(d.account.serverUrl),
                    ),
                confirmLabel = stringResource(R.string.settings_account_remove),
                onConfirm = {
                    dialog = null
                    viewModel.removeAccount(d.account)
                },
                onDismiss = { dialog = null },
            )
        }
    }
}

private const val SOURCE_URL = "https://github.com/Lilithbtw/Revedroid"

@Composable
private fun ProfileCard(
    state: AccountUiState,
    server: String,
) {
    SectionCard(container = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(state.user, server, Modifier.size(64.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.user?.nickname.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                state.user?.email?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                state.user?.group?.name?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Dns,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        ServerUrls.displayHost(server),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

/** The user's avatar from their server, over a person icon for users without one. */
@Composable
private fun Avatar(
    user: User?,
    server: String,
    modifier: Modifier = Modifier,
) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = modifier) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.fillMaxSize(0.6f),
            )
            if (user != null) {
                AsyncImage(
                    model = ServerUrls.api(server, "user/avatar/${user.id}"),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                )
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        SectionCard {
            Column(Modifier.padding(vertical = 8.dp), content = content)
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    body: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = body?.let { { Text(it) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun SettingSwitch(
    icon: ImageVector,
    title: String,
    body: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingRow(
        icon = icon,
        title = title,
        body = body,
        onClick = { onCheckedChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

/** A row showing the current choice; tapping it opens a menu of [options]. */
@Composable
private fun <T> SettingChoice(
    icon: ImageVector,
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingRow(icon = icon, title = title, body = label(selected), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    trailingIcon = { if (option == selected) Icon(Icons.Rounded.CheckCircle, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}
