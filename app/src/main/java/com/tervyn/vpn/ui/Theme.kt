package com.tervyn.vpn.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.tervyn.vpn.R
import com.tervyn.vpn.core.Prefs
import dev.chrisbanes.haze.HazeState

/**
 * Tervyn's liquid glass themes. Every colour in the app comes from the active
 * [GlassPalette]; switching theme animates from one palette to the next.
 */
data class GlassPalette(
    val id: String,
    val name: String,
    val isDark: Boolean,
    val bg1: Color,             // top of the background
    val bg2: Color,             // bottom of the background
    val glow1: Color,           // soft light blobs behind the glass
    val glow2: Color,
    val glow3: Color,
    val glowAlpha: Float,
    val accent: Color,
    val accent2: Color,         // second accent for gradients
    val onAccent: Color,
    val text: Color,
    val muted: Color,
    val label: Color,
    val fillTop: Color,         // glass fill, lit from the top
    val fillBottom: Color,
    val rimHi: Color,           // bright glass edge
    val rimLo: Color,           // faint glass edge
    val tint: Color,            // colour laid over the blurred background
    val field: Color,
    val track: Color,           // switch / slider / progress track
    val divider: Color,
    val shadow: Color,
    val green: Color,
    val amber: Color,
    val red: Color,
    val cyan: Color,
)

fun GlassPalette.lerpTo(o: GlassPalette, t: Float): GlassPalette = if (t >= 1f) o else if (t <= 0f) this else copy(
    id = o.id, name = o.name, isDark = if (t < 0.5f) isDark else o.isDark,
    bg1 = lerp(bg1, o.bg1, t), bg2 = lerp(bg2, o.bg2, t),
    glow1 = lerp(glow1, o.glow1, t), glow2 = lerp(glow2, o.glow2, t), glow3 = lerp(glow3, o.glow3, t),
    glowAlpha = glowAlpha + (o.glowAlpha - glowAlpha) * t,
    accent = lerp(accent, o.accent, t), accent2 = lerp(accent2, o.accent2, t), onAccent = lerp(onAccent, o.onAccent, t),
    text = lerp(text, o.text, t), muted = lerp(muted, o.muted, t), label = lerp(label, o.label, t),
    fillTop = lerp(fillTop, o.fillTop, t), fillBottom = lerp(fillBottom, o.fillBottom, t),
    rimHi = lerp(rimHi, o.rimHi, t), rimLo = lerp(rimLo, o.rimLo, t), tint = lerp(tint, o.tint, t),
    field = lerp(field, o.field, t), track = lerp(track, o.track, t), divider = lerp(divider, o.divider, t),
    shadow = lerp(shadow, o.shadow, t), green = lerp(green, o.green, t), amber = lerp(amber, o.amber, t),
    red = lerp(red, o.red, t), cyan = lerp(cyan, o.cyan, t),
)

