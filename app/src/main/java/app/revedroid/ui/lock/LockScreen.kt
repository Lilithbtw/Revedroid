package app.revedroid.ui.lock

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.revedroid.R
import app.revedroid.container
import app.revedroid.data.store.Passcode
import app.revedroid.data.store.UnlockResult
import app.revedroid.ui.theme.DialogSystemBars
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Covers the app until the passcode (or a biometric) is given. Back leaves the app. */
@Composable
fun LockScreen(
    passcodeLength: Int,
    biometricUnlock: Boolean,
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lock = context.container.appLock
    val scope = rememberCoroutineScope()
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var retryAt by remember { mutableLongStateOf(0L) }
    var secondsLeft by remember { mutableLongStateOf(0L) }
    var biometricsReset by remember { mutableStateOf(false) }
    val biometrics = biometricUnlock && remember { context.canUseBiometrics() }
    val promptTitle = stringResource(R.string.lock_biometric_title)
    val promptCancel = stringResource(R.string.lock_use_passcode)

    suspend fun askBiometrics() {
        val cipher = lock.biometricCipher()
        if (cipher == null) {
            biometricsReset = true
            return
        }
        activity?.authenticateWithBiometrics(promptTitle, promptCancel, cipher) { scope.launch { lock.unlockWithBiometrics(it) } }
    }

    BackHandler(onBack = onLeave)
    LaunchedEffect(biometrics) { if (biometrics) askBiometrics() }
    LaunchedEffect(retryAt) {
        while (SystemClock.elapsedRealtime() < retryAt) {
            secondsLeft = (retryAt - SystemClock.elapsedRealtime() + 999) / 1000
            delay(250)
        }
        secondsLeft = 0
    }

    fun submit(code: String) {
        scope.launch {
            when (val result = lock.unlock(code)) {
                UnlockResult.Unlocked -> {
                    Unit
                }

                UnlockResult.Wrong -> {
                    wrong = true
                }

                is UnlockResult.Wait -> {
                    wrong = true
                    retryAt = result.retryAt
                }
            }
            entered = ""
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        PasscodeLayout(
            title = stringResource(R.string.lock_title),
            message =
                when {
                    secondsLeft > 0 -> stringResource(R.string.lock_wait, secondsLeft)
                    wrong -> stringResource(R.string.lock_wrong)
                    biometricsReset -> stringResource(R.string.lock_biometric_reset)
                    else -> null
                },
            entered = entered.length,
            slots = passcodeLength,
            enabled = secondsLeft == 0L,
            onDigit = {
                wrong = false
                entered += it
                if (passcodeLength > 0 && entered.length >= passcodeLength) submit(entered)
            },
            onDelete = { entered = entered.dropLast(1) },
            extraKey =
                if (biometrics) {
                    {
                        IconButton(onClick = { scope.launch { askBiometrics() } }, modifier = Modifier.size(KEY_SIZE)) {
                            Icon(Icons.Rounded.Fingerprint, contentDescription = stringResource(R.string.lock_biometric_title))
                        }
                    }
                } else {
                    null
                },
        )
    }
}

/** Two steps: choose a passcode, then type it again. [onCreated] gets the new passcode. */
@Composable
fun CreatePasscodeDialog(
    onCreated: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var first by remember { mutableStateOf<String?>(null) }
    var entered by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    PasscodeDialog(onDismiss) {
        PasscodeLayout(
            title = stringResource(if (first == null) R.string.passcode_create_title else R.string.passcode_confirm_title),
            message = stringResource(if (mismatch) R.string.passcode_mismatch else R.string.passcode_create_hint),
            messageIsError = mismatch,
            entered = entered.length,
            slots = first?.length ?: Passcode.MIN_LENGTH,
            enabled = true,
            onDigit = { digit ->
                if (entered.length >= Passcode.MAX_LENGTH) return@PasscodeLayout
                mismatch = false
                entered += digit
                val chosen = first
                if (chosen != null && entered.length == chosen.length) {
                    if (entered == chosen) {
                        onCreated(chosen)
                    } else {
                        mismatch = true
                        first = null
                    }
                    entered = ""
                }
            },
            onDelete = { entered = entered.dropLast(1) },
            footer = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    if (first == null) {
                        Button(
                            onClick = {
                                first = entered
                                entered = ""
                            },
                            enabled = Passcode.isValid(entered),
                        ) { Text(stringResource(R.string.action_next)) }
                    }
                }
            },
        )
    }
}

/** Asks for the current passcode before it can be changed or turned off. */
@Composable
fun CheckPasscodeDialog(
    passcodeLength: Int,
    onConfirmed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val lock = LocalContext.current.container.appLock
    val scope = rememberCoroutineScope()
    var entered by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val wrongText = stringResource(R.string.lock_wrong)
    val waitText = stringResource(R.string.lock_wait_later)
    PasscodeDialog(onDismiss) {
        PasscodeLayout(
            title = stringResource(R.string.passcode_current_title),
            message = message,
            messageIsError = true,
            entered = entered.length,
            slots = passcodeLength,
            enabled = true,
            onDigit = {
                entered += it
                if (entered.length >= passcodeLength) {
                    val code = entered
                    entered = ""
                    scope.launch {
                        when (lock.unlock(code)) {
                            UnlockResult.Unlocked -> onConfirmed()
                            UnlockResult.Wrong -> message = wrongText
                            is UnlockResult.Wait -> message = waitText
                        }
                    }
                }
            },
            onDelete = { entered = entered.dropLast(1) },
            footer = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PasscodeDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DialogSystemBars()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { content() }
    }
}

@Composable
private fun PasscodeLayout(
    title: String,
    message: String?,
    entered: Int,
    slots: Int,
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    messageIsError: Boolean = true,
    extraKey: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.height(16.dp)) {
            repeat(maxOf(slots, entered)) { index ->
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(
                            if (index < entered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            message.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            minLines = 2,
        )
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.widthIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            listOf("123", "456", "789").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    row.forEach { digit -> DigitKey(digit, enabled) { onDigit(digit) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) { extraKey?.invoke() }
                DigitKey('0', enabled) { onDigit('0') }
                Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onDelete, enabled = entered > 0, modifier = Modifier.size(KEY_SIZE)) {
                        Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = stringResource(R.string.passcode_delete))
                    }
                }
            }
        }
        if (footer != null) {
            Spacer(Modifier.height(24.dp))
            footer()
        }
    }
}

@Composable
private fun DigitKey(
    digit: Char,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(KEY_SIZE),
    ) {
        Text(digit.toString(), style = MaterialTheme.typography.headlineSmall)
    }
}

private val KEY_SIZE = 72.dp
