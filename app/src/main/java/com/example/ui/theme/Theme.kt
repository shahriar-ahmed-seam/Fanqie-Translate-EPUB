package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val TomatoLightColorScheme = lightColorScheme(
    primary = TomatoRedPrimary,
    onPrimary = TomatoRedOnPrimary,
    primaryContainer = TomatoRedPrimaryContainer,
    onPrimaryContainer = TomatoRedOnPrimaryContainer,
    secondary = TomatoRedSecondary,
    onSecondary = TomatoRedOnSecondary,
    secondaryContainer = TomatoRedSecondaryContainer,
    onSecondaryContainer = TomatoRedOnSecondaryContainer,
    tertiary = TomatoRedTertiary,
    onTertiary = TomatoRedOnTertiary,
    tertiaryContainer = TomatoRedTertiaryContainer,
    onTertiaryContainer = TomatoRedOnTertiaryContainer,
    background = TomatoLightBackground,
    onBackground = TomatoLightOnBackground,
    surface = TomatoLightSurface,
    onSurface = TomatoLightOnSurface,
    surfaceVariant = TomatoLightSurfaceVariant,
    onSurfaceVariant = TomatoLightOnSurfaceVariant,
    outline = TomatoLightOutline,
    outlineVariant = TomatoLightOutlineVariant
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkTomatoPrimary,
    onPrimary = DarkTomatoOnPrimary,
    primaryContainer = DarkTomatoPrimaryContainer,
    onPrimaryContainer = DarkTomatoOnPrimaryContainer,
    secondary = DarkTomatoSecondary,
    onSecondary = DarkTomatoOnSecondary,
    secondaryContainer = DarkTomatoSecondaryContainer,
    onSecondaryContainer = DarkTomatoOnSecondaryContainer,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else TomatoLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

