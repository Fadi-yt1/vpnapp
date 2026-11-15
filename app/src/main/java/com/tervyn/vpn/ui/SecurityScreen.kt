package com.tervyn.vpn.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import com.tervyn.vpn.core.AppState
import com.tervyn.vpn.core.BlockLists
import com.tervyn.vpn.core.Core
import com.tervyn.vpn.core.DnsConfig
import java.text.DateFormat
import java.util.Date

/** One protection and whether it is genuinely active right now. */
private class Check(val name: String, val ok: Boolean)

private fun checks(st: AppState): List<Check> {
    val p = st.prefs
    val dns = p.dns
    return listOf(
        Check("VPN connected", st.on),
        Check("Kill switch", p.killSwitch),
        Check("DNS leak protection", !p.targeted && (dns.mode == DnsConfig.AUTO || dns.leakProtection)),
        Check("IPv6 leak protection", !p.targeted && p.ipv6Block),
        Check("Private DNS", dns.mode == DnsConfig.AUTO || dns.encrypted),
        Check("Threat blocking", BlockLists.MALWARE in dns.filters || BlockLists.PHISHING in dns.filters),
    )
}

@Composable
fun SecurityScreen(st: AppState, nav: Nav) {
    val ctx = LocalContext.current
    val p = st.prefs
    val dns = p.dns
    val cs = checks(st)
    val active = cs.count { it.ok }

    Page("Security", nav, sub = false, subtitle = "Every switch here changes how the VPN really works") {
        // Summary ring
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val frac by animateFloatAsState(active / cs.size.toFloat(), label = "score")
                val pal = C.palette
                Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(84.dp)) {
                        val sw = 8.dp.toPx()
                        drawArc(pal.track, 0f, 360f, false, style = Stroke(sw))
                        drawArc(Brush.sweepGradient(listOf(pal.accent, pal.accent2, pal.accent)), -90f, 360f * frac, false, style = Stroke(sw, cap = StrokeCap.Round))
                    }
                    Text("$active/${cs.size}", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Fonts.display)
                }
                HSpace(16)
                Column(Modifier.weight(1f)) {
                    Text(if (active == cs.size) "Fully protected" else "$active of ${cs.size} protections on", color = C.text,
                        fontWeight = FontWeight.Bold, fontSize = 17.sp, fontFamily = Fonts.display)
                    VSpace(4)
                    val off = cs.filter { !it.ok }.joinToString(", ") { it.name }
                    Muted(if (off.isEmpty()) "Everything below is switched on and working." else "Off: $off")
                }
            }
        }

        SectionLabel("Leak protection")
        GroupCard {
            SettingRow(TIcons.lock, "Kill switch",
                if (p.killSwitch) "If the VPN server fails, apps stay blocked until it works again — nothing leaks"
                else "Off: if the VPN server fails, apps use your normal connection",
                checked = p.killSwitch, onCheckedChange = Core::setKillSwitch)
            RowDivider()
            SettingRow(TIcons.dns, "DNS leak protection",
                when {
                    p.targeted -> "\"Only my sites\" mode: other sites use your normal DNS"
                    dns.mode == DnsConfig.AUTO -> "On: VPN sites are looked up by the VPN server"
                    dns.leakProtection -> "On: lookups travel inside the VPN tunnel"
                    else -> "Off: lookups go to your resolver outside the tunnel"
                },
                onClick = { nav.open(Routes.DNS) })
            RowDivider()
            SettingRow(TIcons.ipv6, "IPv6 leak protection",
                if (p.ipv6Block) "IPv6 traffic is captured by the VPN and blocked" else "Off: IPv6 traffic can go around the VPN",
                checked = p.ipv6Block, onCheckedChange = Core::setIpv6Block)
            RowDivider()
            SettingRow(TIcons.shield, "Private DNS",
                when {
                    dns.mode == DnsConfig.AUTO -> "Automatic: no lookups leave your phone for VPN sites"
                    dns.encrypted -> "Encrypted: ${dns.label}"
                    else -> "Plain DNS (${dns.label}) — choose a provider to encrypt"
                },
                onClick = { nav.open(Routes.DNS) })
        }

        SectionLabel("Blocking")
        GroupCard {
            val blocked = st.dnsStats.blockedByCategory
            BlockLists.CATEGORIES.forEachIndexed { i, cat ->
                if (i > 0) RowDivider()
                val info = st.lists[cat.id]
                val updating = cat.id in st.listsUpdating
                val on = cat.id in dns.filters
                SettingRow(
                    when (cat.id) { BlockLists.ADS -> TIcons.eyeOff; BlockLists.TRACKERS -> TIcons.filter; BlockLists.MALWARE -> TIcons.bug; else -> TIcons.alert },
                    "Block ${cat.name.lowercase()}",
                    when {
                        updating -> "Downloading the list…"
                        on && info != null && info.count > 0 -> "%,d domains · %,d blocked this session".format(info.count, blocked[cat.id] ?: 0L)
                        on && info?.error.orEmpty().isNotEmpty() -> info?.error.orEmpty()
                        else -> cat.about
                    },
                    checked = on, onCheckedChange = { Core.setFilter(cat.id, it) },
                )
            }
        }
        VSpace(6)
        Muted("Blocking works for every app that goes through Tervyn, using maintained public lists stored on your phone.", Modifier.padding(horizontal = 6.dp))

        SectionLabel("Routing")
        GroupCard {
            SettingRow(TIcons.apps, "Split tunneling", splitLabel(p), onClick = { nav.open(Routes.SPLIT) })
            RowDivider()
            SettingRow(TIcons.route, "Multi-hop",
                when {
                    !p.multiHop -> "Send traffic through two servers in different countries"
                    st.on && st.status.hops.isNotEmpty() -> "Active: ${st.status.hops}"
                    else -> "On: used with SOCKS5 / HTTP servers when two working servers are available"
                },
                checked = p.multiHop, onCheckedChange = Core::setMultiHop)
            RowDivider()
            SettingRow(TIcons.wifi, "Auto connect", autoConnectLabel(st), onClick = { nav.open(Routes.AUTOCONNECT) })
        }

        SectionLabel("Android")
        GlassCard {
            val sys = st.systemKillSwitch
            Text("System kill switch", color = C.text, fontWeight = FontWeight.SemiBold)
            VSpace(4)
            Muted(
                when {
                    Build.VERSION.SDK_INT < 29 -> "Turn on \"Always-on VPN\" and \"Block connections without VPN\" for Tervyn in Android's VPN settings."
                    sys == null -> "Status shows here after you connect once."
                    sys.second -> "On: Android blocks all traffic whenever Tervyn isn't connected, even if the app is closed."
                    sys.first -> "Always-on VPN is on, but \"Block connections without VPN\" is off."
                    else -> "Off. For the strongest protection turn on \"Always-on VPN\" and \"Block connections without VPN\" for Tervyn."
                },
            )
            VSpace(12)
            GlassButton("Open Android VPN settings", Modifier.fillMaxWidth(), ButtonStyle.Secondary) {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
            }
        }

        SectionLabel("Diagnostics")
        CardRow(onClick = { nav.open(Routes.DIAGNOSTICS) }) {
            IconBubble(TIcons.bug)
            Column(Modifier.weight(1f)) {
                Text("Network diagnostics", color = C.text, fontWeight = FontWeight.SemiBold)
                Muted(st.diagnostics.at.takeIf { it > 0 }?.let {
                    "${st.diagnostics.items.count { i -> i.ok }} of ${st.diagnostics.items.size} checks passed · " +
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
                } ?: "Test the internet, VPN server, IP, DNS and leaks")
            }
        }
    }
}

