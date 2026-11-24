package com.dinsplayer.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class ThemePreset(
    val key: String,
    val label: String,
    val argb: Int,
) {
    BLUE("blue", "Biru", 0xFF2979FF.toInt()),
    EMERALD("emerald", "Zamrud", 0xFF10B981.toInt()),
    CRIMSON("crimson", "Merah", 0xFFE53935.toInt()),
    VIOLET("violet", "Ungu", 0xFF8B5CF6.toInt()),
    AMBER("amber", "Emas", 0xFFD97706.toInt()),
    TEAL("teal", "Toska", 0xFF0891B2.toInt()),
    ROSE("rose", "Rose", 0xFFE11D48.toInt()),
    ORANGE("orange", "Oranye", 0xFFEA580C.toInt());

    companion object {
        const val CUSTOM_KEY = "custom"

        fun fromKey(key: String?): ThemePreset? =
            entries.firstOrNull { it.key == key }
    }
}

data class ThemeTokens(
    val bgDeep: Color,
    val bgBase: Color,
    val bgElevated: Color,
    val bgSurface: Color,
    val bgDialog: Color,
    val divider: Color,
    val accent: Color,
    val accentDim: Color,
    val accentSoft: Color,
    val accentFaint: Color,
    val accentBright: Color,
    val accentHighlight: Color,
)

internal val DefaultBlueTokens = ThemeTokens(
    bgDeep = Color(0xFF0B0F17),
    bgBase = Color(0xFF141A26),
    bgElevated = Color(0xD91E2636),
    bgSurface = Color(0xFF273042),
    bgDialog = Color(0xFF1C2436),
    divider = Color(0xFF333D52),
    accent = Color(0xFF2979FF),
    accentDim = Color(0xFF1C54B2),
    accentSoft = Color(0x332979FF),
    accentFaint = Color(0x1A2979FF),
    accentBright = Color(0xFF448AFF),
    accentHighlight = Color(0xFF90CAF9),
)

internal object ThemeColorMath {

    fun rgbToHsl(argb: Int): FloatArray {
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f

        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val l = (maxC + minC) / 2f
        val d = maxC - minC

        if (d < 1e-5f) {
            return floatArrayOf(0f, 0f, l)
        }

        val s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
        val hRaw = when (maxC) {
            r -> ((g - b) / d) + (if (g < b) 6f else 0f)
            g -> ((b - r) / d) + 2f
            else -> ((r - g) / d) + 4f
        }
        val h = ((hRaw * 60f) % 360f + 360f) % 360f
        return floatArrayOf(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    }

    fun hslToArgb(h: Float, s: Float, l: Float, alpha: Int = 0xFF): Int {
        val hue = ((h % 360f) + 360f) % 360f
        val sat = s.coerceIn(0f, 1f)
        val light = l.coerceIn(0f, 1f)

        val c = (1f - abs(2f * light - 1f)) * sat
        val x = c * (1f - abs((hue / 60f) % 2f - 1f))
        val m = light - c / 2f

        val (r1, g1, b1) = when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        val r = (((r1 + m) * 255f) + 0.5f).toInt().coerceIn(0, 255)
        val g = (((g1 + m) * 255f) + 0.5f).toInt().coerceIn(0, 255)
        val b = (((b1 + m) * 255f) + 0.5f).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Turunkan seluruh token tema (aksen + latar gelap selaras) dari satu warna argb.
     * Lightness aksen dibatasi agar teks putih di atas tombol dan teks berwarna di atas latar
     * gelap tetap terbaca jelas.
     */
    fun deriveTokens(seedArgb: Int): ThemeTokens {
        val hsl = rgbToHsl(seedArgb)
        val hue = hsl[0]
        val rawSat = hsl[1]
        val accentSat = if (rawSat < 0.08f) 0.12f else rawSat.coerceIn(0.45f, 0.92f)

        // Hue kuning/hijau muda punya luminansi lebih tinggi, jadi lightness aksen diturunkan sedikit
        val isHighLumHue = hue in 42f..185f
        val accentLight = if (isHighLumHue) 0.41f else 0.52f
        val accentBrightLight = if (isHighLumHue) 0.54f else 0.63f

        val accentArgb = hslToArgb(hue, accentSat, accentLight)
        val accentDimArgb = hslToArgb(hue, accentSat, (accentLight - 0.13f).coerceAtLeast(0.26f))
        val accentBrightArgb = hslToArgb(hue, accentSat.coerceAtLeast(0.55f), accentBrightLight)
        val accentHighlightArgb = hslToArgb(hue, (accentSat * 0.85f), 0.76f)

        // Latar gelap bernuansa halus mengikuti hue tema agar harmonis namun tidak terlalu pekat
        val bgSat = (rawSat * 0.34f).coerceIn(0.06f, 0.30f)
        val dividerSat = (rawSat * 0.28f).coerceIn(0.06f, 0.26f)

        val bgDeepArgb = hslToArgb(hue, bgSat, 0.068f)
        val bgBaseArgb = hslToArgb(hue, bgSat, 0.112f)
        val bgDialogArgb = hslToArgb(hue, bgSat, 0.158f)
        val bgElevatedArgb = hslToArgb(hue, bgSat, 0.168f, alpha = 0xD9)
        val bgSurfaceArgb = hslToArgb(hue, (bgSat * 0.90f), 0.205f)
        val dividerArgb = hslToArgb(hue, dividerSat, 0.258f)

        val accent = Color(accentArgb)
        return ThemeTokens(
            bgDeep = Color(bgDeepArgb),
            bgBase = Color(bgBaseArgb),
            bgElevated = Color(bgElevatedArgb),
            bgSurface = Color(bgSurfaceArgb),
            bgDialog = Color(bgDialogArgb),
            divider = Color(dividerArgb),
            accent = accent,
            accentDim = Color(accentDimArgb),
            accentSoft = accent.copy(alpha = 0.20f),
            accentFaint = accent.copy(alpha = 0.10f),
            accentBright = Color(accentBrightArgb),
            accentHighlight = Color(accentHighlightArgb),
        )
    }

    fun parseHexColor(input: String): Int? {
        val clean = input.trim().removePrefix("#").uppercase()
        if (clean.length != 6) return null
        val rgb = clean.toIntOrNull(16) ?: return null
        return (0xFF shl 24) or rgb
    }

    fun formatHex(argb: Int): String {
        val rgb = argb and 0xFFFFFF
        return "#%06X".format(rgb)
    }
}

fun resolveThemeTokens(presetKey: String?, customArgb: Int): ThemeTokens {
    if (presetKey == ThemePreset.CUSTOM_KEY) {
        return ThemeColorMath.deriveTokens(customArgb)
    }
    val preset = ThemePreset.fromKey(presetKey) ?: ThemePreset.BLUE
    return if (preset == ThemePreset.BLUE) {
        DefaultBlueTokens
    } else {
        ThemeColorMath.deriveTokens(preset.argb)
    }
}
