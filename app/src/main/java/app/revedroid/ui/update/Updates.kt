package app.revedroid.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.revedroid.AppContainer
import app.revedroid.R
import app.revedroid.data.update.AppUpdate
import app.revedroid.ui.components.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UpdateUiState(
    val checking: Boolean = false,
    /** The newer release, once found; the dialog shows while this is set. */
    val available: AppUpdate? = null,
    /** Download progress from 0 to 1 while installing, null otherwise. */
    val downloading: Float? = null,
)

class UpdateViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val updater = container.updater

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    val supported: Boolean get() = updater.supported

    private var installJob: Job? = null

    init {
        viewModelScope.launch { updater.installError.filterNotNull().collect { _messages.send(it) } }
    }

    /** Checks at most twice a day, and only if the user hasn't turned checks off. */
    fun checkAutomatically() {
        if (!supported) return
        viewModelScope.launch {
            if (!container.store.settings
                    .first()
                    .checkUpdates
            ) {
                return@launch
            }
            val now = System.currentTimeMillis()
            if (now - container.store.lastUpdateCheck() < AUTO_CHECK_INTERVAL_MILLIS) return@launch
            check(manual = false)
        }
    }

    fun checkNow() {
        if (_state.value.checking) return
        viewModelScope.launch { check(manual = true) }
    }

    private suspend fun check(manual: Boolean) {
        _state.update { it.copy(checking = true) }
        try {
            val update = updater.check()
            container.store.setLastUpdateCheck(System.currentTimeMillis())
            if (update != null) {
                _state.update { it.copy(available = update) }
            } else if (manual) {
                _messages.send("Revedroid is up to date")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (manual) _messages.send(e.userMessage())
        } finally {
            _state.update { it.copy(checking = false) }
        }
    }

    fun install() {
        val update = _state.value.available ?: return
        if (installJob?.isActive == true) return
        container.appLock.expectExternalActivity()
        installJob =
            viewModelScope.launch {
                _state.update { it.copy(downloading = 0f) }
                try {
                    updater.install(update) { p ->
                        _state.update { it.copy(downloading = if (p.total > 0) p.sent.toFloat() / p.total else 0f) }
                    }
                    // The system installer takes over from here.
                    _state.update { it.copy(available = null) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _messages.send(e.userMessage())
                } finally {
                    _state.update { it.copy(downloading = null) }
                }
            }
    }

    fun dismiss() {
        installJob?.cancel()
        _state.update { it.copy(available = null, downloading = null) }
    }

    private companion object {
        const val AUTO_CHECK_INTERVAL_MILLIS = 12 * 60 * 60 * 1000L
    }
}

@Composable
fun UpdateDialog(
    update: AppUpdate,
    progress: Float?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
        title = { Text(stringResource(R.string.update_title, update.version)) },
        text = {
            Column {
                Text(stringResource(R.string.update_body))
                if (update.notes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        update.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState()),
                    )
                }
                if (progress != null) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onInstall, enabled = progress == null) { Text(stringResource(R.string.update_install)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) } },
    )
}
