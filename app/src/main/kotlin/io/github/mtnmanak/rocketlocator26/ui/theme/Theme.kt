package io.github.mtnmanak.rocketlocator26.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** NASA-adjacent deep blue, also the launcher icon background. */
private val DeepBlue = Color(0xFF0B3D91)
private val DeepBlueLight = Color(0xFF4A6BC4)
private val RecoveryOrange = Color(0xFFE65100)
private val RecoveryOrangeLight = Color(0xFFFFB77C)

private val LightColors = lightColorScheme(
    primary = DeepBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001A43),
    secondary = RecoveryOrange,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBC7),
    onSecondaryContainer = Color(0xFF331100),
    background = Color(0xFFFAF9FD),
    onBackground = Color(0xFF1A1B1F),
    surface = Color(0xFFFAF9FD),
    onSurface = Color(0xFF1A1B1F),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44464F),
)

private val DarkColors = darkColorScheme(
    primary = DeepBlueLight,
    onPrimary = Color(0xFF00296B),
    primaryContainer = Color(0xFF002F77),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = RecoveryOrangeLight,
    onSecondary = Color(0xFF542100),
    secondaryContainer = Color(0xFF783200),
    onSecondaryContainer = Color(0xFFFFDBC7),
    background = Color(0xFF121317),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF121317),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
)

/**
 * App-wide Material3 theme.
 *
 * Uses Android 12+ dynamic color when available (and [dynamicColor] is true),
 * otherwise falls back to a static deep-blue scheme. Follows the system
 * dark-mode setting by default.
 */
@Composable
fun RocketLocatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
