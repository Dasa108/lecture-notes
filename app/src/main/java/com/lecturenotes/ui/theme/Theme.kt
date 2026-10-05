package com.lecturenotes.ui.theme

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

private val Light = lightColorScheme(
    primary = Color(0xFF2B5C8A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFFB2641A),
    secondaryContainer = Color(0xFFFFDCC2),
    background = Color(0xFFFBF9F5),
    surface = Color(0xFFFBF9F5),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA2C9FF),
    onPrimary = Color(0xFF00315C),
    primaryContainer = Color(0xFF0F4879),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFFFB77C),
    secondaryContainer = Color(0xFF6E3900),
    background = Color(0xFF121416),
    surface = Color(0xFF121416),
    error = Color(0xFFFFB4AB),
)

@Composable
fun LectureNotesTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, content = content)
}
