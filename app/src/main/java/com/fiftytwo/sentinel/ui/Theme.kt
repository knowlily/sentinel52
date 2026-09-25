package com.fiftytwo.sentinel.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AA6FF),
    onPrimary = Color(0xFF10131A),
    primaryContainer = Color(0xFF28304A),
    onPrimaryContainer = Color(0xFFD7E0FF),
    secondary = Color(0xFF54D6C4),
    onSecondary = Color(0xFF06201C),
    secondaryContainer = Color(0xFF1C4B45),
    onSecondaryContainer = Color(0xFFB6F0E6),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF2A0B0B),
    errorContainer = Color(0xFF4A2323),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1116),
    onBackground = Color(0xFFE6E8EF),
    surface = Color(0xFF171A21),
    onSurface = Color(0xFFE6E8EF),
    surfaceVariant = Color(0xFF232733),
    onSurfaceVariant = Color(0xFFB9BFCF),
    outline = Color(0xFF3A4050),
    outlineVariant = Color(0xFF262B37),
)

/**
 * 浅色配色。系统处于浅色模式时用这一套——深色那套已经定型，浅色不能只覆盖 primary/secondary
 * 就交给 Material 的默认值，否则卡片底色会和窗口底色（见 themes.xml）打架。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF3A5CCC),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE1FF),
    onPrimaryContainer = Color(0xFF001453),
    secondary = Color(0xFF0B6E60),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB6F0E6),
    onSecondaryContainer = Color(0xFF002019),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF1F2F7),
    onBackground = Color(0xFF1A1C22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C22),
    surfaceVariant = Color(0xFFE7E9F2),
    onSurfaceVariant = Color(0xFF454A58),
    outline = Color(0xFFC3C7D4),
    outlineVariant = Color(0xFFE0E3EC),
)

@Composable
fun SentinelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
