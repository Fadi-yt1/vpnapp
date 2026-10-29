package com.tervyn.vpn.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tervyn.vpn.core.AppState
import com.tervyn.vpn.core.Core
import com.tervyn.vpn.core.Prefs

@Composable
fun HomeScreen(st: AppState, onPower: () -> Unit, goTo: (Int) -> Unit) {
    val prefs = st.prefs
    val on = st.on
    val busy = st.connecting
    val fs = st.freeStatus
    val activeId = if (on) st.status.serverIds.firstOrNull() else prefs.selected.takeIf { it != "auto" }
    val active = activeId?.let { id -> st.servers.firstOrNull { it.id == id } }
    val place = active?.country?.takeIf { it != "Unknown" } ?: ""

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VSpace(16)
        PowerButton(on = on && !busy, busy = busy, onClick = onPower)
        VSpace(16)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(TIcons.shield, null, tint = if (on) C.green else C.muted, modifier = Modifier.size(22.dp))
            HSpace(9)
            Text(
                when {
                    busy -> if (fs.busy) "Finding fast servers…" else "Connecting…"
                    on -> if (prefs.targeted) "Your sites are protected" else "Protected connection"
                    else -> "Not connected"
                },
                color = C.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        VSpace(4)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (on) {
                Icon(TIcons.pin, null, tint = C.muted, modifier = Modifier.size(14.dp))
                HSpace(5)
            }
            Muted(
                when {
                    on -> listOf(
                        place.ifEmpty { "Free server" },
                        if (prefs.targeted) "${prefs.sites.size} site${if (prefs.sites.size == 1) "" else "s"}" else "All apps",
                    ).joinToString(" • ")
                    busy && fs.state == "testing" -> "Tested ${fs.progress}% · ${fs.working} working found"
                    fs.state == "loading" -> "Finding free servers…"
                    fs.state == "testing" -> "Testing free servers… ${fs.progress}%"
                    st.servers.any { it.ms != null } -> "${st.servers.count { it.ms != null }} fast servers ready — tap to connect"
                    else -> "Tap to connect — free servers are found automatically"
                },
                center = true,
            )
        }

        if (st.status.error.isNotEmpty()) Alert(st.status.error, onClose = Core::clearError)
        if (on && st.status.warning.isNotEmpty()) Alert(st.status.warning, warn = true, onClose = Core::clearError)

        VSpace(18)
        Segmented(
            listOf(Prefs.MODE_FULL to "All apps", Prefs.MODE_TARGETED to "Only my sites"),
            prefs.mode,
        ) { mode ->
            Core.setMode(mode)
            if (mode == Prefs.MODE_TARGETED && prefs.sites.isEmpty()) goTo(2)
        }

        SectionLabel("Location")
        CountryPicker(prefs.country, countryList(st), "Fastest available", Core::setCountry)

        if (prefs.targeted) {
            SectionLabel("Protected sites") {
                Text(
                    if (prefs.sites.size > 3) "+${prefs.sites.size - 3} more · Manage" else "Add / manage",
                    color = C.accentLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).clickable { goTo(2) }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (prefs.sites.isEmpty()) {
                Card(onClick = { goTo(2) }) { Text("No sites yet — tap to add sites to protect", color = C.accentLight, fontSize = 13.sp) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in prefs.sites.take(3)) SiteRow(d) { Core.removeSites(listOf(d)) }
            }
        }

        SectionLabel("Current server")
        CardRow(onClick = { goTo(1) }) {
            FlagBadge(active?.flag)
            Column(Modifier.weight(1f)) {
                Text(
                    if (active == null) "Fastest available" else place.ifEmpty { active.name },
                    color = C.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        active == null -> "Auto · picks the fastest " + when (prefs.source) {
                            Prefs.SOURCE_PROXIO -> "Proxio.io server"
                            Prefs.SOURCE_BOTH -> "Proxio.io or free server"
                            Prefs.SOURCE_OWN -> "of your servers"
                            Prefs.SOURCE_CUSTOM -> "integration proxy"
                            Prefs.SOURCE_LIST -> "proxy in your list"
                            else -> "free server"
                        } +
                            (if (prefs.country.isNotEmpty()) " in ${com.tervyn.vpn.core.countryName(prefs.country)}" else "")
                        on -> st.status.label
                        else -> serverSub(active)
                    },
                    color = C.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (active?.ms != null) PingBadge(active.ms, true)
            Icon(TIcons.chevron, null, tint = C.muted, modifier = Modifier.size(16.dp))
        }

        VSpace(14)
        CardRow(onClick = Core::checkIp) {
            Icon(TIcons.lock, null, tint = if (on) C.green else C.muted, modifier = Modifier.size(16.dp))
            Text(
                st.ipText.ifEmpty { if (on) "Encrypted connection · tap to check your IP" else "Not connected · tap to check your IP" },
                color = C.muted, fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            Icon(TIcons.chevron, null, tint = C.muted, modifier = Modifier.size(16.dp))
        }
        VSpace(20)
    }
}

@Composable
private fun PowerButton(on: Boolean, busy: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "pulse")
    val pulse by t.animateFloat(1f, 1.08f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")
    val ringColor = if (on || busy) Color(0x739678FF) else Color(0x1F96A0FF)
    Box(
        Modifier.size(170.dp).scale(if (busy) pulse else 1f).clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(if (on) 0x337B5CFF else 0x1A7B5CFF), Color.Transparent)))
            .border(1.dp, ringColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(132.dp)
                .shadow(if (on) 28.dp else 12.dp, CircleShape, ambientColor = C.accent, spotColor = C.accent)
                .clip(CircleShape)
                .background(
                    if (on) Brush.radialGradient(listOf(Color(0xFFA68CFF), Color(0xFF7350FF), Color(0xFF5530EA)))
                    else Brush.radialGradient(listOf(Color(0xFF2A2F63), Color(0xFF171A40)))
                )
                .border(1.dp, Color(if (on) 0x40FFFFFF else 0x14FFFFFF), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TIcons.power, if (on) "Disconnect" else "Connect", tint = if (on) Color.White else Color(0xFFC9CDF0), modifier = Modifier.size(52.dp))
        }
    }
}

@Composable
fun SiteRow(domain: String, onRemove: () -> Unit) {
    CardRow {
        Box(Modifier.size(30.dp).clip(CircleShape).background(C.accentSoft), contentAlignment = Alignment.Center) {
            Icon(TIcons.globe, null, tint = C.accentLight, modifier = Modifier.size(16.dp))
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

