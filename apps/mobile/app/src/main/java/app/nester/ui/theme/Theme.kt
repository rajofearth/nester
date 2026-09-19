package app.nester.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
}

object NesterColors {
    val amberText = Color(0xFF8A5C00)
    val amberBright = Color(0xFFF2C14E)
    val amberContainerDark = Color(0xFF4A3B14)
    val amberContainerLight = Color(0xFFF6EBC8)
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4CAF7D),
    onPrimary = Color(0xFF002913),
    primaryContainer = Color(0xFF1F5C3D),
    onPrimaryContainer = Color(0xFFABEBC8),
    secondary = Color(0xFFB0CCBB),
    onSecondary = Color(0xFF1B352A),
    secondaryContainer = Color(0xFF324B3F),
    onSecondaryContainer = Color(0xFFCCE8D6),
    tertiary = Color(0xFFA9C6C9),
    onTertiary = Color(0xFF10272A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101413),
    onBackground = Color(0xFFE1E5E3),
    surface = Color(0xFF101413),
    onSurface = Color(0xFFE1E5E3),
    surfaceVariant = Color(0xFF2C3230),
    onSurfaceVariant = Color(0xFFB9C1BD),
    outline = Color(0xFF79827D),
    outlineVariant = Color(0xFF3A423F),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1E6C47),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFABF0C9),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4E6355),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD1E8D7),
    onSecondaryContainer = Color(0xFF0B2016),
    tertiary = Color(0xFF3D6468),
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFAFBF9),
    onBackground = Color(0xFF181C1A),
    surface = Color(0xFFFAFBF9),
    onSurface = Color(0xFF181C1A),
    surfaceVariant = Color(0xFFDFE5E1),
    onSurfaceVariant = Color(0xFF424944),
    outline = Color(0xFF727972),
    outlineVariant = Color(0xFFC3C9C5),
)

private val NesterTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
)

@Composable
fun NesterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = NesterTypography,
        content = content,
    )
}
