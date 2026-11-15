package com.tervyn.vpn.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tervyn.vpn.core.AppState
import com.tervyn.vpn.core.Core
import com.tervyn.vpn.core.Prefs

/** A miniature of the app drawn in [pal]'s colours, so each theme can be previewed. */
@Composable
fun ThemePreview(pal: GlassPalette, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.aspectRatio(0.62f).clip(shape).border(1.dp, pal.rimHi.copy(alpha = 0.6f), shape)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(pal.bg1, pal.bg2)))
            val w = size.width
            val h = size.height
            drawCircle(Brush.radialGradient(listOf(pal.glow1.copy(alpha = pal.glowAlpha * 1.4f), Color.Transparent), Offset(w * 0.15f, h * 0.15f), w * 0.9f), w * 0.9f, Offset(w * 0.15f, h * 0.15f))
            drawCircle(Brush.radialGradient(listOf(pal.glow3.copy(alpha = pal.glowAlpha * 1.2f), Color.Transparent), Offset(w * 0.9f, h * 0.85f), w * 0.9f), w * 0.9f, Offset(w * 0.9f, h * 0.85f))
            // Orb
            val c = Offset(w / 2, h * 0.3f)
            drawCircle(Brush.radialGradient(listOf(pal.accent.copy(alpha = 0.45f), Color.Transparent), c, w * 0.42f), w * 0.42f, c)
            drawCircle(pal.fillTop, w * 0.22f, c)
            drawCircle(Brush.linearGradient(listOf(pal.accent, pal.accent2), Offset(c.x - w * 0.14f, c.y - w * 0.14f), Offset(c.x + w * 0.14f, c.y + w * 0.14f)), w * 0.14f, c)
            // Cards
            fun card(top: Float, hh: Float) {
                drawRoundRect(Brush.verticalGradient(listOf(pal.fillTop, pal.fillBottom), top, top + hh),
                    Offset(w * 0.08f, top), androidx.compose.ui.geometry.Size(w * 0.84f, hh), androidx.compose.ui.geometry.CornerRadius(w * 0.07f))
                drawRoundRect(pal.rimHi.copy(alpha = pal.rimHi.alpha * 0.7f), Offset(w * 0.08f, top), androidx.compose.ui.geometry.Size(w * 0.84f, hh),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.07f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                drawRoundRect(pal.text.copy(alpha = 0.85f), Offset(w * 0.16f, top + hh * 0.3f), androidx.compose.ui.geometry.Size(w * 0.4f, hh * 0.14f), androidx.compose.ui.geometry.CornerRadius(hh))
                drawRoundRect(pal.muted.copy(alpha = 0.8f), Offset(w * 0.16f, top + hh * 0.58f), androidx.compose.ui.geometry.Size(w * 0.56f, hh * 0.11f), androidx.compose.ui.geometry.CornerRadius(hh))
            }
            card(h * 0.52f, h * 0.13f)
            card(h * 0.68f, h * 0.13f)
            // Tab bar
            drawRoundRect(Brush.verticalGradient(listOf(pal.fillTop, pal.fillBottom)), Offset(w * 0.08f, h * 0.86f),
                androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.09f), androidx.compose.ui.geometry.CornerRadius(w))
            drawCircle(pal.accent, w * 0.03f, Offset(w * 0.26f, h * 0.905f))
            drawCircle(pal.muted, w * 0.025f, Offset(w * 0.5f, h * 0.905f))
            drawCircle(pal.muted, w * 0.025f, Offset(w * 0.74f, h * 0.905f))
        }
    }
}

@Composable
fun AppearanceScreen(st: AppState, nav: Nav) {
    val p = st.prefs
    val tick = rememberTick()
    val systemDark = isSystemInDarkTheme()
    Page("Appearance", nav, sub = true, subtitle = "Liquid glass, your way") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (pal in Palettes.all) {
                val chosen = p.theme == pal.id
                Column(
                    Modifier.weight(1f).clip(ShapeInner)
                        .selectable(chosen, role = Role.RadioButton) { tick(); Core.setTheme(pal.id) }
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.fillMaxWidth()
                            .then(if (chosen) Modifier.border(2.dp, Brush.linearGradient(listOf(C.accent, C.accent2)), RoundedCornerShape(20.dp)).padding(3.dp) else Modifier.padding(3.dp)),
                    ) { ThemePreview(pal, Modifier.fillMaxWidth()) }
                    VSpace(8)
                    Text(pal.name.removeSuffix(" Glass").replace("Deep ", ""), color = if (chosen) C.text else C.muted,
                        fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Text("Glass", color = C.muted, fontSize = 11.sp)
                }
            }
        }
        VSpace(14)
        GroupCard {
            SettingRow(TIcons.palette, "Match system", if (p.theme == Prefs.THEME_SYSTEM) "On: ${if (systemDark) "Deep Purple" else "Light"} Glass, following your phone" else "Use Light Glass by day and Deep Purple Glass in dark mode",
                checked = p.theme == Prefs.THEME_SYSTEM,
                onCheckedChange = { Core.setTheme(if (it) Prefs.THEME_SYSTEM else Palettes.forTheme(Prefs.THEME_SYSTEM, systemDark).id) })
        }

        SectionLabel("Glass & motion")
        GroupCard {
            SettingRow(TIcons.layers, "Background blur",
                if (Build.VERSION.SDK_INT < 31) "Needs Android 12 or newer — a frosted look is used instead"
                else if (p.blur) "Glass blurs what's behind it" else "Off: lighter frosted glass, uses less battery",
                checked = p.blur && Build.VERSION.SDK_INT >= 31, enabled = Build.VERSION.SDK_INT >= 31, onCheckedChange = Core::setBlur)
            RowDivider()
            SettingRow(TIcons.pulse, "Reduce motion", "Stops the drifting background, orb animation and slide transitions",
                checked = p.reducedMotion, onCheckedChange = Core::setReducedMotion)
            RowDivider()
            SettingRow(TIcons.bolt, "Haptic feedback", "A light tap when you switch or select something",
                checked = p.haptics, onCheckedChange = Core::setHaptics)
        }

        SectionLabel("Preview")
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(TIcons.shield, C.green)
                HSpace(12)
                Column(Modifier.weight(1f)) {
                    Text("Glass card", color = C.text, fontWeight = FontWeight.SemiBold)
                    Muted("Text, muted text and accents in ${C.palette.name}")
                }
                GlassSwitch(true, null)
            }
            VSpace(12)
            GlassProgressBar(0.66f)
            VSpace(12)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Primary", Modifier.weight(1f)) {}
                GlassButton("Secondary", Modifier.weight(1f), ButtonStyle.Secondary) {}
            }
            VSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (c in listOf(C.accent, C.accent2, C.green, C.amber, C.red)) Box(Modifier.size(20.dp).clip(CircleShape).background(c))
            }
        }
        VSpace(4)
        Muted("Text sizes follow your phone's font size setting.", Modifier.padding(horizontal = 6.dp, vertical = 8.dp))
    }
}
