package com.tervyn.vpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.tervyn.vpn.net.ProxyDeadException
import com.tervyn.vpn.net.ProxyDialer
import com.tervyn.vpn.net.ProxyEndpoint
import com.tervyn.vpn.net.ProxyTester
import com.tervyn.vpn.vpn.FakeDns
import com.tervyn.vpn.vpn.Router
import com.tervyn.vpn.vpn.TervynVpnService
import com.tervyn.vpn.xray.XrayProcess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app's brain (same job as background.js in the extension): gets servers
 * from the chosen source (free lists, Proxio.io, or your own), tests them,
 * picks the fastest, keeps backups ready, swaps out dead ones, runs Xray for
 * VLESS / VMess / Trojan / Shadowsocks servers and tells the VPN where to send traffic.
 */
object Core {
    private const val TAG = "Tervyn"
    private const val KEEP_FREE = 40
    private const val REFRESH_MIN = 30L
    private const val HEALTH_MIN = 2L
    private const val IP_CHECK_HOST = "ipwho.is"
    private const val XRAY_MISSING = "This copy of the app was built without Xray, so VLESS / VMess / Trojan / Shadowsocks servers can't be used. SOCKS5 and HTTP servers work."

    @android.annotation.SuppressLint("StaticFieldLeak") // application context, lives as long as the app
    private lateinit var app: Context
    private lateinit var store: Store
    private val lock = Any()

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val exec = Executors.newCachedThreadPool { r -> Thread(r, "tervyn-core").apply { isDaemon = true } }
    private val sched = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tervyn-sched").apply { isDaemon = true } }

    /** Stand-in addresses survive reconnects, so apps' cached DNS answers keep working. */
    val fakeDns = FakeDns()

    // Servers in use (first = main, rest = backups) and how to reach each one.
    @Volatile private var chain: List<Server> = emptyList()
    @Volatile private var chainEndpoints: List<ProxyEndpoint> = emptyList()

    @Volatile private var refreshing: Thread? = null
    @Volatile private var proxioRefreshing: Thread? = null
    private val proxioLock = Object()
    @Volatile private var connecting = false
    @Volatile private var checkingHealth = false
    @Volatile private var service: TervynVpnService? = null
    private val failTimes = ArrayDeque<Long>()
    private val listeners = mutableListOf<() -> Unit>()

    // Xray-core (bundled as libxray.so so Android lets us run it).
    private var xrayBin: File? = null
    private val xrayDir: File get() = File(app.filesDir, "xray")
    private val xrayLock = Any()
    @Volatile private var mainXray: XrayProcess? = null
    private val testCounter = AtomicInteger()

    fun init(context: Context) {
        app = context.applicationContext
        store = Store(app)
        _state.value = AppState(
            prefs = store.prefs(), free = store.freeServers(), own = store.ownServers(),
            proxio = store.proxioServers(), proxioConfig = store.proxioConfig(), proxioStatus = store.proxioStatus(),
            custom = store.customServers(), customCode = store.customCode(), customStatus = store.customStatus(),
            list = store.listServers(), listName = store.listName(), listStatus = store.listStatus(),
            sources = store.sources(), freeStatus = store.freeStatus(),
        )
        xrayBin = File(app.applicationInfo.nativeLibraryDir, "libxray.so").takeIf { it.exists() }
        bg {
            val bin = xrayBin ?: return@bg
            XrayProcess.killLeftovers(bin)
            val v = runCatching {
                val p = ProcessBuilder(bin.absolutePath, "version").redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readLine().orEmpty().also { p.waitFor(5, TimeUnit.SECONDS) }
            }.getOrDefault("")
            val ver = Regex("Xray ([\\d.]+)").find(v)?.groupValues?.get(1) ?: if (v.isNotBlank()) "?" else ""
            update { it.copy(xrayVersion = ver) }
        }
        sched.scheduleWithFixedDelay({ safe { healthCheck() } }, HEALTH_MIN, HEALTH_MIN, TimeUnit.MINUTES)
        sched.scheduleWithFixedDelay({ safe { periodicRefresh() } }, REFRESH_MIN, REFRESH_MIN, TimeUnit.MINUTES)
    }

