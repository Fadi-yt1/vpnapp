package com.tervyn.vpn.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tervyn.vpn.core.AppState
import com.tervyn.vpn.core.Core
import com.tervyn.vpn.core.Prefs
import com.tervyn.vpn.core.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val SOURCE_OPTIONS = listOf(
    Triple(Prefs.SOURCE_FREE, "Free public servers", "Found and tested automatically"),
    Triple(Prefs.SOURCE_PROXIO, "Proxio.io", "Servers from your Proxio.io API key"),
    Triple(Prefs.SOURCE_BOTH, "Proxio.io + free", "Both, fastest first"),
    Triple(Prefs.SOURCE_OWN, "Your own servers", "WireGuard, VLESS, VMess, Trojan, Shadowsocks, SOCKS5, HTTP"),
    Triple(Prefs.SOURCE_CUSTOM, "Proxy integration", "From your provider's curl / Python / Node.js example"),
    Triple(Prefs.SOURCE_LIST, "Proxy list", "A .txt file with one proxy per line"),
)

/** Example code shown in the empty box (placeholders, not real logins). */
private val INTEGRATION_EXAMPLES = mapOf(
    "curl" to "curl --proxy \"http://USER:PASS@1.2.3.4:6014/\" https://ipv4.webshare.io/",
    "python" to "import requests\nrequests.get(\"https://ipv4.webshare.io/\", proxies={\n  \"http\": \"http://USER:PASS@1.2.3.4:6014/\",\n  \"https\": \"http://USER:PASS@1.2.3.4:6014/\"})",
    "node" to "axios.get('https://ipv4.webshare.io/', { proxy: {\n  protocol: 'http', host: '1.2.3.4', port: 6014,\n  auth: { username: 'USER', password: 'PASS' } } })",
)

/** Runs [work] off the main thread and shows its message. */
private class Worker(val scope: kotlinx.coroutines.CoroutineScope) {
    var msg by mutableStateOf("")
    var running by mutableStateOf(false)
    fun run(work: () -> String) {
        msg = ""
        running = true
        scope.launch {
            msg = try { withContext(Dispatchers.IO) { work() } } catch (e: Exception) { e.message ?: e.toString() }
            running = false
        }
    }
}

@Composable
private fun rememberWorker(): Worker {
    val scope = rememberCoroutineScope()
    return remember { Worker(scope) }
}

private fun readText(ctx: Context, uri: Uri): Pair<String, String>? {
    val name = runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "Imported file"
    val text = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().let { b -> b.copyOf(minOf(b.size, 2_000_000)) }.toString(Charsets.UTF_8) }
    }.getOrNull() ?: return null
    return name to text
}

@Composable
fun SourcesScreen(st: AppState, nav: Nav) {
    Page("Server sources", nav, sub = true, subtitle = "Where Smart Connect gets its servers") {
        GroupCard {
            SOURCE_OPTIONS.forEachIndexed { i, (id, name, about) ->
                if (i > 0) RowDivider(52.dp)
                ChoiceRow(name, about, st.prefs.source == id, { Core.setSource(id) })
            }
        }
        VSpace(6)
        Muted(sourceStatus(st), Modifier.padding(horizontal = 6.dp))

        SectionLabel("Your own servers")
        OwnServersCard(st)
        SectionLabel("Proxio.io")
        ProxioCard(st)
        SectionLabel("Proxy integration")
        IntegrationCard(st)
        SectionLabel("Proxy list")
        ProxyListCard(st)
    }
}

@Composable
private fun StatusLine(text: String, color: androidx.compose.ui.graphics.Color) {
    if (text.isEmpty()) return
    VSpace(10)
    Text(text, color = color, fontSize = 12.sp)
}

@Composable
private fun ProxyRow(s: Server) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        FlagBadge(s.flag, 30)
        HSpace(10)
        Column(Modifier.weight(1f)) {
            Text("${protoName(s.proto)} ${s.hostPort}", color = C.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Muted(listOfNotNull(
                s.username.takeIf { it.isNotEmpty() }?.let { "login $it" },
                s.country.takeIf { it != "Unknown" },
            ).joinToString(" · ").ifEmpty { "no login" })
        }
        PingBadge(s.ms, s.testedAt > 0)
    }
}

