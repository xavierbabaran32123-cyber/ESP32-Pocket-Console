package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val PocketConsoleColorScheme = darkColorScheme(
    primary = ConsoleAccent,
    onPrimary = ConsoleAccentOn,
    primaryContainer = ConsoleRoyalPurple,
    onPrimaryContainer = ConsoleInk,
    secondary = ConsoleCyan,
    onSecondary = ConsoleBg,
    secondaryContainer = ConsolePanel,
    onSecondaryContainer = ConsoleInk,
    tertiary = ConsoleWarn,
    onTertiary = ConsoleBg,
    background = ConsoleBg,
    onBackground = ConsoleInk,
    surface = ConsolePanel,
    onSurface = ConsoleInk,
    surfaceVariant = ConsoleStageBg,
    onSurfaceVariant = ConsoleInkDim,
    outline = ConsolePanelHigh,
    outlineVariant = ConsolePillarBlue,
    error = ConsoleBad,
    onError = ConsoleBg
)

val PocketConsoleShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MaskMazeTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PocketConsoleColorScheme,
        typography = Typography,
        shapes = PocketConsoleShapes,
        content = content
    )
}
