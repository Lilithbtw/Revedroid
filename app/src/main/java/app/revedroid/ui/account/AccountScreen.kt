package app.revedroid.ui.account

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.revedroid.AppContainer
import app.revedroid.R
import app.revedroid.data.api.Capacity
import app.revedroid.data.api.User
import app.revedroid.data.net.ServerUrls
import app.revedroid.data.store.AppSettings
import app.revedroid.data.store.ThemeMode
import app.revedroid.ui.components.userMessage
import app.revedroid.ui.files.formatSize
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountUiState(
    val user: User? = null,
    val capacity: Capacity? = null,
    val error: String? = null,
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

    val server: String get() = container.serverUrl.orEmpty()

    fun refresh() {
        viewModelScope.launch {
            try {
                val user = container.authRepository.me()
                val capacity = container.authRepository.capacity()
                _state.update { it.copy(user = user, capacity = capacity, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage()) }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { container.store.setThemeMode(mode) }

    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { container.store.setDynamicColor(enabled) }

    fun signOut(then: () -> Unit) =
        viewModelScope.launch {
            container.authRepository.signOut()
            then()
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    viewModel: AccountViewModel,
    refreshSignal: Long,
    onSignedOut: () -> Unit,
    onSwitchServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(refreshSignal) { viewModel.refresh() }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_account)) }) },
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

            SectionCard {
                Column(Modifier.padding(vertical = 8.dp)) {
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
}

@Composable
private fun ProfileCard(
    state: AccountUiState,
    server: String,
) {
    SectionCard(container = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp)) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(14.dp),
                )
                state.user?.let { user ->
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
