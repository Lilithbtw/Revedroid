package app.revedroid.ui.connect

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.revedroid.R
import app.revedroid.data.net.ServerUrls
import app.revedroid.data.store.StoredSession

@Composable
fun ConnectScreen(viewModel: ConnectViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val busy = state.stage is ConnectStage.Checking || state.stage is ConnectStage.Connected

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(88.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.CloudQueue,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.connect_title),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.connect_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(32.dp))

                OutlinedTextField(
                    value = state.address,
                    onValueChange = viewModel::onAddressChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    singleLine = true,
                    label = { Text(stringResource(R.string.connect_address_label)) },
                    placeholder = { Text("cloud.example.com") },
                    leadingIcon = { Icon(Icons.Rounded.Language, contentDescription = null) },
                    isError = state.addressError,
                    supportingText = {
                        Text(
                            stringResource(
                                if (state.addressError) R.string.connect_address_invalid else R.string.connect_address_hint,
                            ),
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { viewModel.connect() }),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = viewModel::connect,
                    enabled = !busy && state.address.isNotBlank(),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                ) {
                    Text(stringResource(R.string.connect_action))
                }
                Spacer(Modifier.height(24.dp))

                AnimatedContent(
                    targetState = state.stage,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    contentKey = { it::class },
                    label = "connect-stage",
                ) { stage ->
                    StageCard(stage, state.cloudflareSeen, viewModel)
                }

                if (accounts.isNotEmpty() && !busy) {
                    Spacer(Modifier.height(24.dp))
                    SavedAccounts(accounts, onPick = viewModel::connectTo)
                }
            }
        }
    }
}

@Composable
private fun StageCard(
    stage: ConnectStage,
    cloudflareSeen: Boolean,
    viewModel: ConnectViewModel,
) {
    when (stage) {
        ConnectStage.Idle -> {
            Spacer(Modifier.height(1.dp))
        }

        is ConnectStage.Checking -> {
            StatusCard(
                icon = if (stage.afterChallenge) Icons.Rounded.Security else Icons.Rounded.CloudQueue,
                title =
                    stringResource(
                        if (stage.afterChallenge) R.string.connect_waiting_after_challenge else R.string.connect_checking,
                    ),
                body = stringResource(R.string.connect_attempt, stage.attempt, stage.maxAttempts),
                progress = true,
                action = { TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.action_cancel)) } },
            )
        }

        is ConnectStage.NeedsChallenge -> {
            StatusCard(
                icon = Icons.Rounded.Security,
                title = stringResource(R.string.connect_challenge_title),
                body = stringResource(R.string.connect_challenge_body),
                action = {
                    FilledTonalButton(onClick = viewModel::openChallengeAgain) {
                        Text(stringResource(R.string.connect_challenge_action))
                    }
                },
            )
        }

        is ConnectStage.Failed -> {
            StatusCard(
                icon = Icons.Rounded.ErrorOutline,
                title = stringResource(R.string.connect_failed),
                body = stage.message,
                error = true,
                action = { TextButton(onClick = viewModel::connect) { Text(stringResource(R.string.action_retry)) } },
            )
        }

        is ConnectStage.Connected -> {
            StatusCard(
                icon = Icons.Rounded.CheckCircle,
                title = stringResource(R.string.connect_connected),
                body =
                    buildString {
                        append(stringResource(R.string.connect_version, stage.version))
                        if (cloudflareSeen) append(" · ").append(stringResource(R.string.connect_via_cloudflare))
                    },
            )
        }
    }
}

@Composable
private fun StatusCard(
    icon: ImageVector,
    title: String,
    body: String,
    progress: Boolean = false,
    error: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val colors =
        if (error) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        }
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null)
                Spacer(Modifier.size(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (progress) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (action != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { action() }
            }
        }
    }
}

@Composable
private fun SavedAccounts(
    accounts: List<StoredSession>,
    onPick: (StoredSession) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.connect_saved_accounts),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth(),
        ) {
            accounts.forEach { account ->
                ListItem(
                    modifier = Modifier.clickable { onPick(account) },
                    leadingContent = { Icon(Icons.Rounded.AccountCircle, contentDescription = null) },
                    headlineContent = { Text(account.user.nickname.ifBlank { account.user.email.orEmpty() }, maxLines = 1) },
                    supportingContent = {
                        Text(
                            listOfNotNull(account.user.email, ServerUrls.displayHost(account.serverUrl)).joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}
