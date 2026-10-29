package com.tervyn.vpn.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tervyn.vpn.BuildConfig
import com.tervyn.vpn.core.AppState
import com.tervyn.vpn.core.Core
import com.tervyn.vpn.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

val SOURCE_OPTIONS = listOf(
    Prefs.SOURCE_FREE to "Free public servers",
    Prefs.SOURCE_PROXIO to "Proxio.io servers (your API key)",
    Prefs.SOURCE_BOTH to "Proxio.io + free servers",
    Prefs.SOURCE_OWN to "Your own servers (VLESS, Trojan…)",
    Prefs.SOURCE_CUSTOM to "Proxy integration (curl / Python / Node.js)",
)

/** Example code shown in the empty box (placeholders, not real logins). */
private val INTEGRATION_EXAMPLES = mapOf(
    "curl" to "curl --proxy \"http://USER:PASS@1.2.3.4:6014/\" https://ipv4.webshare.io/",
    "python" to "import requests\nrequests.get(\"https://ipv4.webshare.io/\", proxies={\n  \"http\": \"http://USER:PASS@1.2.3.4:6014/\",\n  \"https\": \"http://USER:PASS@1.2.3.4:6014/\"})",
    "node" to "axios.get('https://ipv4.webshare.io/', { proxy: {\n  protocol: 'http', host: '1.2.3.4', port: 6014,\n  auth: { username: 'USER', password: 'PASS' } } })",
)

@Composable
fun SettingsScreen(st: AppState) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 4.dp)) {
        SectionLabel("Server source")
        OptionPicker(st.prefs.source, SOURCE_OPTIONS, Core::setSource)
        if (st.prefs.source == Prefs.SOURCE_OWN && st.own.isEmpty()) {
            VSpace(6)
            Muted("Add your servers under Servers → Advanced: add your own servers.")
        }

        VSpace(10)
        ProxioCard(st)
        VSpace(10)
        IntegrationCard(st)

        SectionLabel("Backup servers kept ready")
        Segmented(listOf("2", "3", "5", "8").map { it to "$it servers" }, st.prefs.autoPool.toString()) {
            Core.setAutoPool(it.toInt())
        }
        VSpace(6)
        Muted("Auto mode uses the fastest server and switches to a backup instantly if it stops working.")

        SectionLabel("Preferred country")
        CountryPicker(st.prefs.country, countryList(st), "Any country (fastest)", Core::setCountry)

        SectionLabel("Xray core")
        Card {
            if (st.xrayVersion.isNotEmpty()) {
                Text("Included ✓  Xray ${st.xrayVersion}", color = C.green, fontWeight = FontWeight.SemiBold)
                VSpace(4)
                Muted("Your own VLESS, VMess, Trojan and Shadowsocks servers work. Tervyn starts Xray only when one of them is in use or being tested.")
            } else if (Core.xrayIncluded) {
                Text("Included", color = C.text, fontWeight = FontWeight.SemiBold)
            } else {
                Text("Not included in this build", color = C.amber, fontWeight = FontWeight.SemiBold)
                VSpace(4)
                Muted("VLESS / VMess / Trojan / Shadowsocks servers need Xray. Install the APK built by GitHub Actions, which includes it.")
            }
        }

        SectionLabel("Always-on VPN")
        Card {
            Text("Connect automatically and block the internet when the VPN is off", color = C.text, fontWeight = FontWeight.SemiBold)
            VSpace(4)
            Muted("In Android's VPN settings, tap the gear next to Tervyn VPN and turn on \"Always-on VPN\" (and optionally \"Block connections without VPN\").")
            VSpace(10)
            GhostButton("Open VPN settings", Modifier.fillMaxWidth()) {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
            }
        }

        SectionLabel("Quick Settings tile")
        Card {
            Muted("Swipe down twice, tap the pencil (edit) and drag \"Tervyn VPN\" into your tiles to connect with one tap.")
        }

        SectionLabel("Privacy and safety")
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Muted("• No accounts, ads or tracking. Your settings and API key stay on your phone.")
                Muted("• Free public servers are run by unknown people. Secure (https) sites stay encrypted end to end, and Tervyn only keeps servers that pass a secure-site check.")
                Muted("• Avoid banking and other sensitive logins through free servers. \"Only my sites\" mode is the safest way to use them.")
                Muted("• Video calls and some games use UDP, which these servers can't carry. They fall back to TCP or use your normal connection in \"Only my sites\" mode.")
            }
        }

        VSpace(16)
        Text("Tervyn VPN ${BuildConfig.VERSION_NAME}", color = C.muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
        VSpace(20)
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
    var msg by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun launchWork(work: () -> String) {
        msg = ""
        running = true
        scope.launch {
            msg = try { withContext(Dispatchers.IO) { work() } } catch (e: Exception) { e.message ?: e.toString() }
            running = false
        }
    }

    Card {
        Text("Proxio.io API key", color = C.text, fontWeight = FontWeight.SemiBold)
        VSpace(2)
        Muted("Use your own Proxio.io servers. The key stays on this phone and is only sent to proxio.io.")
        VSpace(10)
        Field(
            key, { key = it },
            if (hasKey) "Saved key ${cfg.keyHint} — paste a new one to change" else "Paste your API key",
            Modifier.fillMaxWidth(), password = true,
        )
        VSpace(8)
        Segmented(listOf("socks5" to "SOCKS5", "http" to "HTTP", "both" to "Both"), type) { type = it }
        VSpace(8)
        Segmented(listOf(25, 50, 100, 200).map { it.toString() to "$it servers" }, limit.toString()) { limit = it.toInt() }
        VSpace(10)
        val busy = running || ps.busy
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(if (busy) "Loading…" else "Save & load", Modifier.weight(1.4f), enabled = !busy && (hasKey || key.isNotBlank())) {
                val k = key
                key = ""
                launchWork {
                    val r = Core.proxioSave(k, type, limit)
                    if (r.working > 0) "Loaded ${r.working} working servers." else r.error
                }
            }
            if (hasKey) {
                GhostButton("Reload", Modifier.weight(1f)) {
                    if (!busy) launchWork { Core.refreshProxio().let { r -> if (r.working > 0) "Loaded ${r.working} working servers." else r.error } }
                }
                GhostButton("Remove", Modifier.weight(1f), color = C.red) {
                    if (!busy) { Core.proxioRemove(); key = ""; msg = "API key removed." }
                }
            }
        }
        val line = when {
            ps.state == "loading" -> "Downloading your Proxio.io list…" to C.muted
            ps.state == "testing" -> "Testing ${ps.fetched} Proxio.io servers…" to C.muted
            ps.error.isNotEmpty() && hasKey -> ps.error to C.red
            hasKey && ps.at > 0 -> "${st.proxio.count { it.ms != null }} of ${ps.fetched} Proxio.io servers working · updated " +
                DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(ps.at)) to C.green
            msg.isNotEmpty() -> msg to C.muted
            else -> "" to C.muted
        }
        if (line.first.isNotEmpty()) {
            VSpace(10)
            Text(line.first, color = line.second, fontSize = 12.sp)
        }
    }
}

