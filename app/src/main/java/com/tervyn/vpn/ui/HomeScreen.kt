package com.tervyn.vpn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tervyn.vpn.R
import com.tervyn.vpn.core.AppState
import com.tervyn.vpn.core.Core
import com.tervyn.vpn.core.Prefs
import com.tervyn.vpn.core.countryName
import com.tervyn.vpn.core.formatBytes
import com.tervyn.vpn.core.formatMbps
import com.tervyn.vpn.core.formatRate
import kotlinx.coroutines.delay

fun orbState(st: AppState): OrbState = when {
    st.failed -> OrbState.Failed
    st.reconnecting -> OrbState.Reconnecting
    st.connecting -> OrbState.Connecting
    st.on -> OrbState.On
    else -> OrbState.Off
}

/** Status pill text and colour. */
@Composable
fun statusBadge(st: AppState): Pair<String, Color> = when {
    st.failed && st.status.blocking -> "BLOCKED" to C.red
    st.failed -> "FAILED" to C.red
    st.reconnecting -> "RECONNECTING" to C.amber
    st.connecting -> "CONNECTING" to C.amber
    st.on -> "PROTECTED" to C.green
    else -> "OFF" to C.muted
}

fun duration(since: Long, now: Long): String {
    val secs = ((now - since) / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60)
}

@Composable
fun HomeScreen(st: AppState, nav: Nav, onPower: () -> Unit) {
    val prefs = st.prefs
    val on = st.on
    val fs = st.freeStatus
    val activeId = if (on) st.status.serverIds.firstOrNull() else prefs.selected.takeIf { it != "auto" }
    val active = activeId?.let { id -> st.servers.firstOrNull { it.id == id } }
    val place = active?.country?.takeIf { it != "Unknown" } ?: ""

    // Connection timer, ticking once a second while connected.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(on) {
        while (on) { now = System.currentTimeMillis(); delay(1000) }
    }

    Page(null, nav, sub = false) {
        // Header: logo, name, live status
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(R.drawable.logo_full), null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, C.glassBorder, RoundedCornerShape(12.dp)),
            )
            HSpace(10)
            Column(Modifier.weight(1f)) {
                Text("Tervyn", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = Fonts.display, maxLines = 1)
                Text("VPN", color = C.accentLight, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
            val (label, color) = statusBadge(st)
            GlassStatusBadge(label, color, live = on || st.connecting || st.reconnecting)
            HSpace(8)
            GlassIconButton(TIcons.chart, "Network monitor", size = 42.dp) { nav.open(Routes.MONITOR) }
        }

        VSpace(8)
        GlassConnectionOrb(
            orbState(st), onPower, Modifier.align(Alignment.CenterHorizontally),
            progress = if (st.connecting && fs.state == "testing") fs.progress / 100f else null,
        )

        Text(
            when {
                st.failed && st.status.blocking -> "Internet blocked"
                st.failed -> "Couldn't connect"
                st.reconnecting -> "Reconnecting…"
                st.connecting -> if (fs.busy) "Finding fast servers…" else "Connecting…"
                on -> if (prefs.targeted) "Your sites are protected" else "You're protected"
                else -> "Tap to connect"
            },
            color = C.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = Fonts.display,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        VSpace(4)
        if (on && st.status.since > 0) {
            Text(duration(st.status.since, now), color = C.accentLight, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = Fonts.display, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else {
            Muted(
                when {
                    st.failed && st.status.blocking -> "The kill switch blocks traffic until a server works. Retrying automatically."
                    st.failed -> "Retrying automatically. Tap to stop."
                    st.connecting && fs.state == "testing" -> "Tested ${fs.progress}% · ${fs.working} working found"
                    st.connecting && fs.state == "speed" -> "Measuring speed of the best servers…"
                    fs.state == "loading" -> "Finding free servers…"
                    fs.state == "testing" -> "Testing servers… ${fs.progress}%"
                    st.servers.any { it.ms != null } -> "${st.servers.count { it.ms != null }} working servers ready"
                    else -> "Servers are found and tested automatically"
                },
                Modifier.fillMaxWidth(), center = true,
            )
        }

        if (st.status.error.isNotEmpty()) Alert(st.status.error, onClose = Core::clearError)
        if (on && st.status.warning.isNotEmpty()) Alert(st.status.warning, warn = true, onClose = Core::clearError)
        PermissionBanner(nav)

        // Location
        VSpace(18)
        CardRow(onClick = { nav.tab(1) }) {
            FlagBadge(active?.flag, 46)
            Column(Modifier.weight(1f)) {
                Text(
                    if (active == null) "Fastest location" else place.ifEmpty { active.name },
                    color = C.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        on && st.status.hops.isNotEmpty() -> "Multi-hop ${st.status.hops}" + (active?.let { " · " + serverSub(it) } ?: "")
                        on && active != null -> serverSub(active)
                        on -> st.status.label
                        active == null -> "Smart Connect · " + sourceName(prefs.source) +
                            (if (prefs.country.isNotEmpty()) " in ${countryName(prefs.country)}" else "")
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

        // Smart Connect
        VSpace(10)
        GlassCard(padding = PaddingValues(0.dp)) {
            SettingRow(
                TIcons.sparkle, "Smart Connect",
                if (prefs.selected == "auto" && prefs.autoSwitch) "Fastest server, switches automatically"
                else "Off: stays on your chosen server",
                checked = prefs.selected == "auto" && prefs.autoSwitch,
                onCheckedChange = { onOff ->
                    if (onOff) { Core.select("auto"); Core.setAutoSwitch(true) } else Core.setAutoSwitch(false)
                },
            )
        }

        // Connection details: genuine values only, "—" when unknown.
        SectionLabel("Connection")
        val ip = st.ipInfo
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Public IP", ip?.ip ?: "Tap to check", TIcons.globe, Modifier.weight(1.4f), onClick = Core::checkIp)
            StatTile("Protocol", if (on) protoName(st.status.protocol) else "—", TIcons.layers, Modifier.weight(1f))
        }
        VSpace(10)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Duration", if (on && st.status.since > 0) duration(st.status.since, now) else "—", TIcons.clock, Modifier.weight(1f))
            StatTile("Ping", st.status.ping?.takeIf { on }?.let { "$it ms" } ?: "—", TIcons.pulse, Modifier.weight(1f),
                color = pingColor(st.status.ping.takeIf { on }))
        }
        if (ip != null) {
            VSpace(6)
            Muted(
                listOf(ip.city, ip.country).filter { it.isNotBlank() }.joinToString(", ") +
                    (if (ip.isp.isNotBlank()) " · ${ip.isp}" else "") + if (ip.viaVpn) " · seen through the VPN" else " · your own network",
                Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            )
        } else if (st.ipText.isNotEmpty()) {
            VSpace(6)
            Muted(st.ipText, Modifier.padding(horizontal = 6.dp))
        }

        if (prefs.showSpeed) {
            VSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSpeedCard("Download", if (on) formatRate(st.traffic.down) else "—", TIcons.arrowDown, C.accent, st.history.down, Modifier.weight(1f))
                GlassSpeedCard("Upload", if (on) formatRate(st.traffic.up) else "—", TIcons.arrowUp, C.accent2, st.history.up, Modifier.weight(1f))
            }
            if (on) {
                VSpace(6)
                Muted("Used this session: ↓ ${formatBytes(st.traffic.totalDown)}  ↑ ${formatBytes(st.traffic.totalUp)}", Modifier.fillMaxWidth(), center = true)
            }
        }

        // What goes through the VPN
        SectionLabel("Protect")
        GlassTabBar(listOf(Prefs.MODE_FULL to "All apps", Prefs.MODE_TARGETED to "Only my sites"), prefs.mode, { mode ->
            Core.setMode(mode)
            if (mode == Prefs.MODE_TARGETED && prefs.sites.isEmpty()) nav.open(Routes.SITES)
        })
        if (prefs.targeted) {
            VSpace(10)
            CardRow(onClick = { nav.open(Routes.SITES) }) {
                IconBubble(TIcons.globe)
                Column(Modifier.weight(1f)) {
                    Text(if (prefs.sites.isEmpty()) "No sites yet" else "${prefs.sites.size} protected site${if (prefs.sites.size == 1) "" else "s"}",
                        color = C.text, fontWeight = FontWeight.SemiBold)
                    Muted(prefs.sites.take(3).joinToString(", ").ifEmpty { "Tap to add the sites that should use the VPN" })
                }
                Icon(TIcons.chevron, null, tint = C.muted, modifier = Modifier.size(16.dp))
            }
        }

        // Shortcuts
        SectionLabel("Tools")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Shortcut("DNS Center", if (st.prefs.dnsEnabled) st.prefs.dns.label else "Off", TIcons.dns, Modifier.weight(1f)) { nav.open(Routes.DNS) }
            Shortcut("Monitor", if (on) formatRate(st.traffic.down + st.traffic.up) else "Live charts", TIcons.chart, Modifier.weight(1f)) { nav.open(Routes.MONITOR) }
        }
        VSpace(10)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Shortcut("Split tunneling", splitLabel(prefs), TIcons.apps, Modifier.weight(1f)) { nav.open(Routes.SPLIT) }
            Shortcut("Diagnostics", "Check connection", TIcons.bug, Modifier.weight(1f)) { nav.open(Routes.DIAGNOSTICS) }
        }
    }
}

