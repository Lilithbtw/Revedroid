package app.revedroid.ui

import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.revedroid.container
import app.revedroid.data.store.AppSettings
import app.revedroid.ui.lock.LockScreen
import app.revedroid.ui.theme.RevedroidTheme

/**
 * What every activity shows around its content: the chosen theme, the window
 * privacy flags, and the passcode screen when the app is locked. The content
 * stays composed underneath the lock, so unlocking keeps the user's place.
 */
@Composable
fun RevedroidRoot(
    onLeave: () -> Unit,
    content: @Composable () -> Unit,
) {
    val container = LocalContext.current.container
    val activity = LocalActivity.current
    val settings by container.store.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val locked by container.appLock.locked.collectAsStateWithLifecycle()

    LaunchedEffect(settings.hideContent, settings.appLock) {
        val window = activity?.window ?: return@LaunchedEffect
        if (settings.hideContent) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        // Keep the recents thumbnail from showing files the passcode protects.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!settings.appLock && !settings.hideContent)
        }
    }

    RevedroidTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
        Box(Modifier.fillMaxSize()) {
            content()
            when (locked) {
                false -> Unit

                // Settings not read yet: don't flash content that may be locked.
                null -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {}

                true -> LockScreen(settings.passcodeLength, settings.biometricUnlock, onLeave)
            }
        }
    }
}
