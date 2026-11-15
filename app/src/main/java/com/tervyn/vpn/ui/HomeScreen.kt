package com.tervyn.vpn.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tervyn.vpn.core.AppState
import com.tervyn.vpn.core.Core
import com.tervyn.vpn.core.Prefs
import com.tervyn.vpn.core.countryName
import com.tervyn.vpn.core.formatBytes
import com.tervyn.vpn.core.formatMbps
import com.tervyn.vpn.core.formatRate
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(st: AppState, onPower: () -> Unit, goTo: (Int) -> Unit) {
    val prefs = st.prefs
    val on = st.on
    val busy = st.connecting
    val fs = st.freeStatus
    val activeId = if (on) st.status.serverIds.firstOrNull() else prefs.selected.takeIf { it != "auto" }
    val active = activeId?.let { id -> st.servers.firstOrNull { it.id == id } }
    val place = active?.country?.takeIf { it != "Unknown" } ?: ""

    // Connection timer, ticking once a second while connected.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(on) {
        while (on) { now = System.currentTimeMillis(); delay(1000) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VSpace(6)
        PowerOrb(on = on && !busy, busy = busy, onClick = onPower)

        Text(
            when {
                busy -> if (fs.busy) "Finding fast servers…" else "Connecting…"
                on -> if (prefs.targeted) "Your sites are protected" else "Connected"
                else -> "Tap to connect"
            },
            color = C.text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
        )
        VSpace(4)
        if (on && st.status.since > 0) {
            val secs = ((now - st.status.since) / 1000).coerceAtLeast(0)
            Text(
                "%02d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60),
                color = C.cyan, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
            )
        } else {
            Muted(
                when {
                    busy && fs.state == "testing" -> "Tested ${fs.progress}% · ${fs.working} working found"
                    busy && fs.state == "speed" -> "Measuring speed of the best servers…"
                    fs.state == "loading" -> "Finding free servers…"
                    fs.state == "testing" -> "Testing servers… ${fs.progress}%"
                    st.servers.any { it.ms != null } -> "${st.servers.count { it.ms != null }} fast servers ready"
                    else -> "Free servers are found automatically"
                },
                center = true,
            )
        }

        if (st.status.error.isNotEmpty()) Alert(st.status.error, onClose = Core::clearError)
        if (on && st.status.warning.isNotEmpty()) Alert(st.status.warning, warn = true, onClose = Core::clearError)

        // Location card
        VSpace(18)
        CardRow(onClick = { goTo(1) }) {
            FlagBadge(active?.flag, 44)
            Column(Modifier.weight(1f)) {
                Text(
                    if (active == null) "Fastest location" else place.ifEmpty { active.name },
                    color = C.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        active == null && !prefs.autoSwitch -> "Stays on the fastest server found when you connect"
                        active == null -> "Smart pick · " + when (prefs.source) {
                            Prefs.SOURCE_PROXIO -> "Proxio.io servers"
                            Prefs.SOURCE_BOTH -> "Proxio.io + free servers"
                            Prefs.SOURCE_OWN -> "your servers"
                            Prefs.SOURCE_CUSTOM -> "integration proxies"
                            Prefs.SOURCE_LIST -> "your proxy list"
                            else -> "free servers"
                        } + (if (prefs.country.isNotEmpty()) " in ${countryName(prefs.country)}" else "")
                        on -> st.status.label
                        else -> serverSub(active)
                    },
                    color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (active?.ms != null) {
                Column(horizontalAlignment = Alignment.End) {
                    PingBadge(active.ms, true)
                    active.mbps?.let { Text(formatMbps(it), color = C.accentLight, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            Icon(TIcons.chevron, null, tint = C.muted, modifier = Modifier.size(16.dp))
        }

        // Live speed
        if (prefs.showSpeed) {
            VSpace(12)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SpeedTile("Download", "↓", if (on) formatRate(st.traffic.down) else "—", C.cyan, Modifier.weight(1f))
                SpeedTile("Upload", "↑", if (on) formatRate(st.traffic.up) else "—", C.violet, Modifier.weight(1f))
            }
            if (on) {
                VSpace(6)
                Muted("Used this session: ${formatBytes(st.traffic.totalDown + st.traffic.totalUp)}", Modifier.fillMaxWidth(), center = true)
            }
        }

        SectionLabel("Protect")
        Segmented(
            listOf(Prefs.MODE_FULL to "All apps", Prefs.MODE_TARGETED to "Only my sites"),
            prefs.mode,
        ) { mode ->
            Core.setMode(mode)
            if (mode == Prefs.MODE_TARGETED && prefs.sites.isEmpty()) goTo(2)
        }

        if (prefs.targeted) {
            SectionLabel("Protected sites") {
                Text(
                    if (prefs.sites.size > 3) "+${prefs.sites.size - 3} more · Manage" else "Add / manage",
                    color = C.cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).clickable { goTo(2) }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (prefs.sites.isEmpty()) {
                Card(onClick = { goTo(2) }) { Text("No sites yet — tap to add sites to protect", color = C.accentLight, fontSize = 13.sp) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (d in prefs.sites.take(3)) SiteRow(d) { Core.removeSites(listOf(d)) }
            }
        }

        SectionLabel("Location")
        CountryPicker(prefs.country, countryList(st), "Fastest available", Core::setCountry)
        VSpace(10)
        ToggleRow(
            "Auto-switch to fastest server",
            if (prefs.autoSwitch) "On: uses the fastest server and switches automatically if it slows down or stops working."
            else "Off: stays on the selected server and never switches.",
            prefs.autoSwitch, Core::setAutoSwitch,
        )

        VSpace(10)
        CardRow(onClick = Core::checkIp) {
            Icon(TIcons.lock, null, tint = if (on) C.green else C.muted, modifier = Modifier.size(18.dp))
            Text(
                st.ipText.ifEmpty { if (on) "Encrypted connection · tap to check your IP" else "Not connected · tap to check your IP" },
                color = C.muted, fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            Icon(TIcons.chevron, null, tint = C.muted, modifier = Modifier.size(16.dp))
        }
        VSpace(16)
    }
}

/** The big connect button: glass disc, spinning gradient ring, glowing halo when connected. */
@Composable
private fun PowerOrb(on: Boolean, busy: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (busy) 1300 else 9000, easing = LinearEasing)), label = "spin")
    val pulse by t.animateFloat(1f, 1.07f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "pulse")
    val active = on || busy
    Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().scale(if (active) pulse else 1f)) {
            drawCircle(
                Brush.radialGradient(listOf((if (on) C.cyan else C.accent).copy(alpha = if (on) 0.45f else if (busy) 0.3f else 0.16f), Color.Transparent)),
                radius = size.minDimension / 2,
            )
        }
        Canvas(Modifier.size(206.dp).rotate(spin).alpha(if (active) 1f else 0.5f)) {
            drawCircle(
                Brush.sweepGradient(listOf(C.cyan, C.accent, C.violet, Color.Transparent, C.cyan)),
                radius = size.minDimension / 2 - 4.dp.toPx(),
                style = Stroke(width = (if (active) 5.dp else 2.dp).toPx(), cap = StrokeCap.Round),
            )
        }
        Box(
            Modifier.size(180.dp).glass(CircleShape, selected = on).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(128.dp).clip(CircleShape)
                    .background(if (on) Brush.linearGradient(listOf(C.cyan, C.accent, C.violet)) else C.glassFill)
                    .border(1.dp, C.glassBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(TIcons.power, if (on) "Disconnect" else "Connect", tint = if (on) Color.White else C.text, modifier = Modifier.size(56.dp))
            }
        }
    }
}

@Composable
private fun SpeedTile(label: String, arrow: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.glass(RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Text(arrow, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            HSpace(8)
            Text(label, color = C.muted, fontSize = 12.sp)
        }
        VSpace(8)
        Text(value, color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun SiteRow(domain: String, onRemove: () -> Unit) {
    CardRow {
        Box(Modifier.size(32.dp).glass(CircleShape, selected = true), contentAlignment = Alignment.Center) {
            Icon(TIcons.globe, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Text(domain, color = C.text, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(
            TIcons.close, "Remove $domain", tint = C.muted,
            modifier = Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onRemove).padding(9.dp),
        )
    }
}

/** (code, "🇩🇪 Germany", count) for every country we have working servers in. */
fun countryList(st: AppState): List<Triple<String, String, Int>> =
    st.servers.filter { it.code.isNotEmpty() }
        .groupBy { it.code }
        .map { (code, l) -> Triple(code, "${l.first().flag} ${l.first().country}", l.size) }
        .sortedByDescending { it.third }
