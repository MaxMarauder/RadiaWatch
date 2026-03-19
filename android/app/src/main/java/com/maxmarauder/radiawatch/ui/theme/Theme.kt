package com.maxmarauder.radiawatch.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RadiaWatchColorScheme = darkColorScheme(
    primary = RadiaYellow,
    onPrimary = RadiaBlack,
    primaryContainer = RadiaDarkSurface,
    onPrimaryContainer = RadiaYellow,
    secondary = RadiaYellow,
    onSecondary = RadiaBlack,
    secondaryContainer = RadiaDarkSurface,
    onSecondaryContainer = RadiaYellow,
    background = RadiaBlack,
    onBackground = RadiaYellow,
    surface = RadiaDarkSurface,
    onSurface = RadiaYellow,
    surfaceVariant = RadiaDarkSurface,
    onSurfaceVariant = RadiaDimYellow,
    error = Color(0xFFFF5555),
    onError = RadiaBlack,
    outline = RadiaDimYellow,
)

@Composable
fun RadiaWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RadiaWatchColorScheme,
        typography = Typography,
        content = content,
    )
}
