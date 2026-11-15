package com.tervyn.vpn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tervyn.vpn.core.AppState
import com.tervyn.vpn.core.BlockLists
import com.tervyn.vpn.core.Core
import com.tervyn.vpn.core.DnsConfig
import java.text.DateFormat
import java.util.Date

@Composable
fun DnsScreen(st: AppState, nav: Nav) {
    val p = st.prefs
    val dns = p.dns
    val stats = st.dnsStats
    var mode by rememberSaveable(dns.mode) { mutableStateOf(dns.mode) }
    var error by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf("") }

    fun save(cfg: DnsConfig) {
        val e = Core.setDnsConfig(cfg)
        error = e ?: ""
        saved = if (e == null) "Saved: ${cfg.label}" else ""
    }

    Page("DNS Center", nav, sub = true, subtitle = "How your apps look up websites") {
        // ---------------- status
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(TIcons.dns, if (dns.encrypted || dns.mode == DnsConfig.AUTO) C.green else C.amber, 44.dp)
                HSpace(12)
                Column(Modifier.weight(1f)) {
                    Text(dns.label, color = C.text, fontWeight = FontWeight.Bold, fontSize = 17.sp, fontFamily = Fonts.display,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted(
                        when {
                            !st.tunnelUp -> "Not in use — connect the VPN"
                            dns.mode == DnsConfig.AUTO -> "Automatic · VPN sites resolved by the VPN server"
                            dns.leakProtection -> (if (dns.encrypted) "Encrypted" else "Plain DNS") + " · through the VPN tunnel"
                            else -> (if (dns.encrypted) "Encrypted" else "Plain DNS") + " · directly, outside the tunnel"
                        },
                    )
                }
            }
            p.dnsProfiles.firstOrNull { it.id == p.dnsProfile }?.let { prof ->
                VSpace(10)
                GlassStatusBadge("PROFILE · ${prof.name.uppercase()}", C.accentLight)
            }
            if (dns.addresses.isNotEmpty() && dns.mode != DnsConfig.AUTO) {
                VSpace(8)
                Muted(dns.addresses)
            }
            VSpace(14)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat("Lookups", "%,d".format(stats.queries), Modifier.weight(1f))
                MiniStat("Blocked", "%,d".format(stats.blocked), Modifier.weight(1f))
                MiniStat("Encrypted", "%,d".format(stats.encrypted), Modifier.weight(1f))
            }
            VSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat("Via tunnel", "%,d".format(stats.viaTunnel), Modifier.weight(1f))
                MiniStat("Direct", "%,d".format(stats.direct), Modifier.weight(1f))
                MiniStat("Failed", "%,d".format(stats.failed), Modifier.weight(1f))
            }
            if (stats.lastResolver.isNotEmpty()) {
                VSpace(10)
                Muted("Last answer: ${stats.lastResolver} in ${stats.lastMs} ms")
            }
            if (stats.lastError.isNotEmpty()) {
                VSpace(4)
                Text("Last error: ${stats.lastError}", color = C.red, fontSize = 12.sp)
            }
        }

        // ---------------- configuration
        SectionLabel("DNS server")
        GroupCard {
            ChoiceRow("Automatic", "VPN sites are looked up by the VPN server; nothing leaves your phone", mode == DnsConfig.AUTO, {
                mode = DnsConfig.AUTO; save(dns.copy(mode = DnsConfig.AUTO))
            })
            RowDivider(52.dp)
            ChoiceRow("Provider", "Cloudflare, Google, Quad9 and more, over DNS-over-HTTPS", mode == DnsConfig.PRESET, {
                mode = DnsConfig.PRESET; save(dns.copy(mode = DnsConfig.PRESET))
            })
            RowDivider(52.dp)
            ChoiceRow("Custom", "Your own DNS servers (IPv4 or IPv6)", mode == DnsConfig.CUSTOM, { mode = DnsConfig.CUSTOM })
            RowDivider(52.dp)
            ChoiceRow("Private DNS", "Your own encrypted resolver: DNS-over-HTTPS or DNS-over-TLS", mode == DnsConfig.PRIVATE, { mode = DnsConfig.PRIVATE })
        }

        when (mode) {
            DnsConfig.PRESET -> {
                SectionLabel("Provider")
                GroupCard {
                    DnsConfig.PRESETS.forEachIndexed { i, pr ->
                        if (i > 0) RowDivider(52.dp)
                        ChoiceRow(pr.name, "${pr.about} · ${pr.ip1}, ${pr.ip2}", dns.mode == DnsConfig.PRESET && dns.preset == pr.id, {
                            save(dns.copy(mode = DnsConfig.PRESET, preset = pr.id))
                        })
                    }
                }
            }
            DnsConfig.CUSTOM -> {
                var primary by rememberSaveable(dns.primary) { mutableStateOf(dns.primary) }
                var secondary by rememberSaveable(dns.secondary) { mutableStateOf(dns.secondary) }
                SectionLabel("Custom servers")
                GlassCard {
                    GlassInput(primary, { primary = it.trim() }, "e.g. 1.1.1.1 or 2606:4700:4700::1111", label = "Primary DNS",
                        error = primary.isNotEmpty() && !DnsConfig.isIp(primary), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    ipKind(primary)?.let { Muted(it, Modifier.padding(start = 8.dp, top = 4.dp)) }
                    VSpace(12)
                    GlassInput(secondary, { secondary = it.trim() }, "Optional, e.g. 8.8.8.8", label = "Secondary DNS",
                        error = secondary.isNotEmpty() && !DnsConfig.isIp(secondary), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    ipKind(secondary)?.let { Muted(it, Modifier.padding(start = 8.dp, top = 4.dp)) }
                    VSpace(14)
                    GlassButton("Save custom DNS", Modifier.fillMaxWidth(), enabled = primary.isNotBlank()) {
                        save(dns.copy(mode = DnsConfig.CUSTOM, primary = primary, secondary = secondary))
                    }
                    VSpace(8)
                    Muted("Plain DNS is only sent inside the VPN tunnel while leak protection is on.")
                }
            }
            DnsConfig.PRIVATE -> {
                var type by rememberSaveable(dns.privateType) { mutableStateOf(dns.privateType) }
                var host by rememberSaveable(dns.privateHost) { mutableStateOf(dns.privateHost) }
                SectionLabel("Private DNS")
                GlassCard {
                    GlassTabBar(listOf(DnsConfig.DOH to "DNS over HTTPS", DnsConfig.DOT to "DNS over TLS"), type, { type = it })
                    VSpace(12)
                    GlassInput(host, { host = it.trim() },
                        if (type == DnsConfig.DOH) "https://dns.example.com/dns-query" else "dns.example.com",
                        label = if (type == DnsConfig.DOH) "Resolver URL" else "Resolver hostname",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    VSpace(14)
                    GlassButton("Save private DNS", Modifier.fillMaxWidth(), enabled = host.isNotBlank()) {
                        save(dns.copy(mode = DnsConfig.PRIVATE, privateType = type, privateHost = host))
                    }
                }
            }
        }
        if (error.isNotEmpty()) Alert(error) { error = "" }
        if (saved.isNotEmpty() && error.isEmpty()) { VSpace(8); Text(saved, color = C.green, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp)) }

        // ---------------- protection
        SectionLabel("Protection")
        GroupCard {
            SettingRow(TIcons.lock, "DNS leak protection",
                if (dns.mode == DnsConfig.AUTO) "Always on in Automatic mode"
                else if (dns.leakProtection) "Lookups travel inside the VPN tunnel, never in the clear"
                else "Off: lookups go to your resolver directly",
                checked = dns.mode == DnsConfig.AUTO || dns.leakProtection, enabled = dns.mode != DnsConfig.AUTO,
                onCheckedChange = { save(dns.copy(leakProtection = it)) })
        }

        SectionLabel("Test")
        val t = st.dnsTest
        GlassButton(if (t.running) "Testing…" else "Test DNS now", Modifier.fillMaxWidth(), ButtonStyle.Secondary, icon = TIcons.pulse,
            loading = t.running, enabled = !t.running) { Core.runDnsTest() }
        if (t.items.isNotEmpty()) {
            VSpace(10)
            GlassCard(padding = PaddingValues(vertical = 6.dp)) {
                t.items.forEachIndexed { i, item ->
                    if (i > 0) RowDivider(52.dp)
                    ResultRow(item.name, item.ok, item.detail, item.ms)
                }
            }
            VSpace(6)
            Muted("Tested " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(t.at)), Modifier.padding(horizontal = 6.dp))
        }

        // ---------------- filtering
        SectionLabel("Filtering")
        GroupCard {
            BlockLists.CATEGORIES.forEachIndexed { i, cat ->
                if (i > 0) RowDivider()
                val info = st.lists[cat.id]
                val on = cat.id in dns.filters
                SettingRow(
                    when (cat.id) { BlockLists.ADS -> TIcons.eyeOff; BlockLists.TRACKERS -> TIcons.filter; BlockLists.MALWARE -> TIcons.bug; else -> TIcons.alert },
                    cat.name,
                    when {
                        cat.id in st.listsUpdating -> "Downloading…"
                        info != null && info.count > 0 -> "%,d domains · updated %s".format(info.count, DateFormat.getDateInstance(DateFormat.SHORT).format(Date(info.updatedAt)))
                        else -> cat.about
                    },
                    checked = on, onCheckedChange = { Core.setFilter(cat.id, it) },
                )
            }
        }
        if (dns.filters.isNotEmpty()) {
            VSpace(8)
            GlassButton(if (st.listsUpdating.isNotEmpty()) "Updating lists…" else "Update lists now", Modifier.fillMaxWidth(), ButtonStyle.Secondary,
                icon = TIcons.refresh, loading = st.listsUpdating.isNotEmpty()) { Core.updateListsNow() }
        }
        VSpace(10)
        DomainListCard("Always block", "Domains to block (one per line). Subdomains are included.", dns.blockList) { Core.setDomainList(it, dns.allowList) }
        VSpace(10)
        DomainListCard("Never block", "Domains that are never blocked, even if a list has them.", dns.allowList) { Core.setDomainList(dns.blockList, it) }

        // ---------------- profiles
        SectionLabel("Profiles")
        ProfilesCard(st)
    }
}