private fun autoConnectLabel(st: AppState): String {
    val p = st.prefs
    val on = listOfNotNull(
        "on startup".takeIf { p.autoConnectBoot }, "when the app opens".takeIf { p.autoConnectLaunch },
        "on Wi-Fi".takeIf { p.autoConnectWifi }, "on mobile data".takeIf { p.autoConnectMobile },
    )
    return if (on.isEmpty()) "Off" else "Connects " + on.joinToString(", ")
}

@Composable
fun AutoConnectScreen(st: AppState, nav: Nav) {
    val p = st.prefs
    Page("Auto connect", nav, sub = true) {
        GroupCard {
            SettingRow(TIcons.power, "When the phone starts", "Connects after a restart or app update",
                checked = p.autoConnectBoot, onCheckedChange = { Core.setAutoConnect(boot = it) })
            RowDivider()
            SettingRow(TIcons.home, "When Tervyn opens", "Connects every time you open the app",
                checked = p.autoConnectLaunch, onCheckedChange = { Core.setAutoConnect(launch = it) })
            RowDivider()
            SettingRow(TIcons.wifi, "Wi-Fi protection", "Connects when your phone joins a Wi-Fi network",
                checked = p.autoConnectWifi, onCheckedChange = { Core.setAutoConnect(wifi = it) })
            RowDivider()
            SettingRow(TIcons.bolt, "On mobile data", "Connects when your phone switches to mobile data",
                checked = p.autoConnectMobile, onCheckedChange = { Core.setAutoConnect(mobile = it) })
        }
        VSpace(10)
        Muted("Android may not let apps start a VPN from the background. If auto connect doesn't start, turn on \"Always-on VPN\" for Tervyn in Android's VPN settings — Android then keeps the VPN connected for you.",
            Modifier.padding(horizontal = 6.dp))
    }
}

@Composable
fun DiagnosticsScreen(st: AppState, nav: Nav) {
    val d = st.diagnostics
    Page("Diagnostics", nav, sub = true, subtitle = "Real checks of your connection, step by step") {
        GlassButton(if (d.running) "Checking…" else if (d.at > 0) "Run again" else "Run diagnostics", Modifier.fillMaxWidth(),
            icon = TIcons.pulse, loading = d.running, enabled = !d.running) { Core.runDiagnostics() }
        if (d.running) { VSpace(12); GlassProgressBar(null) }
        if (d.items.isNotEmpty()) {
            VSpace(14)
            GlassCard(padding = PaddingValues(vertical = 6.dp)) {
                d.items.forEachIndexed { i, item ->
                    if (i > 0) RowDivider(52.dp)
                    ResultRow(item.name, item.ok, item.detail, item.ms)
                }
            }
            VSpace(8)
            Muted("Checked " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(d.at)), Modifier.padding(horizontal = 6.dp))
        } else if (!d.running) {
            VSpace(14)
            Muted("Checks your internet connection, the VPN tunnel and server, your public IP, DNS lookups, DNS and IPv6 leak protection, and Android's kill switch.",
                Modifier.padding(horizontal = 6.dp))
        }
    }
}
