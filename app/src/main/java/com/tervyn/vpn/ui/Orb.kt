package com.tervyn.vpn.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class OrbState { Off, Connecting, On, Reconnecting, Failed }

/**
 * The connect button: a glass sphere with orbiting rings. Off: still and dim.
 * Connecting: rings spin fast and [progress] (0..1, when known) fills the
 * outer arc. On: gentle breathing and waves rippling outwards. Reconnecting:
 * amber, spinning. Failed: red.
 */
@Composable
fun GlassConnectionOrb(
    state: OrbState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    size: Dp = 260.dp,
) {
    val reduced = LocalGlass.current.reducedMotion
    val tick = rememberTick()
    val t = rememberInfiniteTransition(label = "orb")
    val busy = state == OrbState.Connecting || state == OrbState.Reconnecting
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (busy) 1400 else 14000, easing = LinearEasing)), label = "spin")
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "wave")

    val color by animateColorAsState(
        when (state) {
            OrbState.On -> C.accent
            OrbState.Connecting -> C.accent2
            OrbState.Reconnecting -> C.amber
            OrbState.Failed -> C.red
            OrbState.Off -> C.muted
        }, tween(600), label = "color",
    )
    val energy by animateFloatAsState(
        when (state) { OrbState.On -> 1f; OrbState.Connecting, OrbState.Reconnecting -> 0.75f; OrbState.Failed -> 0.6f; OrbState.Off -> 0.25f },
        tween(800), label = "energy",
    )
    val shownProgress by animateFloatAsState(progress ?: 0f, tween(500), label = "progress")
    val a = if (reduced) 30f else spin
    val b = if (reduced) 0.5f else breath
    val w = if (reduced) 0.5f else wave
    val p = C.palette

    Box(
        modifier.size(size).semantics {
            role = Role.Button
            stateDescription = when (state) {
                OrbState.On -> "Connected"; OrbState.Connecting -> "Connecting"; OrbState.Reconnecting -> "Reconnecting"
                OrbState.Failed -> "Connection failed"; OrbState.Off -> "Disconnected"
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = this.size.minDimension / 2

            // Halo
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = 0.42f * energy * (0.8f + 0.2f * b)), Color.Transparent), c, r),
                r, c,
            )
            // Waves rippling outwards while connected
            if (state == OrbState.On && !reduced) {
                for (i in 0..2) {
                    val f = (w + i / 3f) % 1f
                    drawCircle(color.copy(alpha = (1f - f) * 0.35f), r * (0.52f + 0.46f * f), c, style = Stroke(1.5.dp.toPx()))
                }
            }
            // Outer ring: progress while connecting, faint track otherwise
            val ringR = r * 0.86f
            drawCircle(p.rimHi.copy(alpha = 0.18f), ringR, c, style = Stroke(1.dp.toPx()))
            if (state == OrbState.Connecting && progress != null) {
                drawArc(
                    Brush.sweepGradient(listOf(p.accent, p.accent2, p.accent), c), -90f, 360f * shownProgress, false,
                    topLeft = Offset(c.x - ringR, c.y - ringR), size = androidx.compose.ui.geometry.Size(ringR * 2, ringR * 2),
                    style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            // Two orbiting rings
            val r1 = r * 0.76f
            rotate(a, c) {
                drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, color.copy(alpha = 0.9f), p.accent2, Color.Transparent), c),
                    0f, 300f, false, topLeft = Offset(c.x - r1, c.y - r1), size = androidx.compose.ui.geometry.Size(r1 * 2, r1 * 2),
                    style = Stroke((2f + 2.5f * energy).dp.toPx(), cap = StrokeCap.Round),
                )
            }
            val r2 = r * 0.68f
            rotate(-a * 1.6f + 120f, c) {
                drawArc(
                    color.copy(alpha = 0.35f + 0.4f * energy), 0f, 110f, false,
                    topLeft = Offset(c.x - r2, c.y - r2), size = androidx.compose.ui.geometry.Size(r2 * 2, r2 * 2),
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
                )
                // Orbiting light
                drawCircle(Color.White.copy(alpha = 0.9f * energy), 3.dp.toPx(), Offset(c.x + r2, c.y))
            }
        }
        // The glass sphere
        val sphereScale = if (state == OrbState.On && !reduced) 1f + 0.025f * b else 1f
        Box(
            Modifier.size(size * 0.56f).scale(sphereScale).glass(CircleShape, selected = state == OrbState.On)
                .clickable(remember { MutableInteractionSource() }, null, onClickLabel = if (state == OrbState.Off || state == OrbState.Failed) "Connect" else "Disconnect") {
                    tick(); onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            // Inner core
            Box(
                Modifier.size(size * 0.38f).clip(CircleShape)
                    .background(
                        if (state == OrbState.On) Brush.linearGradient(listOf(p.accent, p.accent2))
                        else Brush.radialGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.08f))),
                    )
                    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.05f))), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(TIcons.power, null, tint = if (state == OrbState.On) p.onAccent else C.text, modifier = Modifier.size(size * 0.16f))
            }
        }
    }
}
