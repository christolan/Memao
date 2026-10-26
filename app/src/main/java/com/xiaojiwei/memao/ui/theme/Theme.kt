package com.xiaojiwei.memao.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** Material supplies text/selection primitives; visible components use Memao's own styling. */
@Composable
fun MemaoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (darkTheme) DarkColors else LightColors
    val scheme = if (darkTheme) darkColorScheme(
        primary = c.ink, onPrimary = c.background, secondary = c.secondary,
        background = c.background, onBackground = c.ink, surface = c.background,
        onSurface = c.ink, surfaceVariant = c.soft, onSurfaceVariant = c.secondary,
        outline = c.divider, error = c.danger,
    ) else lightColorScheme(
        primary = c.ink, onPrimary = c.background, secondary = c.secondary,
        background = c.background, onBackground = c.ink, surface = c.background,
        onSurface = c.ink, surfaceVariant = c.soft, onSurfaceVariant = c.secondary,
        outline = c.divider, error = c.danger,
    )
    CompositionLocalProvider(LocalMemaoColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}
