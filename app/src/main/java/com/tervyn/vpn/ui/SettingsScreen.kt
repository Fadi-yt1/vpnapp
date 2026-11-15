package com.tervyn.vpn.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tervyn.vpn.BuildConfig
import com.tervyn.vpn.core.AppState
import com.tervyn.vpn.core.Core
import com.tervyn.vpn.core.PRESETS
import com.tervyn.vpn.core.Prefs
import com.tervyn.vpn.core.countryName
import com.tervyn.vpn.core.formatMbps
import com.tervyn.vpn.core.normalizeSite
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(st: AppState, nav: Nav) {
    val ctx = LocalContext.current
    val p = st.prefs
    Page("Settings", nav, sub = false) {
        SectionLabel("Account")
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(TIcons.user, size = 44.dp)
                HSpace(12)
                Column(Modifier.weight(1f)) {
                    Text("No account needed", color = C.text, fontWeight = FontWeight.SemiBold)
                    Muted("Tervyn runs entirely on this phone. Your settings, keys and server lists never leave it.")
                }
            }
        }

        SectionLabel("Connection")
        GroupCard {
            SettingRow(TIcons.servers, "Server sources", sourceName(p.source).replaceFirstChar { it.uppercase() }, onClick = { nav.open(Routes.SOURCES) })
            RowDivider()
            SettingRow(TIcons.layers, "Protocol & switching", Prefs.PROTOCOLS.firstOrNull { it.first == p.protocol }?.second ?: p.protocol,
                onClick = { nav.open(Routes.CONNECTION) })
            RowDivider()
            SettingRow(TIcons.sparkle, "Smart Connect", "Fastest server, switches automatically",
                checked = p.selected == "auto" && p.autoSwitch,
                onCheckedChange = { if (it) { Core.select("auto"); Core.setAutoSwitch(true) } else Core.setAutoSwitch(false) })
            RowDivider()
            SettingRow(TIcons.globe, "Only my sites", if (p.targeted) "${p.sites.size} site(s) use the VPN" else "Off: all apps use the VPN",
                onClick = { nav.open(Routes.SITES) })
        }

        SectionLabel("DNS")
        GroupCard {
            SettingRow(TIcons.dns, "Tervyn DNS", if (p.dnsEnabled) "On · ${p.dns.label}" else "Off: the VPN server's DNS", checked = p.dnsEnabled, onCheckedChange = Core::setDnsEnabled)
            RowDivider()
            SettingRow(TIcons.filter, "DNS Center", "Resolver, blocking, profiles, test", onClick = { nav.open(Routes.DNS) })
        }

        SectionLabel("Security")
        GroupCard {
            SettingRow(TIcons.lock, "Kill switch", null, checked = p.killSwitch, onCheckedChange = Core::setKillSwitch)
            RowDivider()
            SettingRow(TIcons.apps, "Split tunneling", splitLabel(p), onClick = { nav.open(Routes.SPLIT) })
            RowDivider()
            SettingRow(TIcons.wifi, "Auto connect", null, onClick = { nav.open(Routes.AUTOCONNECT) })
            RowDivider()
            SettingRow(TIcons.shield, "Security Center", null, onClick = { nav.tab(2) })
        }

        SectionLabel("Appearance")
        GroupCard {
            SettingRow(TIcons.palette, "Theme", if (p.theme == Prefs.THEME_SYSTEM) "Match system" else C.palette.name, onClick = { nav.open(Routes.APPEARANCE) })
        }

        SectionLabel("General")
        GroupCard {
            SettingRow(TIcons.chart, "Live speed on Home", null, checked = p.showSpeed, onCheckedChange = Core::setShowSpeed)
            RowDivider()
            SettingRow(TIcons.pulse, "Live speed in notification", null, checked = p.speedInNotification, onCheckedChange = Core::setSpeedInNotification)
            RowDivider()
            SettingRow(TIcons.lock, "Always-on VPN", "Android's own setting: keep Tervyn connected",
                onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } })
            RowDivider()
            SettingRow(TIcons.apps, "Quick Settings tile", "Swipe down twice → edit → drag \"Tervyn VPN\" into your tiles")
        }

        SectionLabel("Permissions")
        GroupCard {
            val pm = LocalPerms.current
            SettingRow(TIcons.shield, "App permissions", if (pm.missing == 0) "All set" else "${pm.missing} missing", onClick = { nav.open(Routes.PERMISSIONS) })
        }

        SectionLabel("Support")
        GroupCard {
            SettingRow(TIcons.help, "Help", null, onClick = { nav.open(Routes.HELP) })
            RowDivider()
            SettingRow(TIcons.eyeOff, "Privacy", null, onClick = { nav.open(Routes.PRIVACY) })
            RowDivider()
            SettingRow(TIcons.info, "About Tervyn", "Version ${BuildConfig.VERSION_NAME}", onClick = { nav.open(Routes.ABOUT) })
        }
    }
}

