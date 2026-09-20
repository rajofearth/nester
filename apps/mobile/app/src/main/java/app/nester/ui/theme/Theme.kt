package app.nester.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
}

private val MonoLight = lightColorScheme(
    primary = Color(0xFF4A4459),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6E0F0),
    onPrimaryContainer = Color(0xFF1A1626),
    secondary = Color(0xFF5F5D65),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E1E6),
    onSecondaryContainer = Color(0xFF1B1B1F),
    tertiary = Color(0xFF6B5B62),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE9E0EA),
    onTertiaryContainer = Color(0xFF1E1A22),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFCF8FD),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFCF8FD),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE4E0E5),
    onSurfaceVariant = Color(0xFF48454E),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    inverseSurface = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    inversePrimary = Color(0xFFCFC3E0),
    surfaceDim = Color(0xFFDCD6DE),
    surfaceBright = Color(0xFFFCF8FD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F1F6),
    surfaceContainer = Color(0xFFEFEBF0),
    surfaceContainerHigh = Color(0xFFE9E5EA),
    surfaceContainerHighest = Color(0xFFE4E0E5),
)

private val MonoDark = darkColorScheme(
    primary = Color(0xFFCAC3DC),
    onPrimary = Color(0xFF332D41),
    primaryContainer = Color(0xFF4A4459),
    onPrimaryContainer = Color(0xFFE7DFF8),
    secondary = Color(0xFFC8C5CD),
    onSecondary = Color(0xFF313036),
    secondaryContainer = Color(0xFF48464D),
    onSecondaryContainer = Color(0xFFE4E1EA),
    tertiary = Color(0xFFE4BAD7),
    onTertiary = Color(0xFF3E2238),
    tertiaryContainer = Color(0xFF524346),
    onTertiaryContainer = Color(0xFFF0DEE2),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF141317),
    onBackground = Color(0xFFE3E2E7),
    surface = Color(0xFF141317),
    onSurface = Color(0xFFE3E2E7),
    surfaceVariant = Color(0xFF484550),
    onSurfaceVariant = Color(0xFFC9C5D1),
    outline = Color(0xFF938F9B),
    outlineVariant = Color(0xFF484550),
    inverseSurface = Color(0xFFE3E2E7),
    inverseOnSurface = Color(0xFF313034),
    inversePrimary = Color(0xFF615B71),
    surfaceDim = Color(0xFF141317),
    surfaceBright = Color(0xFF3B3A3F),
    surfaceContainerLowest = Color(0xFF0E0E12),
    surfaceContainerLow = Color(0xFF1C1B1F),
    surfaceContainer = Color(0xFF201F23),
    surfaceContainerHigh = Color(0xFF2B292D),
    surfaceContainerHighest = Color(0xFF353438),
)

@Composable
fun dynamicScheme(darkTheme: Boolean): ColorScheme? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        null
    }

@Composable
fun NesterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = dynamicScheme(darkTheme) ?: if (darkTheme) MonoDark else MonoLight
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
