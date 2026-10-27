package com.tervyn.vpn.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Downloads free public proxy lists (the same ones the browser extension uses),
 * merges them and puts the most promising first: proxies that several trusted
 * lists agree on are usually alive.
 */
object FreeProxies {
    private data class Src(val name: String, val type: String, val proto: String, val weight: Int, val url: String)

    private const val GH = "https://raw.githubusercontent.com/"
    private val SOURCES = listOf(
        Src("jetkai (online)", "text", "socks5", 3, GH + "jetkai/proxy-list/main/online-proxies/txt/proxies-socks5.txt"),
        Src("jetkai HTTP (online)", "text", "http", 2, GH + "jetkai/proxy-list/main/online-proxies/txt/proxies-http.txt"),
        Src("monosans", "text", "socks5", 3, GH + "monosans/proxy-list/main/proxies/socks5.txt"),
        Src("monosans HTTP", "text", "http", 2, GH + "monosans/proxy-list/main/proxies/http.txt"),
        Src("elliottophellia (checked)", "text", "socks5", 3, GH + "elliottophellia/proxylist/master/results/socks5/global/socks5_checked.txt"),
        Src("roosterkid", "text", "socks5", 3, GH + "roosterkid/openproxylist/main/SOCKS5_RAW.txt"),
        Src("roosterkid HTTPS", "text", "http", 3, GH + "roosterkid/openproxylist/main/HTTPS_RAW.txt"),
        Src("vakhov", "text", "socks5", 3, GH + "vakhov/fresh-proxy-list/master/socks5.txt"),
        Src("vakhov HTTPS", "text", "http", 3, GH + "vakhov/fresh-proxy-list/master/https.txt"),
        Src("prxchk", "text", "socks5", 3, GH + "prxchk/proxy-list/main/socks5.txt"),
        Src("sunny9577", "text", "socks5", 2, GH + "sunny9577/proxy-scraper/master/generated/socks5_proxies.txt"),
        Src("hideip.me", "text", "socks5", 2, GH + "zloi-user/hideip.me/main/socks5.txt"),
        Src("Zaeem20", "text", "socks5", 2, GH + "Zaeem20/FREE_PROXIES_LIST/master/socks5.txt"),
        Src("TheSpeedX", "text", "socks5", 1, GH + "TheSpeedX/PROXY-List/master/socks5.txt"),
        Src("Proxifly HTTPS", "text", "http", 2, GH + "proxifly/free-proxy-list/main/proxies/protocols/https/data.txt"),
        Src("Proxifly SOCKS5", "text", "socks5", 1, GH + "proxifly/free-proxy-list/main/proxies/protocols/socks5/data.txt"),
        Src("ProxyScrape", "json", "http", 2, "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies&proxy_format=protocolipport&format=json"),
        Src("Geonode", "json", "http", 2, "https://proxylist.geonode.com/api/proxy-list?limit=500&page=1&sort_by=lastChecked&sort_type=desc"),
    )

    /** Lines from huge lists are mostly dead; only read this many per list. */
    private const val MAX_PER_SOURCE = 3000
    private val IP_RE = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val LINE_RE = Regex("(?:(https?|socks[45]h?)://)?(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{2,5})", RegexOption.IGNORE_CASE)

    class Result(val candidates: List<Server>, val sourcesOk: Int)

    fun fetch(max: Int = 1500): Result {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = SOURCES.map { src ->
                pool.submit(Callable {
                    runCatching {
                        val text = download(src.url, 25_000)
                        if (src.type == "json") {
                            runCatching { fromJson(JSONTokener(text).nextValue(), src.name) }.getOrElse { fromText(text, src.name, "http") }
                        } else fromText(text, src.name, src.proto)
                    }.getOrDefault(emptyList())
                })
            }
            val byId = LinkedHashMap<String, Server>()
            var ok = 0
            futures.forEachIndexed { i, f ->
                val list = runCatching { f.get(40, TimeUnit.SECONDS) }.getOrDefault(emptyList())
                if (list.isEmpty()) return@forEachIndexed
                ok++
                val w = SOURCES[i].weight
                for (s in list) {
                    val prev = byId[s.id]
                    byId[s.id] = when {
                        prev == null -> s.copy(score = w)
                        prev.code.isEmpty() && s.code.isNotEmpty() -> prev.copy(score = prev.score + w, code = s.code)
                        else -> prev.copy(score = prev.score + w)
                    }
                }
            }
            val sorted = byId.values.sortedWith(
                compareByDescending<Server> { it.score }.thenByDescending { it.proto == "socks5" }
            )
            return Result(sorted.take(max), ok)
        } finally {
            pool.shutdownNow()
        }
    }

    fun download(url: String, timeoutMs: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.useCaches = false
        c.setRequestProperty("User-Agent", "TervynVPN/1.0 (Android)")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun normProto(p: String?): String? = when (p?.lowercase()) {
        "socks5", "socks5h", "socks" -> "socks5"
        "http", "https" -> "http" // "https" in these lists = HTTP proxy that supports HTTPS sites
        else -> null              // socks4 etc. are skipped
    }

    private fun make(proto: String?, ip: String, port: Int, code: String, source: String): Server? {
        val p = normProto(proto) ?: return null
        if (!IP_RE.matches(ip) || port !in 1..65535) return null
        return Server.make(p, ip, port, code, source = "free", name = "")
    }

    fun fromText(text: String, source: String, defaultProto: String): List<Server> {
        val out = ArrayList<Server>()
        for (line in text.lineSequence().take(MAX_PER_SOURCE)) {
            val m = LINE_RE.find(line) ?: continue
            val proto = m.groupValues[1].ifEmpty { defaultProto }
            make(proto, m.groupValues[2], m.groupValues[3].toInt(), "", source)?.let(out::add)
        }
        return out
    }

    /** Walks any JSON shape and turns objects that look like proxies into servers. */
    fun fromJson(data: Any?, source: String): List<Server> {
        val out = ArrayList<Server>()
        fun pick(o: JSONObject, vararg keys: String): Any? {
            for (k in keys) if (o.has(k) && !o.isNull(k) && o.opt(k)?.toString().orEmpty().isNotEmpty()) return o.opt(k)
            return null
        }
        fun walk(v: Any?, depth: Int) {
            if (depth > 4 || v == null) return
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), depth + 1)
                is JSONObject -> {
                    val ip = pick(v, "ip", "host", "address")
                    val port = pick(v, "port")
                    if (ip != null && port != null) {
                        var protos: Any? = pick(v, "protocols", "protocol", "type")
                        if (protos == null && v.opt("proxy") is String) protos = v.getString("proxy").substringBefore("://")
                        val list = if (protos is JSONArray) List(protos.length()) { protos.optString(it) } else listOf(protos?.toString())
                        val geo = (v.opt("geolocation") ?: v.opt("ip_data") ?: v.opt("location")) as? JSONObject
                        val code = (pick(v, "countryCode", "country_code")
                            ?: geo?.let { pick(it, "countryCode", "country_code", "country") }
                            ?: v.opt("country"))?.toString() ?: ""
                        val portNum = port.toString().toIntOrNull() ?: return
                        for (p in list) make(p, ip.toString(), portNum, code, source)?.let(out::add)
                        return
                    }
                    for (k in v.keys()) walk(v.opt(k), depth + 1)
                }
            }
        }
        walk(data, 0)
        return out
    }
}