object Palettes {
    val light = GlassPalette(
        id = Prefs.THEME_LIGHT, name = "Light Glass", isDark = false,
        bg1 = Color(0xFFF6F7FC), bg2 = Color(0xFFF2F4FA),
        glow1 = Color(0xFFB9A9FF), glow2 = Color(0xFF9FD8FF), glow3 = Color(0xFFFFC2E6), glowAlpha = 0.55f,
        accent = Color(0xFF8870FF), accent2 = Color(0xFF5E8BFF), onAccent = Color.White,
        text = Color(0xFF171725), muted = Color(0xFF77798D), label = Color(0xFF5E6075),
        fillTop = Color(0xA6FFFFFF), fillBottom = Color(0x66FFFFFF),
        rimHi = Color(0xF2FFFFFF), rimLo = Color(0x1F2A2350), tint = Color(0x59FFFFFF),
        field = Color(0x80FFFFFF), track = Color(0x1F171725), divider = Color(0x14171725), shadow = Color(0x1A3A2C8C),
        green = Color(0xFF16A874), amber = Color(0xFFD68A00), red = Color(0xFFE5484D), cyan = Color(0xFF0EA5C6),
    )
    val dark = GlassPalette(
        id = Prefs.THEME_DARK, name = "Dark Glass", isDark = true,
        bg1 = Color(0xFF111320), bg2 = Color(0xFF080910),
        glow1 = Color(0xFF6B5BFF), glow2 = Color(0xFF2E9BFF), glow3 = Color(0xFFB05BFF), glowAlpha = 0.30f,
        accent = Color(0xFF8B79FF), accent2 = Color(0xFF5B8CFF), onAccent = Color.White,
        text = Color(0xFFFFFFFF), muted = Color(0xFFA7A9B8), label = Color(0xFFC3C5D3),
        fillTop = Color(0x1FFFFFFF), fillBottom = Color(0x0AFFFFFF),
        rimHi = Color(0x52FFFFFF), rimLo = Color(0x0FFFFFFF), tint = Color(0x330C0E18),
        field = Color(0x14FFFFFF), track = Color(0x24FFFFFF), divider = Color(0x14FFFFFF), shadow = Color(0x66000000),
        green = Color(0xFF3DDC97), amber = Color(0xFFFFC069), red = Color(0xFFFF6B7A), cyan = Color(0xFF5CD6FF),
    )
    val purple = GlassPalette(
        id = Prefs.THEME_PURPLE, name = "Deep Purple Glass", isDark = true,
        bg1 = Color(0xFF21113D), bg2 = Color(0xFF160B2B),
        glow1 = Color(0xFFA77BFF), glow2 = Color(0xFFFF6FD8), glow3 = Color(0xFF5B3BFF), glowAlpha = 0.38f,
        accent = Color(0xFFA77BFF), accent2 = Color(0xFFD2B5FF), onAccent = Color(0xFF1A0B33),
        text = Color(0xFFFFFFFF), muted = Color(0xFFC6B5DC), label = Color(0xFFDCCDF0),
        fillTop = Color(0x24E9DCFF), fillBottom = Color(0x0DD2B5FF),
        rimHi = Color(0x66E7D9FF), rimLo = Color(0x14D2B5FF), tint = Color(0x33201040),
        field = Color(0x1AD2B5FF), track = Color(0x2ED2B5FF), divider = Color(0x1AD2B5FF), shadow = Color(0x80090312),
        green = Color(0xFF4BE3A4), amber = Color(0xFFFFC56E), red = Color(0xFFFF7A93), cyan = Color(0xFF7FD8FF),
    )
    val all = listOf(light, dark, purple)

    fun forTheme(theme: String, systemDark: Boolean): GlassPalette = when (theme) {
        Prefs.THEME_LIGHT -> light
        Prefs.THEME_DARK -> dark
        Prefs.THEME_SYSTEM -> if (systemDark) purple else light
        else -> purple
    }
}

/**
 * The active palette. Read anywhere as C.text, C.accent… — reads are snapshot
 * state, so the whole UI recolours (and animates) when the theme changes.
 */
object C {
    var palette by mutableStateOf(Palettes.purple)
        internal set

    val isDark get() = palette.isDark
    val bg1 get() = palette.bg1
    val bg2 get() = palette.bg2
    val text get() = palette.text
    val muted get() = palette.muted
    val label get() = palette.label
    val accent get() = palette.accent
    val accent2 get() = palette.accent2
    val onAccent get() = palette.onAccent
    val violet get() = palette.accent2
    val cyan get() = palette.cyan
    val green get() = palette.green
    val amber get() = palette.amber
    val red get() = palette.red
    val border get() = palette.rimLo
    val divider get() = palette.divider
    val track get() = palette.track
    val inputBg get() = palette.field
    val accentLight get() = if (palette.isDark) palette.accent2 else palette.accent
    val accentSoft get() = palette.accent.copy(alpha = 0.18f)