    val xrayIncluded: Boolean get() = xrayBin != null

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

    private fun setProxioStatus(st: ProxioStatus) {
        update { it.copy(proxioStatus = st) }
        store.saveProxioStatus(st)
    }

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

    private fun saveProxio(list: List<Server>) {
        update { it.copy(proxio = list) }
        store.saveProxioServers(list)
    }

    private fun saveOwn(list: List<Server>) {
        update { it.copy(own = list) }
        store.saveOwnServers(list)
    }

    private fun safe(block: () -> Unit) = try { block() } catch (e: Exception) { Log.w(TAG, "task failed", e) }
    private fun bg(block: () -> Unit) = exec.execute { safe(block) }

    private fun periodicRefresh() {
        val p = _state.value.prefs
        refreshListUrls()
        if (p.usesProxio) runCatching { refreshProxio() }
        if (p.usesFree) refreshFree(force = true)
        if (p.source == Prefs.SOURCE_OWN) testOwn(_state.value.own)
        if (p.source == Prefs.SOURCE_CUSTOM) runCatching { testCustom() }
        if (p.source == Prefs.SOURCE_LIST) runCatching { testList() }
    }

    // =====================================================================
    // Testing (plain proxies directly, VLESS & co. through Xray)
    // =====================================================================

    private class Tagged(val server: Server, ep: ProxyEndpoint) : ProxyEndpoint by ep

    /**
     * Tests servers with the HTTPS check. VLESS / VMess / Trojan / Shadowsocks
     * servers are tested through Xray: the running one if it already serves
     * them, otherwise a temporary one.
     */
    private fun testServers(
        list: List<Server>,
        threads: Int = 64,
        stopAfter: Int = Int.MAX_VALUE,
        maxTimeMs: Long = Long.MAX_VALUE,
        onResult: (Server, ProxyTester.Result?) -> Unit,
    ) {
        val direct = list.filter { !it.isCore }
        val core = list.filter { it.isCore }
        if (direct.isNotEmpty()) ProxyTester.testMany(direct, threads, stopAfter, maxTimeMs, onResult)
        if (core.isEmpty()) return
        val bin = xrayBin
        if (bin == null) {
            synchronized(ProxyTester) { core.forEach { onResult(it, null) } }
            return
        }
        val main = mainXray
        val inMain = if (main != null && main.alive) core.filter { main.endpoints.containsKey(it.id) } else emptyList()
        if (inMain.isNotEmpty()) testVia(inMain, main!!.endpoints, threads, onResult)
        for (chunk in (core - inMain.toSet()).chunked(48)) {
            val x = XrayProcess(bin, xrayDir, "test${testCounter.incrementAndGet()}", chunk)
            try {
                x.start()
            } catch (e: Exception) {
                Log.w(TAG, "xray test start failed", e)
                synchronized(ProxyTester) { chunk.forEach { onResult(it, null) } }
                continue
            }
            try {
                testVia(chunk, x.endpoints, threads, onResult)
            } finally {
                x.stop()
            }
        }
    }

    private fun testVia(servers: List<Server>, eps: Map<String, ProxyEndpoint>, threads: Int, onResult: (Server, ProxyTester.Result?) -> Unit) {
        val tagged = servers.mapNotNull { s -> eps[s.id]?.let { Tagged(s, it) } }
        synchronized(ProxyTester) { servers.filter { it.id !in eps }.forEach { onResult(it, null) } } // Xray refused these
        ProxyTester.testMany(tagged, threads) { t, r -> onResult(t.server, r) }
    }

    // =====================================================================
    // Free servers: find, test, keep the fastest
    // =====================================================================

    /** Called when the app opens: refresh lists that are old or short. */
    fun ensureFree() = bg {
        val p = _state.value.prefs
        if (p.usesProxio && System.currentTimeMillis() - _state.value.proxioStatus.at > REFRESH_MIN * 60_000) {
            runCatching { refreshProxio() }
        }
        if (p.usesFree) refreshFree(force = false)
    }

    fun refreshFreeAsync() = bg { refreshFree(force = true) }

