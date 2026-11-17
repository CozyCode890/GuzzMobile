package io.github.cozycode890.guzz.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Giá trị của `GUI.theme` (D111). */
object ThemeMode {
    const val AUTO = "auto"
    const val LIGHT = "light"
    const val DARK = "dark"
}

/** `GUI.theme` → giao diện tối hay không; `auto` theo hệ thống. */
@Composable
fun isDarkTheme(mode: String): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    else -> isSystemInDarkTheme()
}

/**
 * Material 3 (D111): Android 12+ màu động theo hình nền, máy cũ hơn (hoặc [dynamicColor] = false) dùng bộ màu Guzz.
 * Chủ đề tính hoàn toàn trong Compose, nên đổi Sáng / Tối không phải dựng lại Activity.
 */
@Composable
fun GuzzTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> GuzzDarkColors
        else -> GuzzLightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
