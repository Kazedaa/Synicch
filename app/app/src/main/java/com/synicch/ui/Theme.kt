package com.synicch.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

/**
 * Follows the system theme, dark when it cannot tell. Photos read better
 * against black and there is less competing with them.
 */
private val Dark = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF00315D),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF25262B),
    onSurfaceVariant = Color(0xFFC5C6CB),
    background = Color(0xFF0D0E10),
    onBackground = Color(0xFFE3E2E6),
    error = Color(0xFFF2B8B5),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0B57D0),
    surface = Color(0xFFFAF9FD),
    background = Color(0xFFFFFFFF),
)

@Composable
fun SynicchTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) Dark else Light
    val view = LocalContext.current

    SideEffect {
        (view as? Activity)?.window?.let { w ->
            w.statusBarColor = Color.Transparent.toArgb()
            w.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(w, w.decorView)
                .isAppearanceLightStatusBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