@Composable
private fun IntegrationCard(st: AppState) {
    val cs = st.customStatus
    val has = st.custom.isNotEmpty()
    var lang by rememberSaveable { mutableStateOf("curl") }
    var code by rememberSaveable { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun launchWork(work: () -> String) {
        msg = ""
        running = true
        scope.launch {
            msg = try { withContext(Dispatchers.IO) { work() } } catch (e: Exception) { e.message ?: e.toString() }
            running = false
        }
    }

    Card {
        Text("Proxy integration", color = C.text, fontWeight = FontWeight.SemiBold)
        VSpace(2)
        Muted("Paste the curl, Python or Node.js example from your proxy provider (Webshare, Bright Data, Oxylabs, IPRoyal…). Tervyn finds the proxy address and login in it. The code stays on this phone.")
        VSpace(10)
        Segmented(listOf("curl" to "curl", "python" to "Python", "node" to "Node.js"), lang) { lang = it }
        VSpace(8)
        Field(
            code, { code = it },
            if (has && code.isEmpty()) "Saved: ${st.custom.size} prox${if (st.custom.size == 1) "y" else "ies"}. Paste new code to replace.\n\nExample:\n${INTEGRATION_EXAMPLES[lang]}"
            else "Example:\n${INTEGRATION_EXAMPLES[lang]}",
            Modifier.fillMaxWidth(), singleLine = false, minLines = 4,
        )
        VSpace(10)
        val busy = running || cs.busy
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(if (busy) "Testing…" else "Save & test", Modifier.weight(1.4f), enabled = !busy && code.isNotBlank()) {
                val c = code
                launchWork {
                    val r = Core.customSave(c)
                    if (r.working > 0) "${r.working} of ${r.fetched} working. Server source is now \"Proxy integration\"." else r.error
                }
                code = ""
            }
            if (has) {
                GhostButton("Re-test", Modifier.weight(1f)) {
                    if (!busy) launchWork { Core.testCustom().let { r -> if (r.working > 0) "${r.working} of ${r.fetched} working." else r.error } }
                }
                GhostButton("Remove", Modifier.weight(1f), color = C.red) {
                    if (!busy) { Core.customRemove(); code = ""; msg = "Removed." }
                }
            }
        }
        val line = when {
            cs.state == "testing" -> "Testing ${cs.fetched} prox${if (cs.fetched == 1) "y" else "ies"}…" to C.muted
            msg.isNotEmpty() -> msg to (if (st.custom.any { it.ms != null }) C.green else C.red)
            cs.error.isNotEmpty() && has -> cs.error to C.red
            has && cs.at > 0 -> "${cs.working} of ${cs.fetched} working · tested " +
                DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(cs.at)) to C.green
            else -> "" to C.muted
        }
        if (line.first.isNotEmpty()) {
            VSpace(10)
            Text(line.first, color = line.second, fontSize = 12.sp)
        }
        if (has) {
            VSpace(8)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in st.custom) {
                    CardRow {
                        FlagBadge(s.flag, 30)
                        Column(Modifier.weight(1f)) {
                            Text("${s.proto.uppercase()} ${s.hostPort}", color = C.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Muted(listOfNotNull(
                                s.username.takeIf { it.isNotEmpty() }?.let { "login $it" },
                                s.country.takeIf { it != "Unknown" },
                            ).joinToString(" · ").ifEmpty { "no login" })
                        }
                        PingBadge(s.ms, s.testedAt > 0)
                    }
                }
            }
        }
    }
}
