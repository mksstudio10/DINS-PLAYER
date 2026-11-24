package com.dinsplayer.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

internal object AppThemeState {
    var tokens by mutableStateOf(DefaultBlueTokens)
}

// Background layers - mengikuti nuansa warna tema pilihan pengguna
val BgDeep: Color get() = AppThemeState.tokens.bgDeep
val BgBase: Color get() = AppThemeState.tokens.bgBase
val BgElevated: Color get() = AppThemeState.tokens.bgElevated
val BgSurface: Color get() = AppThemeState.tokens.bgSurface
val BgGlass: Color = Color(0x24FFFFFF)

// Brand accent - dinamis sesuai warna tema pilihan pengguna
val AccentBlue: Color get() = AppThemeState.tokens.accent
val AccentBlueDim: Color get() = AppThemeState.tokens.accentDim
val AccentBlueSoft: Color get() = AppThemeState.tokens.accentSoft
val AccentBlueFaint: Color get() = AppThemeState.tokens.accentFaint
val ElectricBlue: Color get() = AppThemeState.tokens.accentBright
val ElectricBlueDark: Color get() = AppThemeState.tokens.accent
val AccentHighlight: Color get() = AppThemeState.tokens.accentHighlight

// Dialog / overlay panel
val BgDialog: Color get() = AppThemeState.tokens.bgDialog

// Frosted glass tokens (minimal use)
val GlassCardBg      = Color(0x14FFFFFF)
val GlassCardBorder  = Color(0x24FFFFFF)
val GlassPillBg      = Color(0x12FFFFFF)
val GlassPillBorder  = Color(0x22FFFFFF)

// Cinematic highlight
val CinemaGold   = Color(0xFFF59E0B)

// Text
val TextPrimary   = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFC4CAD4)
val TextMuted     = Color(0xFF8C96A6)
val TextOnAccent  = Color(0xFFFFFFFF)

// State colors
val ColorSuccess = Color(0xFF4CAF50)
val ColorError   = Color(0xFFE53935)
val ColorWarning = Color(0xFFFFB300)

// Divider / border
val DividerColor: Color get() = AppThemeState.tokens.divider

// Bottom nav
val NavSelected: Color get() = AppThemeState.tokens.accentBright
val NavUnselected = Color(0xFF94A0B4)