    val isRefreshing get() = refreshing != null || proxioRefreshing != null

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
            if (final.isNotEmpty() && s.on && s.prefs.selected == "auto" && s.prefs.usesFree && !connecting) repick()
        } catch (e: Exception) {
            val fs = _state.value.freeStatus.copy(state = "idle", at = System.currentTimeMillis(), error = e.message ?: e.toString())
            update { it.copy(freeStatus = fs) }
            store.saveFreeStatus(fs)
        } finally {
            refreshing = null
        }
    }

    // =====================================================================
    // Proxio.io servers (your API key)
    // =====================================================================

    /** Downloads and tests your Proxio.io list. Throws with a readable message on failure. */
    fun refreshProxio(): ProxioStatus {
        val cfg = _state.value.proxioConfig
        if (cfg.key.isBlank()) return _state.value.proxioStatus
        synchronized(proxioLock) {
            if (proxioRefreshing != null) {
                // Someone else is loading it: wait for that result.
                while (proxioRefreshing != null) proxioLock.wait(500)
                return _state.value.proxioStatus
            }
            proxioRefreshing = Thread.currentThread()
        }
        try {
            setProxioStatus(_state.value.proxioStatus.copy(state = "loading", error = ""))
            val list = Proxio.fetch(cfg)
            if (list.isEmpty()) throw IOException("Proxio.io returned no usable servers. Check your plan and the server type.")
            setProxioStatus(_state.value.proxioStatus.copy(state = "testing", fetched = list.size))
            val working = ArrayList<Server>()
            testServers(list) { s, r ->
                if (r != null) working += s.copy(ms = r.ms, code = r.code.ifEmpty { s.code }, testedAt = System.currentTimeMillis())
            }
            working.sortBy { it.ms }
            saveProxio(working)
            val st = ProxioStatus(
                at = System.currentTimeMillis(), fetched = list.size, working = working.size,
                error = if (working.isEmpty()) "Proxio.io sent ${list.size} servers, but none answered the speed test. Try again in a few minutes." else "",
            )
            setProxioStatus(st)
            val s = _state.value
            if (working.isNotEmpty() && s.on && s.prefs.usesProxio && s.prefs.selected == "auto" && !connecting) repick()
            return st
        } catch (e: Exception) {
            setProxioStatus(_state.value.proxioStatus.copy(state = "idle", at = System.currentTimeMillis(), error = e.message ?: e.toString()))
            throw e
        } finally {
            synchronized(proxioLock) {
                proxioRefreshing = null
                proxioLock.notifyAll()
            }
        }
    }

    /** Save the key (blank = keep the saved one), load the list, and switch to Proxio.io if it works. */
    fun proxioSave(key: String, type: String, limit: Int): ProxioStatus {
        val old = _state.value.proxioConfig
        val cfg = Proxio.Config(key.trim().ifEmpty { old.key }, type, limit)
        if (cfg.key.isBlank()) throw IOException("Enter your Proxio.io API key first.")
        update { it.copy(proxioConfig = cfg) }
        store.saveProxioConfig(cfg)
        val st = refreshProxio()
        if (st.working > 0 && !_state.value.prefs.usesProxio) setSource(Prefs.SOURCE_PROXIO)
        return st
    }

    fun proxioRemove() {
        store.removeProxio()
        update { it.copy(proxio = emptyList(), proxioConfig = Proxio.Config(), proxioStatus = ProxioStatus()) }
        if (_state.value.prefs.usesProxio) setSource(Prefs.SOURCE_FREE)
    }

    // =====================================================================
    // Proxy integration (pasted curl / Python / Node.js code)
    // =====================================================================

    private fun saveCustom(list: List<Server>) {
        update { it.copy(custom = list) }
        store.saveCustomServers(list)
    }

    private fun setCustomStatus(st: ProxioStatus) {
        update { it.copy(customStatus = st) }
        store.saveCustomStatus(st)
    }

    /** Reads the proxies from [code], tests them and switches to them if one works. */
    fun customSave(code: String): ProxioStatus {
        val parsed = ProxySnippet.parse(code)
        if (parsed.servers.isEmpty()) {
            throw IOException(
                if (parsed.unsupported > 0) "This code uses a SOCKS4 proxy, which isn't supported. Use the HTTP or SOCKS5 example from your provider."
                else "Couldn't find a proxy in this code. Paste the curl, Python or Node.js example from your proxy provider: it should include the proxy address, like http://user:pass@host:port."
            )
        }
        update { it.copy(customCode = code) }
        store.saveCustomCode(code)
        saveCustom(parsed.servers)
        val st = testCustom()
        if (st.working > 0 && _state.value.prefs.source != Prefs.SOURCE_CUSTOM) setSource(Prefs.SOURCE_CUSTOM)
        else if (st.working > 0 && _state.value.on) repick()
        return st
    }

    private val customLock = Any()

    /** Tests the integration proxies (keeps them all, with their result). */
    fun testCustom(): ProxioStatus = testKept({ it.custom }, ::saveCustom, { it.customStatus }, ::setCustomStatus)

    /**
     * Tests every proxy in a list you gave us and keeps them all with their
     * result (dead ones stay, so they can come back later).
     */
    private fun testKept(
        get: (AppState) -> List<Server>,
        save: (List<Server>) -> Unit,
        status: (AppState) -> ProxioStatus,
        setStatus: (ProxioStatus) -> Unit,
    ): ProxioStatus {
        synchronized(customLock) {
            val list = get(_state.value)
            if (list.isEmpty()) return status(_state.value)
            setStatus(status(_state.value).copy(state = "testing", fetched = list.size, error = ""))
            val res = HashMap<String, ProxyTester.Result?>()
            testServers(list, threads = 32) { s, r -> res[s.id] = r }
            val now = System.currentTimeMillis()
            val tested = get(_state.value).map { s ->
                if (!res.containsKey(s.id)) s else {
                    val r = res[s.id]
                    s.copy(ms = r?.ms, code = r?.code?.ifEmpty { s.code } ?: s.code, testedAt = now)
                }
            }
            save(tested)
            val working = tested.count { it.ms != null }
            val st = ProxioStatus(
                at = now, fetched = tested.size, working = working,
                error = if (working == 0) {
                    if (tested.size == 1) "The proxy didn't answer the speed test. Check the address, the login and that your plan is active."
                    else "None of the ${tested.size} proxies answered the speed test. Check the login, the type (HTTP / SOCKS5) and that your plan is active."
                } else "",
            )
            setStatus(st)
            return st
        }
    }

    fun customRemove() {
        store.removeCustom()
        update { it.copy(custom = emptyList(), customCode = "", customStatus = ProxioStatus()) }
        if (_state.value.prefs.source == Prefs.SOURCE_CUSTOM) setSource(Prefs.SOURCE_FREE)
    }

    // =====================================================================
    // Proxy list file (host:port:user:pass lines)
    // =====================================================================

    private fun saveList(list: List<Server>) {
        update { it.copy(list = list) }
        store.saveListServers(list)
    }

    private fun setListStatus(st: ProxioStatus) {
        update { it.copy(listStatus = st) }
        store.saveListStatus(st)
    }

    class ListImport(val status: ProxioStatus, val added: Int, val skipped: Int)

    /** Replaces your proxy list with [text] (one proxy per line), tests it and switches to it if any work. */
    fun listImport(text: String, name: String, proto: String): ListImport {
        val parsed = ProxyList.parse(text, proto)
        if (parsed.servers.isEmpty()) {
            throw IOException("No proxies found. Each line should look like host:port:user:pass (or user:pass@host:port, or host:port).")
        }
        update { it.copy(listName = name) }
        store.saveListName(name)
        saveList(parsed.servers)
        val st = testList()
        if (st.working > 0 && _state.value.prefs.source != Prefs.SOURCE_LIST) setSource(Prefs.SOURCE_LIST)
        else if (st.working > 0 && _state.value.on) repick()
        return ListImport(st, parsed.servers.size, parsed.skipped)
    }

    /** Switches every proxy in the list between HTTP and SOCKS5 (the file doesn't say which) and re-tests. */
    fun listSetProto(proto: String): ProxioStatus {
        val list = _state.value.list
        if (list.isEmpty() || list.all { it.proto == proto }) return _state.value.listStatus
        saveList(list.map { s ->
            s.copy(proto = proto, id = hashId("${ProxyList.SOURCE}:$proto://${s.username}@${s.address}:${s.port}"),
                name = "${proto.uppercase()} ${s.hostPort}", ms = null, testedAt = 0)
        })
        if (_state.value.prefs.selected != "auto" && _state.value.servers.none { it.id == _state.value.prefs.selected }) {
            savePrefs { it.copy(selected = "auto") }
        }
        val st = testList()
        if (_state.value.on && _state.value.prefs.source == Prefs.SOURCE_LIST) repick()
        return st
    }

    fun testList(): ProxioStatus = testKept({ it.list }, ::saveList, { it.listStatus }, ::setListStatus)

    fun listRemove() {
        store.removeList()
        update { it.copy(list = emptyList(), listName = "", listStatus = ProxioStatus()) }
        if (_state.value.prefs.source == Prefs.SOURCE_LIST) setSource(Prefs.SOURCE_FREE)
    }

    // =====================================================================
    // Health checks
    // =====================================================================

    /** Re-checks the servers in use; swaps out dead ones and tops up the list. */
    private fun healthCheck() {
        val s = _state.value
        if (!s.on || refreshing != null || connecting || checkingHealth) return
        checkingHealth = true
        try {
            val inUse = chain
            if (inUse.isEmpty()) return
            // Xray stopped (e.g. Android killed it): start it again.
            if (inUse.any { it.isCore } && mainXray?.alive != true) {
                runCatching { activate(inUse) }
            }
            val results = HashMap<String, ProxyTester.Result?>()
            testServers(inUse, threads = inUse.size) { srv, r -> results[srv.id] = r }
            val now = System.currentTimeMillis()
            val dead = inUse.filter { results[it.id] == null }.map { it.id }.toSet()
            fun upd(l: List<Server>, dropDead: Boolean) = l.mapNotNull { srv ->
                when {
                    srv.id in dead && dropDead -> null
                    srv.id in dead -> srv.copy(ms = null, testedAt = now)
                    results.containsKey(srv.id) -> srv.copy(ms = results[srv.id]!!.ms, testedAt = now)
                    else -> srv
                }
            }
            saveFree(upd(_state.value.free, dropDead = true))
            saveProxio(upd(_state.value.proxio, dropDead = true))
            saveOwn(upd(_state.value.own, dropDead = false))
            saveCustom(upd(_state.value.custom, dropDead = false))
            saveList(upd(_state.value.list, dropDead = false))
            if (dead.isNotEmpty() && !connecting && refreshing == null) {
                if (inUse.first().id in dead) {
                    setStatus {
                        it.copy(warning = if (_state.value.prefs.selected == "auto")
                            "A server stopped answering — switched to the next fastest one."
                        else "The server you picked isn't answering. Pick another one or use Fastest.")
                    }
                }
                repick()
            }
            val p = _state.value.prefs
            val since = { at: Long -> System.currentTimeMillis() - at > 5 * 60_000 }
            if (p.usesProxio && _state.value.proxio.count { it.ms != null } < 3 && since(_state.value.proxioStatus.at)) {
                runCatching { refreshProxio() }
            }
            if (p.usesFree && _state.value.free.count { it.ms != null } < 10 && since(_state.value.freeStatus.at)) {
                refreshFree(force = true)
            }
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
        fun inCountry(l: List<Server>) = if (prefs.country.isEmpty()) l
            else l.filter { it.code == prefs.country }.ifEmpty { l }
        fun best(l: List<Server>) = inCountry(l.filter { it.ms != null }).sortedBy { it.ms }.take(maxOf(2, prefs.autoPool))

        when (prefs.source) {
            Prefs.SOURCE_LIST -> {
                var list = best(_state.value.list)
                if (list.isEmpty()) {
                    if (_state.value.list.isEmpty()) {
                        throw IOException("Import your proxy list (.txt) under Settings → Proxy list, or choose another server source.")
                    }
                    runCatching { testList() }
                    list = best(_state.value.list)
                }
                if (list.isEmpty()) throw IOException(_state.value.listStatus.error.ifEmpty { "None of the proxies in your list are answering. Check the login, the type (HTTP / SOCKS5) and that your plan is active." })
                return list
            }
            Prefs.SOURCE_CUSTOM -> {
                var list = best(_state.value.custom)
                if (list.isEmpty()) {
                    if (_state.value.custom.isEmpty()) {
                        throw IOException("Paste your proxy's curl, Python or Node.js example under Settings → Proxy integration, or choose another server source.")
                    }
                    runCatching { testCustom() }
                    list = best(_state.value.custom)
                }
                if (list.isEmpty()) throw IOException(_state.value.customStatus.error.ifEmpty { "Your integration proxy isn't answering. Check the login and that your plan is active." })
                return list
            }
            Prefs.SOURCE_OWN -> {
                var list = best(_state.value.own)
                if (list.isEmpty()) {
                    if (_state.value.own.isEmpty()) {
                        throw IOException("You haven't added any servers yet. Add them under Servers → Your own servers, or choose another server source in Settings.")
                    }
                    testOwn(_state.value.own)
                    list = best(_state.value.own)
                }
                if (list.isEmpty()) throw IOException("None of your servers answered the speed test. Check them, or choose another server source in Settings.")
                return list
            }
            Prefs.SOURCE_PROXIO -> {
                var list = best(_state.value.proxio)
                if (list.isEmpty()) {
                    if (_state.value.proxioConfig.key.isBlank()) throw IOException("Add your Proxio.io API key in Settings, or choose another server source.")
                    runCatching { refreshProxio() }
                    list = best(_state.value.proxio)
                }
                if (list.isEmpty()) {
                    throw IOException(_state.value.proxioStatus.error.ifEmpty {
                        "No working Proxio.io server right now. Check your API key in Settings, or switch the server source to free servers."
                    })
                }
                return list
            }
        }

        // Free servers (and Proxio.io servers when the source is "both").
        fun working() = (if (prefs.usesProxio) _state.value.proxio else emptyList()) + _state.value.free
        var list = working().filter { it.ms != null }
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
                list = working().filter { it.ms != null }
                if (list.isNotEmpty() && firstFoundAt == 0L) firstFoundAt = System.currentTimeMillis()
                // Search finished (give it a moment to start first).
                val searchDone = refreshing == null && System.currentTimeMillis() - start > 3000
                // Connect with 2+ servers (one is a backup), or with 1 after a short wait.
                if (list.size >= 2 || searchDone || (firstFoundAt > 0 && System.currentTimeMillis() - firstFoundAt > 6000)) break
                if (!_state.value.prefs.enabled) return emptyList()
                Thread.sleep(1000)
            }
        }
        return best(list)
    }

    private fun label(prefs: Prefs, servers: List<Server>): String {
        val first = servers.first()
        val place = if (first.country != "Unknown") first.country else first.name
        return if (prefs.selected == "auto") {
            "${first.flag} $place" + if (servers.size > 1) " + ${servers.size - 1} backup" else ""
        } else "${first.flag} $place"
    }

    /**
     * Makes [servers] the ones in use. Starts (or restarts) Xray when some of
     * them are VLESS / VMess / Trojan / Shadowsocks servers.
     */
    private fun activate(servers: List<Server>) {
        synchronized(xrayLock) {
            val core = servers.filter { it.isCore }
            if (core.isEmpty()) {
                mainXray?.stop()
                mainXray = null
            } else {
                val bin = xrayBin ?: throw IOException(XRAY_MISSING)
                val cur = mainXray
                if (cur == null || !cur.alive || cur.servers.map { it.id }.toSet() != core.map { it.id }.toSet()) {
                    // Start the new one before stopping the old one, so traffic keeps flowing.
                    val x = XrayProcess(bin, xrayDir, "main", core)
                    x.start()
                    cur?.stop()
                    mainXray = x
                }
            }
            val x = mainXray
            val usable = servers.filter { !it.isCore || x?.endpoints?.containsKey(it.id) == true }
            if (usable.isEmpty()) throw IOException("Xray could not use these servers. ${x?.lastError().orEmpty()}".trim())
            chainEndpoints = usable.map { if (it.isCore) x!!.endpoints.getValue(it.id) else it }
            chain = usable
        }
    }

    /** Switch servers while connected, without dropping the VPN. */
    private fun repick() {
        val prefs = _state.value.prefs
        if (!_state.value.on) return
        val servers = runCatching { pickServers(prefs) }.getOrElse { e ->
            setStatus { it.copy(warning = e.message ?: e.toString()) }
            return
        }
        if (servers.isEmpty()) return
        try {
            activate(servers)
        } catch (e: Exception) {
            setStatus { it.copy(warning = e.message ?: e.toString()) }
            return
        }
        setStatus { it.copy(label = label(prefs, chain), serverIds = chain.map { s -> s.id }) }
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
                throw IOException("No free server is answering right now. Tap \"Find fastest servers\" in the Servers tab, or try again in a minute.")
            }
            activate(servers)
            svc.startTunnel(fullMode = !prefs.targeted, router = router)
            setStatus {
                Status(state = "on", label = label(prefs, chain), serverIds = chain.map { it.id },
                    since = if (wasOn) it.since else System.currentTimeMillis())
            }
            update { it.copy(ipText = "") }
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            savePrefs { it.copy(enabled = false) }
            clearChain()
            svc.stopTunnel()
            setStatus { Status(state = "off", error = e.message ?: e.toString()) }
        }
    }

    private fun clearChain() {
        chain = emptyList()
        chainEndpoints = emptyList()
        synchronized(xrayLock) {
            mainXray?.stop()
            mainXray = null
        }
    }

    /** Stop button, tile, notification, or Android revoked the VPN. */
    fun disconnect() {
        savePrefs { it.copy(enabled = false) }
        service?.stopTunnel()
        service = null
        bg { clearChain() }
        chain = emptyList()
        chainEndpoints = emptyList()
        setStatus { Status(state = "off") }
        update { it.copy(ipText = "") }
    }

    fun serviceDestroyed(svc: TervynVpnService) {
        if (service !== svc) return
        service = null
        bg { clearChain() }
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
            val eps = chainEndpoints
            if (eps.isEmpty()) throw IOException("VPN is not connected")
            var last: IOException? = null
            // Like the browser: if a server fails, try the next one in the chain.
            for (ep in eps.take(3)) {
                try {
                    return ProxyDialer.open(ep, host, port, 8000)
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

    /** Where auto mode gets servers from. */
    fun setSource(source: String) {
        if (_state.value.prefs.source == source) return
        val p = savePrefs { it.copy(source = source, selected = "auto") }
        setStatus { it.copy(warning = "", error = "") }
        bg {
            // Make sure the new source has servers, then move the connection over.
            if (p.usesFree && _state.value.free.none { it.ms != null }) refreshFreeAsync()
            if (p.usesProxio && _state.value.proxio.none { it.ms != null }) runCatching { refreshProxio() }
            if (_state.value.on) repick()
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
    // Your own servers (VLESS, VMess, Trojan, Shadowsocks, SOCKS5, HTTP)
    // =====================================================================

    class ImportResult(val added: Int, val skipped: Int, val core: Int, val switched: Boolean)

    private fun importText(text: String, source: Source): ImportResult {
        val parsed = Links.parse(text, source.id)
        if (parsed.servers.isEmpty()) throw IOException("No usable servers found in this text.")
        val core = parsed.servers.count { it.isCore }
        if (core > 0 && !xrayIncluded && core == parsed.servers.size) throw IOException(XRAY_MISSING)
        val own = _state.value.own.filter { it.sourceId != source.id } + parsed.servers
        val sources = _state.value.sources.filter { it.id != source.id } + source.copy(
            name = source.name.ifBlank { parsed.title ?: runCatching { URL(source.url).host }.getOrDefault("Imported list") },
            count = parsed.servers.size, skipped = parsed.skipped, updatedAt = System.currentTimeMillis(),
        )
        saveOwn(own)
        update { it.copy(sources = sources) }
        store.saveSources(sources)
        // Use them: switch the server source, then test them so the fastest is picked.
        val switched = _state.value.prefs.source != Prefs.SOURCE_OWN
        if (switched) savePrefs { it.copy(source = Prefs.SOURCE_OWN, selected = "auto") }
        bg {
            testOwn(parsed.servers)
            if (_state.value.on && _state.value.prefs.source == Prefs.SOURCE_OWN) repick()
        }
        return ImportResult(parsed.servers.size, parsed.skipped, core, switched)
    }

    private val testingOwn = AtomicInteger()

    private fun testOwn(list: List<Server>) {
        if (list.isEmpty()) return
        testingOwn.incrementAndGet()
        update { it.copy(ownTesting = true) }
        try {
            val res = HashMap<String, ProxyTester.Result?>()
            testServers(list, threads = 32) { s, r -> res[s.id] = r }
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
        } finally {
            val left = testingOwn.decrementAndGet()
            update { it.copy(ownTesting = left > 0) }
        }
    }

    fun importPasted(text: String): ImportResult =
        importText(text, Source(hashId("paste:" + System.currentTimeMillis()), "paste", "", "Pasted servers", 0, 0, 0))

    fun addListUrl(url: String): ImportResult {
        val text = FreeProxies.download(url, 20_000)
        return importText(text, Source(hashId(url), "url", url, "", 0, 0, 0))
    }

    private fun refreshListUrls() {
        for (src in _state.value.sources.filter { it.type == "url" }) {
            runCatching {
                val parsed = Links.parse(FreeProxies.download(src.url, 20_000), src.id)
                if (parsed.servers.isNotEmpty()) {
                    // Keep test results for servers that are still in the list.
                    val old = _state.value.own.associateBy { it.id }
                    val fresh = parsed.servers.map { s -> old[s.id]?.let { o -> s.copy(ms = o.ms, testedAt = o.testedAt, code = o.code.ifEmpty { s.code }) } ?: s }
                    saveOwn(_state.value.own.filter { it.sourceId != src.id } + fresh)
                    val sources = _state.value.sources.map { if (it.id == src.id) it.copy(count = fresh.size, skipped = parsed.skipped, updatedAt = System.currentTimeMillis()) else it }
                    update { it.copy(sources = sources) }
                    store.saveSources(sources)
                }
            }
        }
    }

    fun removeSource(id: String) {
        val own = _state.value.own.filter { it.sourceId != id }
        val sources = _state.value.sources.filter { it.id != id }
        saveOwn(own)
        update { it.copy(sources = sources) }
        store.saveSources(sources)
        if (_state.value.prefs.selected != "auto" && _state.value.servers.none { it.id == _state.value.prefs.selected }) select("auto")
        if (own.isEmpty() && _state.value.prefs.source == Prefs.SOURCE_OWN) setSource(Prefs.SOURCE_FREE)
    }

    /** The "Find fastest servers" button: re-test the current source. */
    fun testAll() = bg {
        val p = _state.value.prefs
        when (p.source) {
            Prefs.SOURCE_CUSTOM -> runCatching { testCustom() }
            Prefs.SOURCE_LIST -> runCatching { testList() }
            Prefs.SOURCE_OWN -> testOwn(_state.value.own)
            Prefs.SOURCE_PROXIO -> runCatching { refreshProxio() }
            Prefs.SOURCE_BOTH -> { runCatching { refreshProxio() }; refreshFree(force = true) }
            else -> refreshFree(force = true)
        }
        if (_state.value.on && p.selected == "auto") repick()
    }

    // =====================================================================
    // Check my IP
    // =====================================================================

    fun checkIp() = bg {
        update { it.copy(ipText = "Checking your IP…") }
        val text = try {
            val eps = chainEndpoints
            val body = if (_state.value.on && eps.isNotEmpty()) {
                // Our own app is outside the VPN, so ask through the VPN server explicitly.
                var result: String? = null
                var err: Exception? = null
                for (ep in eps.take(3)) {
                    try {
                        result = ProxyTester.httpsGet(ProxyDialer.open(ep, IP_CHECK_HOST, 443), IP_CHECK_HOST, "/")
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
