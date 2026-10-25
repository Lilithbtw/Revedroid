package app.revedroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.revedroid.data.store.AppSettings
import app.revedroid.ui.navigation.AppNavHost
import app.revedroid.ui.theme.RevedroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val store = container.store
        setContent {
            val settings by store.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            RevedroidTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                AppNavHost()
            }
        }
    }
}
