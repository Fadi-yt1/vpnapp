package com.tervyn.vpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.tervyn.vpn.net.ProxyDeadException
import com.tervyn.vpn.net.ProxyDialer
import com.tervyn.vpn.net.ProxyTester
import com.tervyn.vpn.vpn.FakeDns
import com.tervyn.vpn.vpn.Router
import com.tervyn.vpn.vpn.TervynVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The app's brain (same job as background.js in the extension): finds and
 * tests free servers, picks the fastest, keeps backups ready, swaps out dead
 * ones and tells the VPN service where to send traffic.
 */
object Core {
    private const val TAG = "Tervyn"
    private const val KEEP_FREE = 40
    private const val FREE_REFRESH_MIN = 30L
    private const val HEALTH_MIN = 2L
    private const val IP_CHECK_HOST = "ipwho.is"

    private lateinit var app: Context
    private lateinit var store: Store
    private val lock = Any()

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val exec = Executors.newCachedThreadPool { r -> Thread(r, "tervyn-core").apply { isDaemon = true } }
    private val sched = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tervyn-sched").apply { isDaemon = true } }

    /** Stand-in addresses survive reconnects, so apps' cached DNS answers keep working. */
    val fakeDns = FakeDns()

    @Volatile private var chain: List<Server> = emptyList()   // servers in use (first = main, rest = backups)
    @Volatile private var refreshing: Thread? = null
    @Volatile private var connecting = false
    @Volatile private var checkingHealth = false
    @Volatile private var service: TervynVpnService? = null
    private val failTimes = ArrayDeque<Long>()