@Composable
private fun Shortcut(title: String, sub: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    GlassContainer(modifier, ShapeInner, onClick = onClick, padding = PaddingValues(14.dp)) {
        IconBubble(icon, size = 34.dp)
        VSpace(10)
        Text(title, color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
        Text(sub, color = C.muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun pingColor(ms: Int?): Color = when {
    ms == null -> C.accentLight
    ms < 300 -> C.green
    ms < 800 -> C.amber
    else -> C.red
}

fun sourceName(source: String): String = when (source) {
    Prefs.SOURCE_PROXIO -> "Proxio.io servers"
    Prefs.SOURCE_BOTH -> "Proxio.io + free servers"
    Prefs.SOURCE_OWN -> "your servers"
    Prefs.SOURCE_CUSTOM -> "integration proxies"
    Prefs.SOURCE_LIST -> "your proxy list"
    Prefs.SOURCE_VPNGATE -> "VPN Gate + WARP"
    else -> "free servers"
}

fun splitLabel(p: Prefs): String = when (p.splitMode) {
    Prefs.SPLIT_EXCLUDE -> "${p.splitApps.size} app${if (p.splitApps.size == 1) "" else "s"} bypass"
    Prefs.SPLIT_INCLUDE -> "Only ${p.splitApps.size} app${if (p.splitApps.size == 1) "" else "s"}"
    else -> "All apps"
}

@Composable
fun SiteRow(domain: String, onRemove: () -> Unit) {
    CardRow {
        IconBubble(TIcons.globe, size = 32.dp)
        Text(domain, color = C.text, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(
            TIcons.close, "Remove $domain", tint = C.muted,
            modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onRemove).padding(13.dp),
        )
    }
}

/** (code, "🇩🇪 Germany", count) for every country we have servers in. */
fun countryList(st: AppState): List<Triple<String, String, Int>> =
    st.servers.filter { it.code.isNotEmpty() }
        .groupBy { it.code }
        .map { (code, l) -> Triple(code, "${l.first().flag} ${l.first().country}", l.size) }
        .sortedByDescending { it.third }
