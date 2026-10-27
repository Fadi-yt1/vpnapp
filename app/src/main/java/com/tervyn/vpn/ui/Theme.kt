package com.tervyn.vpn.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Same palette as the browser extension. */
object C {
    val bg1 = Color(0xFF0D1033)
    val bg2 = Color(0xFF080A1F)
    val card = Color(0x09FFFFFF)
    val cardHover = Color(0x10FFFFFF)
    val border = Color(0x2496A0FF)
    val borderStrong = Color(0x4296A0FF)
    val text = Color(0xFFECEEFF)
    val muted = Color(0xFF8E94BF)
    val label = Color(0xFF9FA6D6)
    val accent = Color(0xFF7B5CFF)
    val accentLight = Color(0xFFB9A8FF)
    val accentSoft = Color(0x2E7B5CFF)
    val green = Color(0xFF3EE0A1)
    val amber = Color(0xFFF5B451)
    val red = Color(0xFFFF6B7D)
    val inputBg = Color(0x40000000)

    val buttonGradient = Brush.verticalGradient(listOf(Color(0xFF8366FF), Color(0xFF6243F2)))
    val background = Brush.verticalGradient(listOf(bg1, bg2))
}

@Composable
fun TervynTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent,
            onPrimary = Color.White,
            secondary = C.accentLight,
            background = C.bg2,
            surface = C.bg1,
            onSurface = C.text,
            onBackground = C.text,
            surfaceVariant = Color(0xFF151938),
            onSurfaceVariant = C.muted,
            outline = C.border,
            error = C.red,
        ),
        content = content,
    )
}
