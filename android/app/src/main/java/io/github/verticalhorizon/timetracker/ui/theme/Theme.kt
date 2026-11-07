package io.github.verticalhorizon.timetracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Rice paper, sage and sand; dark mode is the same at dusk.
private val Light = lightColorScheme(
    primary = Color(0xFF6F8F78),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6DC),
    onPrimaryContainer = Color(0xFF2E3B31),
    secondary = Color(0xFFA08C70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9E1D3),
    onSecondaryContainer = Color(0xFF4A4034),
    background = Color(0xFFF4F1EA),
    onBackground = Color(0xFF3C3F3A),
    surface = Color(0xFFF4F1EA),
    onSurface = Color(0xFF3C3F3A),
    surfaceVariant = Color(0xFFE7E2D8),
    onSurfaceVariant = Color(0xFF7D8078),
    surfaceContainerLowest = Color(0xFFFBF9F5),
    surfaceContainerLow = Color(0xFFF8F5EF),
    surfaceContainer = Color(0xFFEDE8DF),
    surfaceContainerHigh = Color(0xFFECE7DE),
    surfaceContainerHighest = Color(0xFFE6E0D5),
    outline = Color(0xFFC9C2B5),
    outlineVariant = Color(0xFFE0D9CD),
    error = Color(0xFFAD6A5E),
    onError = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF3C3F3A),
    inverseOnSurface = Color(0xFFF4F1EA),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9FBBA6),
    onPrimary = Color(0xFF1D2B22),
    primaryContainer = Color(0xFF34453A),
    onPrimaryContainer = Color(0xFFD5E5D9),
    secondary = Color(0xFFCDB99C),
    onSecondary = Color(0xFF3A3024),
    secondaryContainer = Color(0xFF463E33),
    onSecondaryContainer = Color(0xFFEDE1CF),
    background = Color(0xFF1B1D1A),
    onBackground = Color(0xFFE3E0D8),
    surface = Color(0xFF1B1D1A),
    onSurface = Color(0xFFE3E0D8),
    surfaceVariant = Color(0xFF30332E),
    onSurfaceVariant = Color(0xFFA5A89F),
    surfaceContainerLowest = Color(0xFF161815),
    surfaceContainerLow = Color(0xFF212420),
    surfaceContainer = Color(0xFF252824),
    surfaceContainerHigh = Color(0xFF2A2D29),
    surfaceContainerHighest = Color(0xFF30332E),
    outline = Color(0xFF4F534C),
    outlineVariant = Color(0xFF383C36),
    error = Color(0xFFD9958A),
    onError = Color(0xFF3A1D18),
    inverseSurface = Color(0xFFE3E0D8),
    inverseOnSurface = Color(0xFF1B1D1A),
)

/** Category colors: muted, and readable on both backgrounds. */
val CategoryColors = listOf(
    Color(0xFF8FAE96), // sage
    Color(0xFF8FA9BF), // mist
    Color(0xFFCDB48F), // sand
    Color(0xFFC99A8B), // clay
    Color(0xFFA99BC2), // lavender
    Color(0xFF9AA67A), // moss
    Color(0xFFC9A0AA), // rose
    Color(0xFFA39E93), // stone
)

fun categoryColor(index: Int): Color = CategoryColors[Math.floorMod(index, CategoryColors.size)]

private val Base = Typography()

private val ZenTypography = Base.copy(
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.Light, fontFeatureSettings = "tnum"),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Light),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Light),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Light),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Normal),
    labelSmall = Base.labelSmall.copy(letterSpacing = 1.4.sp),
)

private val ZenShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun ZenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = ZenTypography,
        shapes = ZenShapes,
        content = content,
    )
}
