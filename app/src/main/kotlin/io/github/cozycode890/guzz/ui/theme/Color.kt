package io.github.cozycode890.guzz.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Bộ màu Guzz dự phòng (D111) cho máy dưới Android 12 (không có màu động) và cho bản debug khi tắt màu động. Sinh từ màu
 * nhấn mặc định của desktop `#009faa` (`DEFAULT_ACCENT` ở gui/pages/settings.py) theo cách của Material Theme Builder.
 */
val GuzzLightColors = lightColorScheme(
    primary = Color(0xFF006970),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9DF0F8),
    onPrimaryContainer = Color(0xFF002023),
    secondary = Color(0xFF4A6366),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8EB),
    onSecondaryContainer = Color(0xFF051F22),
    tertiary = Color(0xFF4E5F7D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD6E3FF),
    onTertiaryContainer = Color(0xFF091B36),
    background = Color(0xFFFAFDFD),
    onBackground = Color(0xFF191C1D),
    surface = Color(0xFFFAFDFD),
    onSurface = Color(0xFF191C1D),
    surfaceVariant = Color(0xFFDAE4E5),
    onSurfaceVariant = Color(0xFF3F484A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F7F7),
    surfaceContainer = Color(0xFFEEF1F1),
    surfaceContainerHigh = Color(0xFFE8EBEB),
    surfaceContainerHighest = Color(0xFFE3E6E6),
    outline = Color(0xFF6F797A),
    outlineVariant = Color(0xFFBEC8C9),
)

val GuzzDarkColors = darkColorScheme(
    primary = Color(0xFF80D4DC),
    onPrimary = Color(0xFF00363B),
    primaryContainer = Color(0xFF004F55),
    onPrimaryContainer = Color(0xFF9DF0F8),
    secondary = Color(0xFFB1CCCF),
    onSecondary = Color(0xFF1C3437),
    secondaryContainer = Color(0xFF324B4E),
    onSecondaryContainer = Color(0xFFCCE8EB),
    tertiary = Color(0xFFB6C7EA),
    onTertiary = Color(0xFF20314C),
    tertiaryContainer = Color(0xFF374764),
    onTertiaryContainer = Color(0xFFD6E3FF),
    background = Color(0xFF191C1D),
    onBackground = Color(0xFFE0E3E3),
    surface = Color(0xFF191C1D),
    onSurface = Color(0xFFE0E3E3),
    surfaceVariant = Color(0xFF3F484A),
    onSurfaceVariant = Color(0xFFBEC8C9),
    surfaceContainerLowest = Color(0xFF0B0F0F),
    surfaceContainerLow = Color(0xFF191C1D),
    surfaceContainer = Color(0xFF1D2021),
    surfaceContainerHigh = Color(0xFF272B2B),
    surfaceContainerHighest = Color(0xFF323536),
    outline = Color(0xFF899294),
    outlineVariant = Color(0xFF3F484A),
)