@Composable
private fun OwnServersCard(st: AppState) {
    val ctx = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }
    var paste by rememberSaveable { mutableStateOf("") }
    val w = rememberWorker()

    fun run(work: () -> Core.ImportResult, after: () -> Unit) = w.run {
        val r = work()
        after()
        "Added ${r.added} servers" + (if (r.core > 0) " (${r.core} WireGuard / VLESS / VMess / Trojan / SS)" else "") +
            (if (r.skipped > 0) ", ${r.skipped} skipped (unsupported settings)" else "") + ". " +
            (if (r.switched) "Server source switched to \"Your own servers\". " else "") + "Testing them now…"
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val f = readText(ctx, uri)
            if (f == null) w.msg = "Couldn't read that file." else run({ Core.importPasted(f.second) }) {}
        }
    }

    GlassCard {
        Muted("Paste share links, a subscription link or a WireGuard config (.conf). Imported servers are tested and the fastest is used when the source is \"Your own servers\".")
        VSpace(12)
        GlassInput(paste, { paste = it }, "wireguard://…  vless://…  vmess://…  trojan://…  ss://…  socks5://…\nor a [Interface] / [Peer] config",
            singleLine = false, minLines = 3)
        VSpace(10)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Import", Modifier.weight(1f), enabled = paste.isNotBlank()) { val t = paste; run({ Core.importPasted(t) }) { paste = "" } }
            GlassButton("Open file…", Modifier.weight(1f), ButtonStyle.Secondary, icon = TIcons.upload) {
                picker.launch(arrayOf("text/*", "application/octet-stream", "application/x-wireguard-profile"))
            }
        }
        VSpace(14)
        Text("Server list link (refreshes every 30 min)", color = C.label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        VSpace(6)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassInput(url, { url = it }, "https://…", Modifier.weight(1f))
            GlassButton("Add", enabled = url.isNotBlank()) { val u = url.trim(); run({ Core.addListUrl(u) }) { url = "" } }
        }
        if (w.running) { VSpace(10); GlassProgressBar(null) }
        StatusLine(w.msg, C.muted)
        for (src in st.sources) {
            VSpace(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(if (src.type == "url") TIcons.globe else TIcons.list, size = 32.dp)
                HSpace(10)
                Column(Modifier.weight(1f)) {
                    Text(src.name, color = C.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted("${src.count} servers · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(src.updatedAt))}" +
                        if (src.type == "url") " · auto-updates" else "")
                }
                Icon(TIcons.trash, "Remove ${src.name}", tint = C.red,
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable { Core.removeSource(src.id) }.padding(12.dp))
            }
        }
    }
}

