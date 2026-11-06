package com.dinsplayer.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary          = AccentBlue,
    onPrimary        = TextOnAccent,
    primaryContainer = AccentBlueSoft,
    onPrimaryContainer = TextPrimary,
    secondary        = CinemaGold,
    onSecondary      = BgDeep,
    background       = BgBase,
    onBackground     = TextPrimary,
    surface          = BgElevated,
    onSurface        = TextPrimary,
    surfaceVariant   = BgSurface,
    onSurfaceVariant = TextSecondary,
    outline          = DividerColor,
    error            = ColorError,
    onError          = Color.White,
    scrim            = Color(0xCC000000),
)

@Composable
fun DINSPLAYERTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = DinsTypography,
        content     = content
    )
}