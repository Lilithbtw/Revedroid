package app.revedroid.ui.login

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.revedroid.R
import app.revedroid.data.net.ServerUrls

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onLoggedIn: () -> Unit,
    onChangeServer: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.loggedIn.collect { onLoggedIn() } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(80.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (state.twoFactorSessionId != null) Icons.Rounded.Key else Icons.Rounded.Person,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(if (state.twoFactorSessionId != null) R.string.login_2fa_title else R.string.login_title),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                state.siteTitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                AssistChip(
                    onClick = onChangeServer,
                    label = { Text(ServerUrls.displayHost(state.server)) },
                    leadingIcon = { Icon(Icons.Rounded.Dns, contentDescription = null, Modifier.size(18.dp)) },
                    trailingIcon = { Text(stringResource(R.string.login_change_server), style = MaterialTheme.typography.labelMedium) },
                )
                Spacer(Modifier.height(24.dp))

                when {
                    state.loadingConfig -> {
                        CircularProgressIndicator()
                    }

                    state.configError != null -> {
                        Text(
                            state.configError!!,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = viewModel::loadConfig) { Text(stringResource(R.string.action_retry)) }
                    }

                    else -> {
                        AnimatedContent(state.twoFactorSessionId != null, label = "login-phase") { twoFactor ->
                            if (twoFactor) TwoFactorForm(state, viewModel) else CredentialsForm(state, viewModel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialsForm(
    state: LoginUiState,
    viewModel: LoginViewModel,
) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.login_email)) },
            leadingIcon = { Icon(Icons.Rounded.Email, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.login_password)) },
            leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription =
                            stringResource(
                                if (showPassword) R.string.login_hide_password else R.string.login_show_password,
                            ),
                    )
                }
            },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (state.captcha == CaptchaKind.None) ImeAction.Done else ImeAction.Next,
                ),
            keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
        )

        when (val captcha = state.captcha) {
            CaptchaKind.None -> {}

            CaptchaKind.Image -> {
                ImageCaptcha(state, viewModel)
            }

            else -> {
                WebCaptcha(
                    kind = captcha,
                    serverUrl = state.server,
                    generation = state.captchaGeneration,
                    dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f,
                    onToken = viewModel::onWebCaptchaToken,
                )
            }
        }

        ErrorText(state.error)
        SubmitButton(
            label = stringResource(R.string.login_action),
            busy = state.submitting,
            enabled = state.canSubmit,
            onClick = viewModel::submit,
        )
    }
}

@Composable
private fun ImageCaptcha(
    state: LoginUiState,
    viewModel: LoginViewModel,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.captchaText,
            onValueChange = viewModel::onCaptchaTextChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text(stringResource(R.string.login_captcha)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
        )
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(width = 120.dp, height = 56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClick = viewModel::refreshImageCaptcha),
            contentAlignment = Alignment.Center,
        ) {
            val image = state.captchaImage
            if (image != null) {
                Image(
                    image,
                    contentDescription = stringResource(R.string.login_captcha_refresh),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.login_captcha_refresh))
            }
        }
    }
}

@Composable
private fun TwoFactorForm(
    state: LoginUiState,
    viewModel: LoginViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.login_2fa_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.otp,
            onValueChange = viewModel::onOtpChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.login_2fa_code)) },
            textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
        )
        ErrorText(state.error)
        SubmitButton(
            label = stringResource(R.string.login_2fa_action),
            busy = state.submitting,
            enabled = state.canSubmit,
            onClick = viewModel::submit,
        )
        TextButton(onClick = viewModel::cancelTwoFactor, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.action_back))
        }
    }
}

@Composable
private fun ErrorText(error: String?) {
    if (error == null) return
    Text(
        error,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SubmitButton(
    label: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(52.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
        } else {
            Text(label)
        }
    }
}
