package app.revedroid.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Returns a function that runs an action after asking for the notification
 * permission (Android 13+) if it hasn't been granted. The action runs whatever
 * the answer: notifications only add upload progress, they aren't required.
 */
@Composable
fun rememberNotificationPermission(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            pending[0]?.invoke()
            pending[0] = null
        }
    return remember(launcher) {
        { action ->
            val granted =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                action()
            } else {
                pending[0] = action
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
