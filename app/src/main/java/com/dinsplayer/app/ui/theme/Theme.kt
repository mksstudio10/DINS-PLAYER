package com.dinsplayer.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.dinsplayer.app.core.i18n.AppLanguage
import com.dinsplayer.app.core.i18n.AppLocaleState
import com.dinsplayer.app.data.KEY_APP_LANGUAGE
import com.dinsplayer.app.data.KEY_THEME_CUSTOM_ARGB
import com.dinsplayer.app.data.KEY_THEME_PRESET
import com.dinsplayer.app.data.settingsDataStore

@Composable
fun DINSPLAYERTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs by context.settingsDataStore.data.collectAsState(initial = null)
    val presetKey = prefs?.get(KEY_THEME_PRESET) ?: ThemePreset.BLUE.key
    val customArgb = prefs?.get(KEY_THEME_CUSTOM_ARGB) ?: ThemePreset.BLUE.argb
    val languageCode = prefs?.get(KEY_APP_LANGUAGE)
    val appLanguage = remember(languageCode) { AppLanguage.fromCode(languageCode) }
    val layoutDirection = if (appLanguage.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    val tokens = remember(presetKey, customArgb) {
        resolveThemeTokens(presetKey, customArgb)
    }

    SideEffect {
        if (AppThemeState.tokens != tokens) {
            AppThemeState.tokens = tokens
        }
        if (AppLocaleState.currentLanguage != appLanguage) {
            AppLocaleState.currentLanguage = appLanguage
        }
    }

    val colorScheme = remember(tokens) {
        darkColorScheme(
            primary = tokens.accent,
            onPrimary = TextOnAccent,
            primaryContainer = tokens.accentSoft,
            onPrimaryContainer = TextPrimary,
            secondary = CinemaGold,
            onSecondary = tokens.bgDeep,
            background = tokens.bgBase,
            onBackground = TextPrimary,
            surface = tokens.bgElevated,
            onSurface = TextPrimary,
            surfaceVariant = tokens.bgSurface,
            onSurfaceVariant = TextSecondary,
            outline = tokens.divider,
            error = ColorError,
            onError = Color.White,
            scrim = Color(0xCC000000),
        )
    }

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DinsTypography,
            content = content
        )
    }
}
