package app.revedroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.revedroid.container
import app.revedroid.data.store.AppSettings
import app.revedroid.ui.theme.RevedroidTheme

/** What every activity shows around its content: the chosen theme. */
@Composable
fun RevedroidRoot(content: @Composable () -> Unit) {
    val container = LocalContext.current.container
    val settings by container.store.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    RevedroidTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, content = content)
}