// ---------------------------------------------------------------------------- Connection

@Composable
fun ConnectionScreen(st: AppState, nav: Nav) {
    val p = st.prefs
    val t = st.speedTest
    Page("Protocol & switching", nav, sub = true) {
        SectionLabel("Protocol")
        GlassDropdown(p.protocol, Prefs.PROTOCOLS, Core::setProtocol, leading = TIcons.layers)
        VSpace(6)
        Muted(
            if (p.protocol == Prefs.PROTO_AUTO) "Automatic uses the best working server of any protocol your sources provide."
            else "Only ${Prefs.PROTOCOLS.first { it.first == p.protocol }.second} servers are used. Add them in Server sources if you have none.",
            Modifier.padding(horizontal = 6.dp),
        )

        SectionLabel("Switching")
        GroupCard {
            SettingRow(TIcons.sparkle, "Auto-switch to fastest server",
                if (p.autoSwitch) "Keeps backups ready and switches instantly if a server stops working" else "Stays on the selected server",
                checked = p.autoSwitch, onCheckedChange = Core::setAutoSwitch)
            RowDivider()
            SettingRow(TIcons.route, "Multi-hop", "Route through a second server first (SOCKS5 / HTTP servers)",
                checked = p.multiHop, onCheckedChange = Core::setMultiHop)
        }
        SectionLabel("Backup servers kept ready")
        GlassTabBar(listOf("2", "3", "5", "8").map { it to it }, p.autoPool.toString(), { Core.setAutoPool(it.toInt()) })

        SectionLabel("Preferred country")
        CountryPicker(p.country, countryList(st), "Any country (fastest)", Core::setCountry)
        if (p.country.isNotEmpty()) { VSpace(6); Muted("Smart Connect only uses servers in ${countryName(p.country)}.", Modifier.padding(horizontal = 6.dp)) }

        SectionLabel("Speed test")
        GlassCard {
            Muted(if (st.on) "Measures download speed through the server you're connected to." else "Measures download speed of the best server for your source.")
            VSpace(12)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(t.mbps?.let { formatMbps(it) } ?: "—", color = C.text, fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = Fonts.display)
                HSpace(10)
                if (t.ms != null) Muted("${t.ms} ms to first byte", Modifier.padding(bottom = 6.dp))
            }
            if (t.server.isNotEmpty()) Muted("${t.server} · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t.at)))
            if (t.error.isNotEmpty()) { VSpace(6); Text(t.error, color = C.red, fontSize = 12.sp) }
            VSpace(12)
            GlassButton(if (t.running) "Testing…" else "Run speed test", Modifier.fillMaxWidth(), icon = TIcons.bolt, loading = t.running, enabled = !t.running) { Core.runSpeedTest() }
        }

        SectionLabel("Xray core")
        GlassCard {
            if (st.xrayVersion.isNotEmpty()) {
                Text("Included · Xray ${st.xrayVersion}", color = C.green, fontWeight = FontWeight.SemiBold)
                VSpace(4)
                Muted("WireGuard, VLESS, VMess, Trojan and Shadowsocks servers work. Xray runs only while one of them is in use or being tested.")
            } else if (Core.xrayIncluded) {
                Text("Included", color = C.text, fontWeight = FontWeight.SemiBold)
            } else {
                Text("Not included in this build", color = C.amber, fontWeight = FontWeight.SemiBold)
                VSpace(4)
                Muted("WireGuard / VLESS / VMess / Trojan / Shadowsocks need Xray. Install the APK built by GitHub Actions, which includes it.")
            }
        }
    }
}