    val buttonGradient: Brush get() = Brush.linearGradient(listOf(palette.accent, palette.accent2))
    val background: Brush get() = Brush.verticalGradient(listOf(palette.bg1, palette.bg2))
    val glassFill: Brush get() = Brush.verticalGradient(listOf(palette.fillTop, palette.fillBottom))
    val glassFillSelected: Brush
        get() = Brush.verticalGradient(listOf(palette.accent.copy(alpha = if (palette.isDark) 0.34f else 0.22f), palette.accent2.copy(alpha = 0.12f)))
    val glassBorder: Brush get() = Brush.linearGradient(listOf(palette.rimHi, palette.rimLo, palette.rimLo, palette.rimHi.copy(alpha = palette.rimHi.alpha * 0.5f)))
    val glassBorderSelected: Brush get() = Brush.linearGradient(listOf(palette.accent2.copy(alpha = 0.9f), palette.accent.copy(alpha = 0.35f), palette.accent.copy(alpha = 0.8f)))
}

@OptIn(ExperimentalTextApi::class)
object Fonts {
    private fun inter(w: Int) = Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    private fun sora(w: Int) = Font(R.font.sora, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

    /** Body text: Inter (SIL Open Font License). */
    val body = FontFamily(inter(400), inter(500), inter(600), inter(700))

    /** Headings and numbers: Sora (SIL Open Font License). */
    val display = FontFamily(sora(400), sora(600), sora(700), sora(800))
}

/** Glass settings for the current screen: the blurred background and motion preference. */
class GlassEnv(val haze: HazeState?, val blur: Boolean, val reducedMotion: Boolean, val haptics: Boolean)

val LocalGlass = staticCompositionLocalOf { GlassEnv(null, blur = false, reducedMotion = false, haptics = true) }

/**
 * Applies the palette for [theme] (animated when it changes), fonts and the
 * Material colours used by menus and dialogs.
 */
@Composable
fun TervynTheme(
    theme: String,
    systemDark: Boolean,
    reducedMotion: Boolean = false,
    env: GlassEnv = GlassEnv(null, false, reducedMotion, true),
    content: @Composable () -> Unit,
) {
    val target = Palettes.forTheme(theme, systemDark)
    // First frame: no animation.
    remember { C.palette = target; 0 }
    LaunchedEffect(target) {
        val from = C.palette
        if (from == target) return@LaunchedEffect
        if (reducedMotion) { C.palette = target; return@LaunchedEffect }
        animate(0f, 1f, animationSpec = tween(520)) { v, _ -> C.palette = from.lerpTo(target, v) }
        C.palette = target
    }
    val p = C.palette
    val scheme = if (p.isDark) darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, secondary = p.accent2, background = p.bg2, surface = p.bg1,
        onSurface = p.text, onBackground = p.text, surfaceVariant = p.bg1, onSurfaceVariant = p.muted,
        surfaceContainer = p.bg1, outline = p.rimLo, error = p.red,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent, secondary = p.accent2, background = p.bg2, surface = Color.White,
        onSurface = p.text, onBackground = p.text, surfaceVariant = p.bg1, onSurfaceVariant = p.muted,
        surfaceContainer = Color(0xFFF8F8FD), outline = p.rimLo, error = p.red,
    )
    val base = Typography()
    fun TextStyle.b() = copy(fontFamily = Fonts.body)
    fun TextStyle.d() = copy(fontFamily = Fonts.display)
    val type = Typography(
        displayLarge = base.displayLarge.d(), displayMedium = base.displayMedium.d(), displaySmall = base.displaySmall.d(),
        headlineLarge = base.headlineLarge.d(), headlineMedium = base.headlineMedium.d(), headlineSmall = base.headlineSmall.d(),
        titleLarge = base.titleLarge.d(), titleMedium = base.titleMedium.b(), titleSmall = base.titleSmall.b(),
        bodyLarge = base.bodyLarge.b(), bodyMedium = base.bodyMedium.b(), bodySmall = base.bodySmall.b(),
        labelLarge = base.labelLarge.b(), labelMedium = base.labelMedium.b(), labelSmall = base.labelSmall.b(),
    )
    CompositionLocalProvider(LocalGlass provides env) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
