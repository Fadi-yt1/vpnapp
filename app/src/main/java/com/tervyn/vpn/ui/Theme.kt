package com.tervyn.vpn.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Glass theme: deep navy with the cyan → blue → violet of the Tervyn logo. */
object C {
    val bg1 = Color(0xFF07103A)
    val bg2 = Color(0xFF02051A)

    // Frosted glass
    val card = Color(0x17FFFFFF)
    val cardHover = Color(0x24FFFFFF)
    val border = Color(0x33FFFFFF)
    val borderStrong = Color(0x59FFFFFF)

    val text = Color(0xFFF2F6FF)
    val muted = Color(0xFF9DABD8)
    val label = Color(0xFFB7C5F2)

    val cyan = Color(0xFF22D3EE)
    val accent = Color(0xFF3B82F6)
    val violet = Color(0xFF8B5CF6)
    val accentLight = Color(0xFF93DDFF)
    val accentSoft = Color(0x333B82F6)

    val green = Color(0xFF34E5A6)
    val amber = Color(0xFFFFC069)
    val red = Color(0xFFFF6B81)
    val inputBg = Color(0x14FFFFFF)

    val buttonGradient = Brush.linearGradient(listOf(cyan, accent, violet))
    val background = Brush.verticalGradient(listOf(bg1, bg2))

    /** Light at the top, almost clear at the bottom, like frosted glass catching light. */
    val glassFill = Brush.verticalGradient(listOf(Color(0x26FFFFFF), Color(0x0CFFFFFF)))
    val glassFillSelected = Brush.verticalGradient(listOf(Color(0x3322D3EE), Color(0x1A8B5CF6)))

    /** Bright top-left edge fading out, with a hint of cyan: the glass rim. */
    val glassBorder = Brush.linearGradient(listOf(Color(0x80FFFFFF), Color(0x1AFFFFFF), Color(0x5522D3EE)))
    val glassBorderSelected = Brush.linearGradient(listOf(Color(0xCC22D3EE), Color(0x403B82F6), Color(0xAA8B5CF6)))
}

@Composable
fun TervynTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent,
            onPrimary = Color.White,
            secondary = C.cyan,
            background = C.bg2,
            surface = Color(0xFF0E1846),
            onSurface = C.text,
            onBackground = C.text,
            surfaceVariant = Color(0xFF16214F),
            onSurfaceVariant = C.muted,
            surfaceContainer = Color(0xFF111B4A),
            outline = C.border,
            error = C.red,
        ),
        content = content,
    )
}