@Composable
private fun ProxioCard(st: AppState) {
    val cfg = st.proxioConfig
    val ps = st.proxioStatus
    val hasKey = cfg.key.isNotBlank()
    var key by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable(cfg.type) { mutableStateOf(cfg.type) }
    var limit by rememberSaveable(cfg.limit) { mutableStateOf(cfg.limit) }
    val w = rememberWorker()
    val busy = w.running || ps.busy

    GlassCard {
        Muted("Use your own Proxio.io servers. The key stays on this phone and is only sent to proxio.io.")
        VSpace(12)
        GlassInput(key, { key = it }, if (hasKey) "Saved key ${cfg.keyHint} — paste a new one to change" else "Paste your API key", password = true)
        VSpace(10)
        GlassTabBar(listOf("socks5" to "SOCKS5", "http" to "HTTP", "both" to "Both"), type, { type = it })
        VSpace(8)
        GlassTabBar(listOf(25, 50, 100, 200).map { it.toString() to "$it" }, limit.toString(), { limit = it.toInt() })
        VSpace(12)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(if (busy) "Loading…" else "Save & load", Modifier.weight(1.3f), loading = busy, enabled = !busy && (hasKey || key.isNotBlank())) {
                val k = key
                key = ""
                w.run { val r = Core.proxioSave(k, type, limit); if (r.working > 0) "Loaded ${r.working} working servers." else r.error }
            }
            if (hasKey) {
                GlassButton("Reload", Modifier.weight(1f), ButtonStyle.Secondary, enabled = !busy) {
                    w.run { Core.refreshProxio().let { r -> if (r.working > 0) "Loaded ${r.working} working servers." else r.error } }
                }
                GlassButton("Remove", Modifier.weight(1f), ButtonStyle.Danger, enabled = !busy) { Core.proxioRemove(); key = ""; w.msg = "API key removed." }
            }
        }
        val line = when {
            ps.state == "loading" -> "Downloading your Proxio.io list…" to C.muted
            ps.state == "testing" -> "Testing ${ps.fetched} Proxio.io servers…" to C.muted
            ps.error.isNotEmpty() && hasKey -> ps.error to C.red
            hasKey && ps.at > 0 -> "${st.proxio.count { it.ms != null }} of ${ps.fetched} Proxio.io servers working · updated " +
                DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(ps.at)) to C.green
            else -> w.msg to C.muted
        }
        StatusLine(line.first, line.second)
    }
}

@Composable
private fun IntegrationCard(st: AppState) {
    val cs = st.customStatus
    val has = st.custom.isNotEmpty()
    var lang by rememberSaveable { mutableStateOf("curl") }
    var code by rememberSaveable { mutableStateOf("") }
    val w = rememberWorker()
    val busy = w.running || cs.busy

    GlassCard {
        Muted("Paste the curl, Python or Node.js example from your proxy provider (Webshare, Bright Data, Oxylabs, IPRoyal…). Tervyn finds the proxy address and login in it. The code stays on this phone.")
        VSpace(12)
        GlassTabBar(listOf("curl" to "curl", "python" to "Python", "node" to "Node.js"), lang, { lang = it })
        VSpace(10)
        GlassInput(
            code, { code = it },
            if (has && code.isEmpty()) "Saved: ${st.custom.size} prox${if (st.custom.size == 1) "y" else "ies"}. Paste new code to replace.\n\nExample:\n${INTEGRATION_EXAMPLES[lang]}"
            else "Example:\n${INTEGRATION_EXAMPLES[lang]}",
            singleLine = false, minLines = 4,
        )
        VSpace(12)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(if (busy) "Testing…" else "Save & test", Modifier.weight(1.3f), loading = busy, enabled = !busy && code.isNotBlank()) {
                val c = code
                code = ""
                w.run { val r = Core.customSave(c); if (r.working > 0) "${r.working} of ${r.fetched} working. Server source is now \"Proxy integration\"." else r.error }
            }
            if (has) {
                GlassButton("Re-test", Modifier.weight(1f), ButtonStyle.Secondary, enabled = !busy) {
                    w.run { Core.testCustom().let { r -> if (r.working > 0) "${r.working} of ${r.fetched} working." else r.error } }
                }
                GlassButton("Remove", Modifier.weight(1f), ButtonStyle.Danger, enabled = !busy) { Core.customRemove(); code = ""; w.msg = "Removed." }
            }
        }
        val line = when {
            cs.state == "testing" -> "Testing ${cs.fetched} prox${if (cs.fetched == 1) "y" else "ies"}…" to C.muted
            w.msg.isNotEmpty() -> w.msg to (if (st.custom.any { it.ms != null }) C.green else C.red)
            cs.error.isNotEmpty() && has -> cs.error to C.red
            has && cs.at > 0 -> "${cs.working} of ${cs.fetched} working · tested " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(cs.at)) to C.green
            else -> "" to C.muted
        }
        StatusLine(line.first, line.second)
        if (has) {
            VSpace(6)
            for (s in st.custom) ProxyRow(s)
        }
    }
}

