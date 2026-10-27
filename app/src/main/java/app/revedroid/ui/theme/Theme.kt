package app.revedroid.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import app.revedroid.data.store.ThemeMode

// Fallback scheme generated from the Cloudreve-blue seed #3F6CC4, for devices
// without Material You or when dynamic color is switched off.
private val LightColors =
    lightColorScheme(
        primary = Color(0xFF3A5BA9),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDAE2FF),
        onPrimaryContainer = Color(0xFF001946),
        secondary = Color(0xFF585E71),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDCE2F9),
        onSecondaryContainer = Color(0xFF151B2C),
        tertiary = Color(0xFF725572),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFDD7FA),
        onTertiaryContainer = Color(0xFF2A132C),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFF9F9FF),
        onBackground = Color(0xFF191C23),
        surface = Color(0xFFF9F9FF),
        onSurface = Color(0xFF191C23),
        surfaceVariant = Color(0xFFE1E2EC),
        onSurfaceVariant = Color(0xFF44464F),
        outline = Color(0xFF757780),
        outlineVariant = Color(0xFFC5C6D0),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF3F3FA),
        surfaceContainer = Color(0xFFEDEDF4),
        surfaceContainerHigh = Color(0xFFE7E8EE),
        surfaceContainerHighest = Color(0xFFE2E2E9),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFB1C5FF),
        onPrimary = Color(0xFF002C71),
        primaryContainer = Color(0xFF1F438F),
        onPrimaryContainer = Color(0xFFDAE2FF),
        secondary = Color(0xFFC0C6DC),
        onSecondary = Color(0xFF2A3042),
        secondaryContainer = Color(0xFF404659),
        onSecondaryContainer = Color(0xFFDCE2F9),
        tertiary = Color(0xFFE0BBDE),
        onTertiary = Color(0xFF412742),
        tertiaryContainer = Color(0xFF593D5A),
        onTertiaryContainer = Color(0xFFFDD7FA),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF111318),
        onBackground = Color(0xFFE2E2E9),
        surface = Color(0xFF111318),
        onSurface = Color(0xFFE2E2E9),
        surfaceVariant = Color(0xFF44464F),
        onSurfaceVariant = Color(0xFFC5C6D0),
        outline = Color(0xFF8F909A),
        outlineVariant = Color(0xFF44464F),
        surfaceContainerLowest = Color(0xFF0C0E13),
        surfaceContainerLow = Color(0xFF191C20),
        surfaceContainer = Color(0xFF1D2024),
        surfaceContainerHigh = Color(0xFF282A2F),
        surfaceContainerHighest = Color(0xFF33353A),
    )

@Composable
fun RevedroidTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark =
        when (themeMode) {
            ThemeMode.System -> isSystemInDarkTheme()
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
    val context = LocalContext.current
    val colors =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            dark -> {
                DarkColors
            }

            else -> {
                LightColors
            }
        }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(colorScheme = colors, content = content)
}

/**
 * A full-screen dialog draws in its own window, which doesn't inherit the
 * activity's light or dark bar icons; call this inside the dialog to match them.
 */
@Composable
fun DialogSystemBars() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}
