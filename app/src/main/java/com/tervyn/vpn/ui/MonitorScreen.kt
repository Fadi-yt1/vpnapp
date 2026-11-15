package com.tervyn.vpn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tervyn.vpn.core.AppState
import com.tervyn.vpn.core.formatBytes
import com.tervyn.vpn.core.formatRate

/** A line chart of the last minute; each series is (values, colour). */
@Composable
fun LineChart(series: List<Pair<List<Long>, Color>>, modifier: Modifier = Modifier, slots: Int = 60, description: String = "") {
    val grid = C.divider
    Canvas(modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = description }) {
        val max = series.flatMap { it.first }.maxOrNull()?.coerceAtLeast(1L)?.toFloat() ?: 1f
        // Grid
        for (i in 0..3) {
            val y = size.height * i / 3f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
        }
        val step = size.width / (slots - 1)
        for ((values, color) in series) {
            if (values.size < 2) continue
            val pts = values.takeLast(slots)
            val x0 = size.width - (pts.size - 1) * step
            val path = Path()
            pts.forEachIndexed { i, v ->
                val x = x0 + i * step
                val y = size.height - (v / max) * size.height * 0.92f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val area = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(x0, size.height); close() }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), Color.Transparent)))
            drawPath(path, color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun Legend(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            HSpace(6)
            Text(label, color = C.muted, fontSize = 12.sp)
        }
        Text(value, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = Fonts.display, maxLines = 1)
    }
}

@Composable
fun MonitorScreen(st: AppState, nav: Nav) {
    val on = st.on
    val h = st.history
    Page("Network Monitor", nav, sub = true, subtitle = if (on) "Live, measured on this phone every second" else "Connect to see live traffic") {
        GlassCard {
            Text("Throughput", color = C.text, fontWeight = FontWeight.SemiBold)
            VSpace(12)
            Row {
                Legend("Download", if (on) formatRate(st.traffic.down) else "—", C.accent, Modifier.weight(1f))
                Legend("Upload", if (on) formatRate(st.traffic.up) else "—", C.accent2, Modifier.weight(1f))
            }
            VSpace(14)
            LineChart(listOf(h.down to C.accent, h.up to C.accent2), description = "Download and upload speed over the last minute")
            VSpace(6)
            Row {
                Muted("60 s ago", Modifier.weight(1f))
                Muted("Peak ${formatRate((h.down + h.up).maxOrNull() ?: 0L)}")
            }
        }

        VSpace(12)
        GlassCard {
            Text("Latency", color = C.text, fontWeight = FontWeight.SemiBold)
            VSpace(12)
            val pings = h.ping
            Row {
                Legend("Now", st.status.ping?.takeIf { on }?.let { "$it ms" } ?: "—", pingColor(st.status.ping.takeIf { on }), Modifier.weight(1f))
                Legend("Average", if (pings.isNotEmpty()) "${pings.average().toInt()} ms" else "—", C.accent, Modifier.weight(1f))
                Legend("Best", pings.minOrNull()?.let { "$it ms" } ?: "—", C.green, Modifier.weight(1f))
            }
            VSpace(14)
            LineChart(listOf(pings.map { it.toLong() } to C.cyan), slots = 40, description = "Ping to the VPN server, measured every 15 seconds")
            VSpace(6)
            Muted("Time for a request to travel through the VPN server and back, measured every 15 seconds.")
        }

        SectionLabel("This session")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Downloaded", formatBytes(st.traffic.totalDown), TIcons.arrowDown, Modifier.weight(1f))
            StatTile("Uploaded", formatBytes(st.traffic.totalUp), TIcons.arrowUp, Modifier.weight(1f), color = C.accent2)
        }
        VSpace(10)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Server", if (on) st.status.label.ifEmpty { "—" } else "—", TIcons.servers, Modifier.weight(1f))
            StatTile("Protocol", if (on) protoName(st.status.protocol) else "—", TIcons.layers, Modifier.weight(1f))
        }

        SectionLabel("DNS")
        val d = st.dnsStats
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Lookups", "%,d".format(d.queries), TIcons.dns, Modifier.weight(1f))
            StatTile("Blocked", "%,d".format(d.blocked), TIcons.shield, Modifier.weight(1f), color = C.red)
            StatTile("Encrypted", "%,d".format(d.encrypted), TIcons.lock, Modifier.weight(1f), color = C.green)
        }
        VSpace(10)
        GhostButton("Open DNS Center", Modifier.fillMaxWidth()) { nav.open(Routes.DNS) }
    }
}