private fun ipKind(s: String): String? = when {
    s.isBlank() -> null
    DnsConfig.isIpv4(s) -> "IPv4 address ✓"
    DnsConfig.isIp(s) -> "IPv6 address ✓"
    else -> "Not a valid IPv4 or IPv6 address"
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, color = C.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = Fonts.display, maxLines = 1)
        Text(label, color = C.muted, fontSize = 11.sp)
    }
}

@Composable
private fun DomainListCard(title: String, about: String, list: List<String>, onSave: (List<String>) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable(list) { mutableStateOf(list.joinToString("\n")) }
    GlassCard(onClick = if (open) null else ({ open = true })) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = C.text, fontWeight = FontWeight.SemiBold)
                Muted(if (list.isEmpty()) about else "${list.size} domain${if (list.size == 1) "" else "s"}: ${list.take(3).joinToString(", ")}")
            }
            Icon(if (open) TIcons.down else TIcons.edit, null, tint = C.muted, modifier = Modifier.size(18.dp))
        }
        if (open) {
            VSpace(10)
            GlassInput(text, { text = it }, "example.com\nads.example.net", singleLine = false, minLines = 4)
            VSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Cancel", Modifier.weight(1f), ButtonStyle.Secondary) { text = list.joinToString("\n"); open = false }
                GlassButton("Save", Modifier.weight(1f)) {
                    onSave(text.split(Regex("[\\s,;]+")).filter { it.isNotBlank() })
                    open = false
                }
            }
        }
    }
}

