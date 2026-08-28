package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = ElegantPurple,
    onPrimary = ElegantPurpleOnPrimary,
    primaryContainer = ElegantPurpleContainer,
    onPrimaryContainer = ElegantPurpleOnContainer,
    secondary = ElegantSecondary,
    secondaryContainer = ElegantSecondaryContainer,
    onSecondaryContainer = ElegantOnSecondaryContainer,
    background = DarkBackground,
    onBackground = ElegantTextPrimary,
    surface = DarkSurface,
    onSurface = ElegantTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = ElegantTextSecondary,
    outline = DarkOutlineVariant,
    outlineVariant = DarkOutline
)

private val LightColorScheme = DarkColorScheme // Default to Elegant Dark styling

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = DarkColorScheme
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