    private var listeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        app = context.applicationContext
        store = Store(app)
        val prefs = store.prefs()
        _state.value = AppState(
            prefs = prefs, free = store.freeServers(), own = store.ownServers(),
            sources = store.sources(), freeStatus = store.freeStatus(),
        )
        sched.scheduleWithFixedDelay({ safe { healthCheck() } }, HEALTH_MIN, HEALTH_MIN, TimeUnit.MINUTES)
        sched.scheduleWithFixedDelay({ safe { refreshListUrls(); refreshFree(force = true) } },
            FREE_REFRESH_MIN, FREE_REFRESH_MIN, TimeUnit.MINUTES)
    }

    /** Called on every state change (the VPN service and quick-settings tile use it). */
    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    private inline fun update(f: (AppState) -> AppState) {
        synchronized(lock) { _state.value = f(_state.value) }
        val ls = synchronized(listeners) { listeners.toList() }
        ls.forEach { runCatching { it() } }
    }

    private fun setStatus(f: (Status) -> Status) = update { it.copy(status = f(it.status)) }
    private fun setFree(f: (FreeStatus) -> FreeStatus) = update { it.copy(freeStatus = f(it.freeStatus)) }

    private fun savePrefs(f: (Prefs) -> Prefs): Prefs {
        var p: Prefs? = null
        update { s -> s.copy(prefs = f(s.prefs)).also { p = it.prefs } }
        store.savePrefs(p!!)
        return p!!
    }

    private fun saveFree(list: List<Server>) {
        update { it.copy(free = list) }
        store.saveFreeServers(list)
    }

    private fun safe(block: () -> Unit) = try { block() } catch (e: Exception) { Log.w(TAG, "task failed", e) }
    private fun bg(block: () -> Unit) = exec.execute { safe(block) }

    // =====================================================================
    // Free servers: find, test, keep the fastest
    // =====================================================================

    /** Start a search in the background if the list is old or short (called when the app opens). */
    fun ensureFree() = bg { refreshFree(force = false) }

    fun refreshFreeAsync() = bg { refreshFree(force = true) }

    val isRefreshing get() = refreshing != null

    fun refreshFree(force: Boolean) {
        synchronized(this) {
            if (refreshing != null) return
            refreshing = Thread.currentThread()
        }
        try {
            val st = _state.value
            if (!force && st.freeStatus.at > 0 && System.currentTimeMillis() - st.freeStatus.at < 10 * 60_000 &&
                st.free.count { it.ms != null } >= 5) return
            setFree { it.copy(state = "loading", error = "", progress = 0) }

            val fetched = FreeProxies.fetch(1500)
            val previous = _state.value.free
            if (fetched.candidates.isEmpty() && previous.isEmpty()) {
                throw IOException("Could not download the free server lists. Check your internet connection.")
            }
            // Servers that worked last time go first, so results come back fast.
            val prevIds = previous.map { it.id }.toSet()
            val pool = previous + fetched.candidates.filter { it.id !in prevIds }
            setFree { it.copy(state = "testing", found = pool.size, progress = 0, working = 0) }

            val working = ArrayList<Server>()
            val tested = HashSet<String>()
            var lastSave = 0L
            ProxyTester.testMany(pool, threads = 64, stopAfter = KEEP_FREE, maxTimeMs = 4 * 60_000L) { s, r ->
                tested += s.id
                if (r != null) {
                    working += s.copy(ms = r.ms, code = r.code.ifEmpty { s.code }, testedAt = System.currentTimeMillis())
                }
                val now = System.currentTimeMillis()
                if (r != null || now - lastSave > 1000) {
                    lastSave = now
                    val best = working.sortedBy { it.ms }.take(KEEP_FREE)
                    // Keep not-yet-retested old servers until the new list is big enough.
                    val keepOld = previous.filter { it.id !in tested && best.none { b -> b.id == it.id } }
                    update {
                        it.copy(
                            free = (best + keepOld).take(KEEP_FREE),
                            freeStatus = it.freeStatus.copy(progress = tested.size * 100 / pool.size, working = best.size),
                        )
                    }
                }
            }
            val final = working.sortedBy { it.ms }.take(KEEP_FREE)
            saveFree(final)
            val fs = FreeStatus(
                state = "idle", at = System.currentTimeMillis(), found = pool.size, working = final.size,
                sources = fetched.sourcesOk,
                error = if (final.isEmpty()) "No free server is answering right now. Tervyn will try again in a few minutes." else "",
            )
            update { it.copy(freeStatus = fs) }
            store.saveFreeStatus(fs)
            // Move an active connection onto the fresh list.
            val s = _state.value
            if (final.isNotEmpty() && s.on && s.prefs.selected == "auto" && !connecting) repick()
        } catch (e: Exception) {
            val fs = _state.value.freeStatus.copy(state = "idle", at = System.currentTimeMillis(), error = e.message ?: e.toString())
            update { it.copy(freeStatus = fs) }
            store.saveFreeStatus(fs)
        } finally {
            refreshing = null
        }
    }

    /** Re-checks the servers in use; swaps out dead ones and tops up the list. */
    private fun healthCheck() {
        val s = _state.value
        if (!s.on || refreshing != null || connecting || checkingHealth) return
        checkingHealth = true
        try {
            val inUse = chain
            if (inUse.isEmpty()) return
            val results = HashMap<String, ProxyTester.Result?>()
            ProxyTester.testMany(inUse, threads = inUse.size) { srv, r -> results[srv.id] = r }
            val now = System.currentTimeMillis()
            val dead = inUse.filter { results[it.id] == null }.map { it.id }.toSet()
            // Update delays shown in the app.
            fun upd(l: List<Server>) = l.mapNotNull { srv ->
                when {
                    srv.id in dead && srv.free -> null
                    srv.id in dead -> srv.copy(ms = null, testedAt = now)
                    results.containsKey(srv.id) -> srv.copy(ms = results[srv.id]!!.ms, testedAt = now)
                    else -> srv
                }
            }
            saveFree(upd(_state.value.free))
            update { it.copy(own = upd(it.own)) }
            store.saveOwnServers(_state.value.own)
            if (dead.isNotEmpty() && !connecting && refreshing == null) {
                if (inUse.first().id in dead) {
                    setStatus { it.copy(warning = if (_state.value.prefs.selected == "auto")
                        "A server stopped answering — switched to the next fastest one."
                    else "The server you picked isn't answering. Pick another one or use Fastest.") }
                }
                repick()
            }
            val left = _state.value.free.count { it.ms != null }
            if (left < 10 && System.currentTimeMillis() - _state.value.freeStatus.at > 5 * 60_000) refreshFree(force = true)
        } finally {
            checkingHealth = false
        }
    }

    // =====================================================================
    // Choosing servers
    // =====================================================================

    private fun pickServers(prefs: Prefs): List<Server> {
        val st = _state.value
        if (prefs.selected != "auto") {
            st.servers.firstOrNull { it.id == prefs.selected }?.let { return listOf(it) }
        }
        fun working() = _state.value.servers.filter { it.ms != null }
        fun inCountry(l: List<Server>) = if (prefs.country.isEmpty()) l
            else l.filter { it.code == prefs.country }.ifEmpty { l }

        var list = working()
        if (list.size < 3 && refreshing == null && System.currentTimeMillis() - st.freeStatus.at > 5 * 60_000) {
            refreshFreeAsync()
        }
        if (list.isEmpty()) {
            // Nothing yet: wait for the search and connect as soon as servers show up.
            if (refreshing == null) refreshFreeAsync()
            val start = System.currentTimeMillis()
            val deadline = start + 180_000
            var firstFoundAt = 0L
            while (System.currentTimeMillis() < deadline) {
                list = working()
                if (list.isNotEmpty() && firstFoundAt == 0L) firstFoundAt = System.currentTimeMillis()
                // Search finished (give it a moment to start first).
                val searchDone = refreshing == null && System.currentTimeMillis() - start > 3000
                // Connect with 2+ servers (one is a backup), or with 1 after a short wait.
                if (list.size >= 2 || searchDone || (firstFoundAt > 0 && System.currentTimeMillis() - firstFoundAt > 6000)) break
                if (!_state.value.prefs.enabled) return emptyList()
                Thread.sleep(1000)
            }
        }
        return inCountry(list).sortedBy { it.ms }.take(maxOf(2, prefs.autoPool))
    }

    private fun label(prefs: Prefs, servers: List<Server>): String {
        val first = servers.first()
        val place = if (first.country != "Unknown") first.country else first.name
        return if (prefs.selected == "auto") {
            "${first.flag} $place" + if (servers.size > 1) " + ${servers.size - 1} backup" else ""
        } else "${first.flag} $place"
    }

    /** Switch servers while connected, without dropping the VPN. */
    private fun repick() {
        val prefs = _state.value.prefs
        if (!_state.value.on) return
        val servers = pickServers(prefs)
        if (servers.isEmpty()) return
        chain = servers
        setStatus { it.copy(label = label(prefs, servers), serverIds = servers.map { s -> s.id }) }
    }

    // =====================================================================
    // Connect / disconnect (called by the VPN service)
    // =====================================================================

    /** The service is running and wants to connect (from the app, the tile or always-on VPN). */
    fun connect(svc: TervynVpnService) {
        service = svc
        if (connecting) return
        connecting = true
        bg {
            try {
                doConnect(svc)
            } finally {
                connecting = false
            }
        }
    }

    private fun doConnect(svc: TervynVpnService) {
        val prefs = savePrefs { it.copy(enabled = true) }
        val wasOn = _state.value.status.state == "on"
        if (!wasOn) setStatus { it.copy(state = "connecting", error = "", warning = "") }
        try {
            if (prefs.targeted && prefs.sites.isEmpty()) {
                throw IOException("\"Only my sites\" is on but the list is empty. Add a site in the Sites tab.")
            }
            val servers = pickServers(prefs)
            if (!_state.value.prefs.enabled) return   // user pressed stop while we were searching
            if (servers.isEmpty()) {
                throw IOException("No free server is answering right now. Tap \"Find fastest free servers\" in the Servers tab, or try again in a minute.")
            }
            chain = servers
            svc.startTunnel(fullMode = !prefs.targeted, router = router)
            setStatus {
                Status(state = "on", label = label(prefs, servers), serverIds = servers.map { it.id },
                    since = if (wasOn) it.since else System.currentTimeMillis())
            }
            update { it.copy(ipText = "") }
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            savePrefs { it.copy(enabled = false) }
            chain = emptyList()
            svc.stopTunnel()
            setStatus { Status(state = "off", error = e.message ?: e.toString()) }
        }
    }

    /** Stop button, tile, notification, or Android revoked the VPN. */
    fun disconnect() {
        savePrefs { it.copy(enabled = false) }
        chain = emptyList()
        service?.stopTunnel()
        service = null
        setStatus { Status(state = "off") }
        update { it.copy(ipText = "") }
    }

    fun serviceDestroyed(svc: TervynVpnService) {
        if (service !== svc) return
        service = null
        chain = emptyList()
        if (_state.value.status.state != "off") setStatus { Status(state = "off") }
    }

    // =====================================================================
    // Routing (used by the VPN engine for every connection)
    // =====================================================================

    val router = object : Router {
        override val fullMode: Boolean get() = !_state.value.prefs.targeted

        override fun shouldProxy(domain: String): Boolean {
            val p = _state.value.prefs
            return !p.targeted || siteMatches(domain, p.sites)
        }

        override fun connectProxied(host: String, port: Int): Socket {
            val servers = chain
            if (servers.isEmpty()) throw IOException("VPN is not connected")
            var last: IOException? = null
            // Like the browser: if a server fails, try the next one in the chain.
            for (s in servers.take(3)) {
                try {
                    return ProxyDialer.open(s, host, port, 8000)
                } catch (e: ProxyDeadException) {
                    last = e
                    noteFailure()
                }
            }
            throw last ?: IOException("No server")
        }

        override fun connectDirect(host: String, port: Int): Socket {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(InetAddress.getByName(host), port), 10_000)
            } catch (e: IOException) {
                runCatching { s.close() }
                throw e
            }
            return s
        }

        override fun upstreamDns(): List<InetAddress> {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val net = cm?.activeNetwork   // our own app is outside the VPN, so this is the real network
            val dns = net?.let { cm.getLinkProperties(it)?.dnsServers }.orEmpty()
                .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }
            return dns + listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("8.8.8.8"))
        }
    }

    /** Several failed connections in a short time: check the servers now instead of in 2 minutes. */
    private fun noteFailure() {
        val now = System.currentTimeMillis()
        val trigger = synchronized(failTimes) {
            while (failTimes.isNotEmpty() && now - failTimes.first() > 20_000) failTimes.removeFirst()
            failTimes.addLast(now)
            if (failTimes.size >= 3) { failTimes.clear(); true } else false
        }
        if (trigger) bg { healthCheck() }
    }

    // =====================================================================
    // Settings from the UI
    // =====================================================================

    fun setMode(mode: String) {
        val before = _state.value.prefs
        if (before.mode == mode) return
        val p = savePrefs { it.copy(mode = mode) }
        // The VPN routes differ between the modes, so rebuild the tunnel.
        if (_state.value.on) service?.let { svc ->
            if (p.targeted && p.sites.isEmpty()) disconnect() else bg { svc.startTunnel(!p.targeted, router) }
        }
    }

    fun select(id: String) {
        savePrefs { it.copy(selected = id) }
        setStatus { it.copy(warning = "", error = "") }
        if (_state.value.on) bg { repick() }
    }

    fun setCountry(code: String) {
        savePrefs { it.copy(country = code) }
        if (_state.value.on) bg { repick() }
    }

    fun setAutoPool(n: Int) {
        savePrefs { it.copy(autoPool = n) }
        if (_state.value.on) bg { repick() }
    }

    /** Returns how many new sites were added. */
    fun addSites(list: List<String>): Int {
        var added = 0
        savePrefs { p ->
            val set = p.sites.toMutableSet()
            for (raw in list) {
                val d = normalizeSite(raw)
                if (d.isNotEmpty() && set.add(d)) added++
            }
            p.copy(sites = set.sorted())
        }
        return added
    }

    fun removeSites(list: List<String>) {
        val p = savePrefs { p -> p.copy(sites = p.sites.filter { it !in list }) }
        if (_state.value.on && p.targeted && p.sites.isEmpty()) disconnect()
    }

    fun clearError() = setStatus { it.copy(error = "", warning = "") }

    // =====================================================================
    // Your own servers (optional)
    // =====================================================================

    class ImportResult(val added: Int, val skipped: Int)

    private fun importText(text: String, source: Source): ImportResult {
        val parsed = Links.parse(text, source.id)
        if (parsed.servers.isEmpty()) {
            throw IOException(
                if (parsed.skipped > 0) "These servers use VLESS / VMess / Trojan / Shadowsocks, which the Android app doesn't support yet. SOCKS5 and HTTP servers work."
                else "No usable servers found in this text."
            )
        }
        val own = _state.value.own.filter { it.sourceId != source.id } + parsed.servers
        val sources = _state.value.sources.filter { it.id != source.id } + source.copy(
            name = source.name.ifBlank { parsed.title ?: runCatching { URL(source.url).host }.getOrDefault("Imported list") },
            count = parsed.servers.size, skipped = parsed.skipped, updatedAt = System.currentTimeMillis(),
        )
        update { it.copy(own = own, sources = sources) }
        store.saveOwnServers(own)
        store.saveSources(sources)
        // Test them right away so they can be used.
        bg { testOwn(parsed.servers) }
        return ImportResult(parsed.servers.size, parsed.skipped)
    }

    private fun testOwn(list: List<Server>) {
        val res = HashMap<String, ProxyTester.Result?>()
        ProxyTester.testMany(list, threads = 32) { s, r -> res[s.id] = r }
        val now = System.currentTimeMillis()
        update { st ->
            st.copy(own = st.own.map { s ->
                if (!res.containsKey(s.id)) s else {
                    val r = res[s.id]
                    s.copy(ms = r?.ms, code = r?.code?.ifEmpty { s.code } ?: s.code, testedAt = now)
                }
            })
        }
        store.saveOwnServers(_state.value.own)
    }

    fun importPasted(text: String): ImportResult =
        importText(text, Source(hashId("paste:" + System.currentTimeMillis()), "paste", "", "Pasted servers", 0, 0, 0))

    fun addListUrl(url: String): ImportResult {
        val text = FreeProxies.download(url, 20_000)
        return importText(text, Source(hashId(url), "url", url, "", 0, 0, 0))
    }

    private fun refreshListUrls() {
        for (src in _state.value.sources.filter { it.type == "url" }) {
            runCatching { importText(FreeProxies.download(src.url, 20_000), src) }
        }
    }

    fun removeSource(id: String) {
        val own = _state.value.own.filter { it.sourceId != id }
        val sources = _state.value.sources.filter { it.id != id }
        update { it.copy(own = own, sources = sources) }
        store.saveOwnServers(own)
        store.saveSources(sources)
        if (_state.value.prefs.selected != "auto" && _state.value.servers.none { it.id == _state.value.prefs.selected }) select("auto")
    }

    fun testAll() = bg {
        if (_state.value.own.isNotEmpty()) testOwn(_state.value.own)
        refreshFree(force = true)
    }

    // =====================================================================
    // Check my IP
    // =====================================================================

    fun checkIp() = bg {
        update { it.copy(ipText = "Checking your IP…") }
        val text = try {
            val servers = chain
            val body = if (_state.value.on && servers.isNotEmpty()) {
                // Our own app is outside the VPN, so ask through the VPN server explicitly.
                var result: String? = null
                var err: Exception? = null
                for (s in servers.take(3)) {
                    try {
                        result = ProxyTester.httpsGet(ProxyDialer.open(s, IP_CHECK_HOST, 443), IP_CHECK_HOST, "/")
                        break
                    } catch (e: Exception) { err = e }
                }
                result ?: throw err ?: IOException("No server")
            } else {
                val c = URL("https://$IP_CHECK_HOST/").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
            }
            val j = JSONObject(body)
            if (!j.optBoolean("success", true)) "Could not check your IP"
            else {
                val where = listOf(j.optString("city"), j.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
                val flag = j.optJSONObject("flag")?.optString("emoji").orEmpty()
                "Your IP: ${j.optString("ip")} · $where $flag".trim()
            }
        } catch (e: Exception) {
            "Could not check your IP: ${e.message}"
        }
        update { it.copy(ipText = text) }
    }
}