@Composable
private fun ProxyListCard(st: AppState) {
    val ctx = LocalContext.current
    val ls = st.listStatus
    val has = st.list.isNotEmpty()
    var type by rememberSaveable(has) { mutableStateOf(st.list.firstOrNull()?.proto ?: "http") }
    var pasteOpen by rememberSaveable { mutableStateOf(false) }
    var paste by rememberSaveable { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    val w = rememberWorker()
    val busy = w.running || ls.busy

    fun importList(text: String, name: String) = w.run {
        val r = Core.listImport(text, name, type)
        "Imported ${r.added} proxies" + (if (r.skipped > 0) " (${r.skipped} lines skipped)" else "") + ". " +
            if (r.status.working > 0) "${r.status.working} working. Server source is now \"Proxy list\"." else r.status.error
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val f = readText(ctx, uri)
            if (f == null) w.msg = "Couldn't read that file." else importList(f.second, f.first)
        }
    }

    GlassCard {
        Muted("Import the proxy list file from your provider (for example Webshare → Proxy list → Download). One proxy per line, like host:port:user:pass. The list stays on this phone.")
        VSpace(12)
        Text("Type of proxies in the file", color = C.label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        VSpace(6)
        GlassTabBar(listOf("http" to "HTTP", "socks5" to "SOCKS5"), type, { t ->
            type = t
            if (has && !busy) w.run { Core.listSetProto(t).let { r -> if (r.working > 0) "Switched to ${t.uppercase()}: ${r.working} of ${r.fetched} working." else r.error } }
        })
        VSpace(12)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(if (busy) "Testing…" else if (has) "Replace file…" else "Choose .txt file", Modifier.weight(1.3f), icon = TIcons.upload,
                loading = busy, enabled = !busy) { picker.launch(arrayOf("text/*", "application/octet-stream")) }
            GlassButton(if (pasteOpen) "Hide" else "Paste", Modifier.weight(1f), ButtonStyle.Secondary) { pasteOpen = !pasteOpen }
        }
        if (pasteOpen) {
            VSpace(10)
            GlassInput(paste, { paste = it }, "1.2.3.4:6014:user:pass\n5.6.7.8:6015:user:pass", singleLine = false, minLines = 4)
            VSpace(10)
            GlassButton("Import pasted list", Modifier.fillMaxWidth(), enabled = !busy && paste.isNotBlank()) {
                val t = paste
                paste = ""
                pasteOpen = false
                importList(t, "Pasted list")
            }
        }
        if (has) {
            VSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Re-test", Modifier.weight(1f), ButtonStyle.Secondary, enabled = !busy) {
                    w.run { Core.testList().let { r -> if (r.working > 0) "${r.working} of ${r.fetched} working." else r.error } }
                }
                GlassButton("Remove list", Modifier.weight(1f), ButtonStyle.Danger, enabled = !busy) { Core.listRemove(); w.msg = "List removed." }
            }
        }
        val line = when {
            ls.state == "testing" -> "Testing ${ls.fetched} proxies…" to C.muted
            w.msg.isNotEmpty() -> w.msg to (if (st.list.any { it.ms != null }) C.green else C.red)
            ls.error.isNotEmpty() && has -> ls.error to C.red
            has && ls.at > 0 -> "${st.listName.ifEmpty { "Your list" }}: ${ls.working} of ${ls.fetched} working · tested " +
                DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(ls.at)) to C.green
            else -> "" to C.muted
        }
        StatusLine(line.first, line.second)
        if (has) {
            VSpace(6)
            val sorted = st.list.sortedBy { it.ms ?: if (it.testedAt == 0L) 50_000 else 100_000 }
            for (s in if (showAll) sorted else sorted.take(5)) ProxyRow(s)
            if (!showAll && sorted.size > 5) {
                VSpace(6)
                GlassButton("Show all ${sorted.size} proxies", Modifier.fillMaxWidth(), ButtonStyle.Secondary) { showAll = true }
            }
        }
    }
}
