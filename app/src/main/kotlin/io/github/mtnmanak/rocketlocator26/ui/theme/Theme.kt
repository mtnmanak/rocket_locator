package io.github.mtnmanak.rocketlocator26.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Mountain Man Rockets brand colours, from docs/brand/tokens.json (mmr-brand 1.1.0).
// Container roles are the brand's -soft tints composited over surface, because
// Material 3 containers must be opaque; text on them is always the plain text colour.

private val Navy = Color(0xFF03254F)
private val Paper = Color(0xFFF6FAFF)
private val White = Color(0xFFFFFFFF)

private val LightColors = lightColorScheme(
    primary = Color(0xFFBB4100), // btn-primary-bg (action)
    onPrimary = White,
    primaryContainer = Color(0xFFFFE8DA), // accent-soft over surface
    onPrimaryContainer = Navy,
    inversePrimary = Color(0xFFFF6509),
    secondary = Color(0xFF006AAB), // link-color
    onSecondary = White,
    secondaryContainer = Color(0xFFD9E9F2), // link-soft over surface
    onSecondaryContainer = Navy,
    tertiary = Color(0xFF855300), // warning
    onTertiary = White,
    tertiaryContainer = Color(0xFFEDE5D9), // warning-soft over surface
    onTertiaryContainer = Navy,
    background = Paper,
    onBackground = Navy,
    surface = White,
    onSurface = Navy,
    surfaceVariant = Color(0xFFE5F0F8), // surface-2 (mist)
    onSurfaceVariant = Color(0xFF45637B), // text-muted (slate)
    surfaceTint = White, // = surface: tonal elevation never shifts brand surfaces
    inverseSurface = Navy,
    inverseOnSurface = Paper,
    error = Color(0xFFAD2436), // danger
    onError = White,
    errorContainer = Color(0xFFF5E5E7), // danger-soft over surface
    onErrorContainer = Navy,
    outline = Color(0xFF7C93A8), // border-control
    outlineVariant = Color(0xFFB3CADC), // border
    scrim = Navy,
    surfaceBright = White,
    surfaceDim = Color(0xFFE5F0F8),
    surfaceContainerLowest = White,
    surfaceContainerLow = White,
    surfaceContainer = White,
    surfaceContainerHigh = White,
    surfaceContainerHighest = White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF6509), // btn-primary-bg (brand orange)
    onPrimary = Navy,
    primaryContainer = Color(0xFF363F4D), // accent-soft over surface
    onPrimaryContainer = Paper,
    inversePrimary = Color(0xFFBB4100),
    secondary = Color(0xFF6BC6F3), // link-color (sky)
    onSecondary = Navy,
    secondaryContainer = Color(0xFF1F4D70), // link-soft over surface
    onSecondaryContainer = Paper,
    tertiary = Color(0xFFFFB23E), // warning (amber)
    onTertiary = Navy,
    tertiaryContainer = Color(0xFF364A55), // warning-soft over surface
    onTertiaryContainer = Paper,
    background = Navy,
    onBackground = Paper,
    surface = Color(0xFF123859), // navy-raised
    onSurface = Paper,
    surfaceVariant = Color(0xFF1C476A), // surface-2 (navy-high)
    onSurfaceVariant = Color(0xFFA9C3D9), // text-muted (slate-light)
    surfaceTint = Color(0xFF123859), // = surface: tonal elevation never shifts brand surfaces
    inverseSurface = Paper,
    inverseOnSurface = Navy,
    error = Color(0xFFFF9EA5), // danger
    onError = Navy,
    errorContainer = Color(0xFF2E4462), // danger-soft over surface
    onErrorContainer = Paper,
    outline = Color(0xFF7E9DB8), // border-control
    outlineVariant = Color(0xFF557793), // border
    scrim = Navy,
    surfaceBright = Color(0xFF1C476A),
    surfaceDim = Navy,
    surfaceContainerLowest = Navy,
    surfaceContainerLow = Color(0xFF123859),
    surfaceContainer = Color(0xFF123859),
    surfaceContainerHigh = Color(0xFF123859),
    surfaceContainerHighest = Color(0xFF123859),
)

/**
 * Brand colours with no Material 3 role.
 *
 * [accentText] is orange for body-size words (brand orange itself fails 4.5:1 as
 * small text on dark surfaces); [success] and [warning] are the brand status colours.
 */
@Immutable
data class MmrColors(
    val accentText: Color,
    val success: Color,
    val warning: Color,
)

private val LightMmrColors = MmrColors(
    accentText = Color(0xFFAD3C00),
    success = Color(0xFF226B47),
    warning = Color(0xFF855300),
)

private val DarkMmrColors = MmrColors(
    accentText = Color(0xFFFF9A62),
    success = Color(0xFF6FD39A),
    warning = Color(0xFFFFB23E),
)

private val LocalMmrColors = staticCompositionLocalOf { DarkMmrColors }

/** Brand colours outside the Material scheme, for the current light/dark theme. */
val MaterialTheme.mmrColors: MmrColors
    @Composable
    @ReadOnlyComposable
    get() = LocalMmrColors.current

/**
 * App-wide Material3 theme in the Mountain Man Rockets brand.
 *
 * Dynamic color (Material You) is deliberately not used, so the brand holds on
 * Android 12+. Follows the system dark-mode setting.
 */
@Composable
fun RocketLocatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalMmrColors provides if (darkTheme) DarkMmrColors else LightMmrColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}