// ---------------------------------------------------------------------------- Sites

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SitesScreen(st: AppState, nav: Nav) {
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    val prefs = st.prefs

    fun add() {
        val list = input.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        if (list.isEmpty()) return
        if (list.none { normalizeSite(it).isNotEmpty() }) {
            error = "That doesn't look like a website address. Example: youtube.com"
            return
        }
        error = ""
        Core.addSites(list)
        input = ""
    }

    Page("Only my sites", nav, sub = true, subtitle = "Just these sites use the VPN, in every app") {
        GlassTabBar(listOf(Prefs.MODE_FULL to "All apps", Prefs.MODE_TARGETED to "Only my sites"), prefs.mode, Core::setMode)
        VSpace(10)
        Muted(if (prefs.targeted) "Only the sites below go through the VPN. Everything else uses your normal connection. Subdomains are included."
            else "You're in \"All apps\" mode, so everything is protected. Switch to \"Only my sites\" to protect just the sites below.",
            Modifier.padding(horizontal = 6.dp))
        VSpace(12)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassInput(
                input, { input = it }, "example.com", Modifier.weight(1f), leading = TIcons.globe,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
            )
            GlassButton("Add", enabled = input.isNotBlank()) { add() }
        }
        if (error.isNotEmpty()) Alert(error) { error = "" }
        VSpace(6)
        Muted("Tip: in any browser or app, tap Share → Tervyn VPN to add that site.", Modifier.padding(horizontal = 6.dp))

        SectionLabel("Quick add")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (pr in PRESETS) {
                val added = pr.domains.all { it in prefs.sites }
                Chip(pr.name, added) { if (added) Core.removeSites(pr.domains) else Core.addSites(pr.domains) }
            }
        }

        SectionLabel("Your sites" + if (prefs.sites.isNotEmpty()) " (${prefs.sites.size})" else "")
        if (prefs.sites.isEmpty()) Muted("No sites yet. Add one above or use Quick add.", Modifier.padding(horizontal = 6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (d in prefs.sites) SiteRow(d) { Core.removeSites(listOf(d)) }
        }
    }
}

// ---------------------------------------------------------------------------- Help / privacy / about

@Composable
fun InfoScreen(kind: String, nav: Nav) {
    val (title, blocks) = when (kind) {
        Routes.HELP -> "Help" to listOf(
            "Connecting" to "Tap the orb on Home. The first time, Android asks you to allow Tervyn to set up a VPN. Smart Connect picks the fastest working server; turn it off to stay on a server you chose in Locations.",
            "Servers" to "Free public servers are found and tested automatically. For steadier connections add your own servers (WireGuard, VLESS, VMess, Trojan, Shadowsocks, SOCKS5, HTTP), a Proxio.io API key, a proxy list file or your provider's curl / Python / Node.js example in Settings → Server sources.",
            "Only my sites" to "Protects just the sites you list, in every app. Everything else uses your normal connection — useful with free servers.",
            "Kill switch" to "If the VPN server stops working, Tervyn keeps apps blocked until it reconnects. For protection while Tervyn itself is closed, turn on Always-on VPN and \"Block connections without VPN\" in Android's VPN settings.",
            "DNS" to "DNS Center lets you choose who answers your apps' lookups, encrypt them, keep them inside the tunnel, block ads, trackers, malware and phishing, and save setups as profiles. Use Test to check it.",
            "Video calls and games" to "Most servers carry TCP only. Apps using UDP (calls, some games) fall back to TCP, or use your normal connection in \"Only my sites\" mode. WireGuard servers carry everything.",
            "Something isn't working" to "Open Security → Network diagnostics and run it: it checks each step and tells you which one fails.",
        )
        Routes.PRIVACY -> "Privacy" to listOf(
            "No account, no tracking" to "Tervyn has no sign-up, no analytics and no ads. Nothing about you or your browsing is sent to us — there is no Tervyn server.",
            "Stays on your phone" to "Your settings, API keys, proxy logins, server lists and DNS profiles are stored only in the app's private storage.",
            "Who sees your traffic" to "The VPN server you use sees which sites you visit (secure https sites stay encrypted end to end). Free public servers are run by unknown people: avoid banking and other sensitive logins through them.",
            "What Tervyn downloads" to "Free server lists and DNS blocklists from public GitHub repositories, your Proxio.io list when you add a key, and your public IP from ipwho.is when you check it.",
            "Permissions" to "VPN (to route traffic), notifications (connection status), start at boot (only if you turn on auto connect), and the list of installed apps (only shown in Split tunneling, never sent anywhere).",
        )
        else -> "About" to listOf(
            "Tervyn VPN ${BuildConfig.VERSION_NAME}" to "A private, account-free VPN app with liquid glass design.",
            "Open source components" to "Xray-core (MPL 2.0) for WireGuard, VLESS, VMess, Trojan and Shadowsocks. Haze (Apache 2.0) for background blur. Sora and Inter fonts (SIL Open Font License). World map from Natural Earth (public domain); country positions from Google DSPL (CC BY 4.0).",
            "Blocklists" to "Ads: anudeepND, StevenBlack. Tracking: Perflyst, HaGeZi. Malware: HaGeZi Threat Intelligence. Phishing: RPiList. Each list keeps its own licence.",
        )
    }
    Page(title, nav, sub = true) {
        for ((h, body) in blocks) {
            GlassCard {
                Text(h, color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                VSpace(6)
                Text(body, color = C.muted, fontSize = 13.5.sp, lineHeight = 19.sp)
            }
            VSpace(10)
        }
    }
}