@Composable
private fun ProfilesCard(st: AppState) {
    val p = st.prefs
    var name by rememberSaveable { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var renaming by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    if (p.dnsProfiles.isNotEmpty()) {
        GroupCard {
            p.dnsProfiles.forEachIndexed { i, prof ->
                if (i > 0) RowDivider(16.dp)
                val inUse = prof.id == p.dnsProfile
                Row(Modifier.fillMaxWidth().clickable { Core.applyDnsProfile(prof.id) }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioDot(inUse)
                    HSpace(12)
                    Column(Modifier.weight(1f)) {
                        Text(prof.name + if (prof.id == p.dnsDefaultProfile) "  · default" else "", color = C.text, fontWeight = FontWeight.SemiBold)
                        Muted(prof.config.label + if (prof.config.filters.isNotEmpty()) " · blocks ${prof.config.filters.size} list(s)" else "")
                    }
                    Icon(if (prof.id == p.dnsDefaultProfile) TIcons.starFilled else TIcons.star,
                        if (prof.id == p.dnsDefaultProfile) "Remove as default" else "Use at startup",
                        tint = if (prof.id == p.dnsDefaultProfile) C.amber else C.muted,
                        modifier = Modifier.size(44.dp).clip(CircleShape).clickable { Core.setDefaultDnsProfile(prof.id) }.padding(12.dp))
                    Icon(TIcons.edit, "Rename or update ${prof.name}", tint = C.muted,
                        modifier = Modifier.size(44.dp).clip(CircleShape).clickable { renaming = prof.id; name = prof.name }.padding(12.dp))
                    Icon(TIcons.trash, "Delete ${prof.name}", tint = C.red,
                        modifier = Modifier.size(44.dp).clip(CircleShape).clickable { confirmDelete = prof.id }.padding(12.dp))
                }
            }
        }
        VSpace(10)
    }
    GlassCard {
        Text(if (renaming.isNotEmpty()) "Update profile" else "Save current setup as a profile", color = C.text, fontWeight = FontWeight.SemiBold)
        VSpace(2)
        Muted(if (renaming.isNotEmpty()) "Saves the current DNS setup and name into this profile." else "Profiles keep a resolver, leak protection and filtering together so you can switch in one tap.")
        VSpace(10)
        GlassInput(name, { name = it }, "Profile name, e.g. Home or Work")
        VSpace(10)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (renaming.isNotEmpty()) GlassButton("Cancel", Modifier.weight(1f), ButtonStyle.Secondary) { renaming = ""; name = "" }
            GlassButton(if (renaming.isNotEmpty()) "Update" else "Save profile", Modifier.weight(1f), enabled = name.isNotBlank()) {
                val e = Core.saveDnsProfile(name, renaming.ifEmpty { null })
                msg = e ?: "Saved \"${name.trim()}\"."
                if (e == null) { name = ""; renaming = "" }
            }
        }
        if (msg.isNotEmpty()) { VSpace(8); Muted(msg) }
    }
    confirmDelete?.let { id ->
        val prof = p.dnsProfiles.firstOrNull { it.id == id }
        GlassDialog("Delete profile?", "\"${prof?.name}\" will be removed. Your current DNS setup stays as it is.", "Delete",
            onConfirm = { Core.deleteDnsProfile(id); confirmDelete = null }, onDismiss = { confirmDelete = null }, danger = true)
    }
}
