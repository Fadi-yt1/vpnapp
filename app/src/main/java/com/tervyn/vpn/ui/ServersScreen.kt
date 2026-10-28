package com.tervyn.vpn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tervyn.vpn.core.AppState
import com.tervyn.vpn.core.Core
import com.tervyn.vpn.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun ServersScreen(st: AppState, goHome: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var country by rememberSaveable { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    val fs = st.freeStatus
    val ps = st.proxioStatus
    val src = st.prefs.source
    val busy = when (src) {
        Prefs.SOURCE_OWN -> st.ownTesting
        Prefs.SOURCE_PROXIO -> ps.busy
        Prefs.SOURCE_BOTH -> ps.busy || fs.busy
        else -> fs.busy
    }

    val list = st.servers.distinctBy { it.id }
        .filter { country.isEmpty() || it.code == country }
        .filter { s -> query.isBlank() || "${s.name} ${s.address} ${s.country} ${s.proto}".lowercase().contains(query.trim().lowercase()) }
        .sortedBy { it.ms ?: if (it.testedAt == 0L) 50_000 else 100_000 }
    val shown = if (showAll) list else list.take(60)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Field(query, { query = it }, "Search country, host…", Modifier.fillMaxWidth(), leading = TIcons.search)
        }
        item {
            CountryPicker(country, countryList(st), "All countries") { country = it }
        }
        item {
            PrimaryButton(if (busy) "Testing…" else "Find fastest servers", Modifier.fillMaxWidth().padding(top = 4.dp), enabled = !busy) {
                Core.testAll()
            }
        }
        item {
            val freeLine = when {
                fs.state == "loading" -> "Downloading free server lists…"
                fs.state == "testing" -> "Testing free servers… ${fs.progress}% · ${fs.working} working so far"
                fs.error.isNotEmpty() -> fs.error
                fs.at > 0 -> "${st.free.count { it.ms != null }} fast free servers ready (checked ${fs.found}). " +
                    "Updated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(fs.at))} · refreshes every 30 min."
                else -> "Free servers are being prepared…"
            }
            val proxioLine = when {
                st.proxioConfig.key.isBlank() -> "Add your Proxio.io API key in Settings."
                ps.state == "loading" -> "Downloading your Proxio.io list…"
                ps.state == "testing" -> "Testing ${ps.fetched} Proxio.io servers…"
                ps.error.isNotEmpty() -> ps.error
                else -> "${st.proxio.count { it.ms != null }} of ${ps.fetched} Proxio.io servers working."
            }
            val ownLine = when {
                st.own.isEmpty() -> "Source: your own servers. Add them below."
                st.ownTesting -> "Testing your servers…"
                else -> "${st.own.count { it.ms != null }} of ${st.own.size} of your servers working."
            }
            Muted(
                when (src) {
                    Prefs.SOURCE_OWN -> ownLine
                    Prefs.SOURCE_PROXIO -> proxioLine
                    Prefs.SOURCE_BOTH -> "$proxioLine\n$freeLine"
                    else -> freeLine
                },
                Modifier.fillMaxWidth().padding(vertical = 6.dp), center = true,
            )
        }
        item {
            ServerRow(null, st.prefs.selected == "auto") { Core.select("auto"); goHome() }
        }
        items(shown, key = { it.id }) { s ->
            ServerRow(s, st.prefs.selected == s.id) { Core.select(s.id); goHome() }
        }
        if (list.size > shown.size) item {
            GhostButton("Show all ${list.size} servers", Modifier.fillMaxWidth()) { showAll = true }
        }
        if (st.servers.isEmpty()) item {
            Muted("Finding free servers for you… this takes about 30 seconds the first time.", Modifier.fillMaxWidth().padding(12.dp), center = true)
        }
        item { OwnServers(st) }
    }
}

@Composable
private fun OwnServers(st: AppState) {
    var open by rememberSaveable { mutableStateOf(st.sources.isNotEmpty()) }
    var url by rememberSaveable { mutableStateOf("") }
    var paste by rememberSaveable { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun run(work: () -> Core.ImportResult, after: () -> Unit) {
        msg = "Importing…"
        scope.launch {
            msg = try {
                val r = withContext(Dispatchers.IO) { work() }
                after()
                "Added ${r.added} servers" + (if (r.core > 0) " (${r.core} VLESS / VMess / Trojan / SS)" else "") +
                    (if (r.skipped > 0) ", ${r.skipped} skipped (unsupported settings)" else "") + ". " +
                    (if (r.switched) "Server source switched to \"Your own servers\". " else "") + "Testing them now…"
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
        }
    }

    Column(Modifier.padding(top = 10.dp)) {
        Card(onClick = { open = !open }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Advanced: add your own servers (optional)", color = C.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(if (open) TIcons.down else TIcons.chevron, null, tint = C.muted, modifier = Modifier.size(18.dp))
            }
        }
        if (open) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted("Optional. Paste share links or a subscription link: VLESS, VMess, Trojan, Shadowsocks (through the built-in Xray core), SOCKS5 and HTTP. Imported servers are tested, and the fastest is used when Server source is \"Your own servers\".")
                SectionLabel("Server list link (refreshes every 30 min)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field(url, { url = it }, "https://…", Modifier.weight(1f))
                    PrimaryButton("Add", enabled = url.isNotBlank()) { val u = url.trim(); run({ Core.addListUrl(u) }) { url = "" } }
                }
                SectionLabel("Or paste servers")
                Field(paste, { paste = it }, "vless://…  vmess://…  trojan://…  ss://…  socks5://…", Modifier.fillMaxWidth(), singleLine = false, minLines = 3)
                GhostButton("Import text", Modifier.fillMaxWidth()) {
                    val t = paste
                    if (t.isNotBlank()) run({ Core.importPasted(t) }) { paste = "" }
                }
                if (msg.isNotEmpty()) Muted(msg)
                for (src in st.sources) {
                    CardRow {
                        Column(Modifier.weight(1f)) {
                            Text(src.name, color = C.text, fontWeight = FontWeight.SemiBold)
                            Muted("${src.count} servers · updated ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(src.updatedAt))}" +
                                if (src.type == "url") " · auto-updates" else "")
                        }
                        Text("Remove", color = C.red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { Core.removeSource(src.id) }.padding(8.dp))
                    }
                }
            }
        }
    }
}
