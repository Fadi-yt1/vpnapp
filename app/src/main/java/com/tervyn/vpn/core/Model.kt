package com.tervyn.vpn.core

import com.tervyn.vpn.net.ProxyEndpoint
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** A plain SOCKS5 or HTTP proxy (free or your own). */
data class Server(
    val id: String,
    override val proto: String,          // "socks5" or "http"
    override val address: String,
    override val port: Int,
    override val username: String = "",
    override val password: String = "",
    val code: String = "",      // country code, e.g. "DE"
    val name: String = "",
    val free: Boolean = true,
    val sourceId: String = "free",
    val score: Int = 0,
    val ms: Int? = null,        // last measured delay, null = not working / untested
    val testedAt: Long = 0,
    val link: String = "",      // share link, for VLESS / VMess / Trojan / Shadowsocks servers
    val mbps: Double? = null,   // measured download speed, null = not measured
) : ProxyEndpoint {
    /**
     * Lower is better: delay plus a penalty that shrinks with download speed,
     * so a server with low ping AND high speed comes first.
     */
    val rank: Int get() = when {
        ms == null -> Int.MAX_VALUE
        mbps == null -> ms + 1500
        else -> ms + (3000 / (1.0 + mbps)).toInt()
    }

    /** Needs Xray (VLESS, VMess, Trojan, Shadowsocks). Plain SOCKS5 / HTTP servers don't. */
    val isCore: Boolean get() = proto in CORE_PROTOS
    val isProxio: Boolean get() = sourceId == PROXIO_SOURCE
    val flag: String get() = flagFromCode(code)
    val country: String get() = countryName(code)
    val hostPort: String get() = "$address:$port"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("proto", proto); put("address", address); put("port", port)
        put("username", username); put("password", password); put("code", code); put("name", name)
        put("free", free); put("sourceId", sourceId); put("score", score)
        if (ms != null) put("ms", ms)
        put("testedAt", testedAt)
        if (link.isNotEmpty()) put("link", link)
        if (mbps != null) put("mbps", mbps)
    }

    companion object {
        fun fromJson(o: JSONObject) = Server(
            id = o.getString("id"), proto = o.getString("proto"), address = o.getString("address"),
            port = o.getInt("port"), username = o.optString("username"), password = o.optString("password"),
            code = o.optString("code"), name = o.optString("name"), free = o.optBoolean("free", true),
            sourceId = o.optString("sourceId", "free"), score = o.optInt("score"),
            ms = if (o.has("ms")) o.getInt("ms") else null, testedAt = o.optLong("testedAt"),
            link = o.optString("link"),
            mbps = if (o.has("mbps")) o.getDouble("mbps") else null,
        )

        val CORE_PROTOS = setOf("vless", "vmess", "trojan", "shadowsocks")
        const val PROXIO_SOURCE = "proxio"

        fun make(
            proto: String, address: String, port: Int, code: String = "", source: String = "free",
            free: Boolean = true, username: String = "", password: String = "", name: String = "",
        ): Server {
            val key = "$proto://$address:$port"
            val c = if (Regex("^[A-Za-z]{2}$").matches(code)) code.uppercase() else ""
            return Server(
                id = hashId(key), proto = proto, address = address, port = port, code = c,
                name = name.ifBlank { "${proto.uppercase()} $address:$port" }, free = free,
                sourceId = source, username = username, password = password,
            )
        }
    }
}

data class Prefs(
    val enabled: Boolean = false,
    val mode: String = MODE_FULL,       // MODE_FULL = all traffic, MODE_TARGETED = only listed sites
    val selected: String = "auto",      // "auto" or a server id
    val sites: List<String> = emptyList(),
    val autoPool: Int = 5,              // servers kept ready (first one + backups)
    val country: String = "",           // limit auto mode to one country code ("" = any)
    val source: String = SOURCE_FREE,   // where auto mode gets servers from
    val autoSwitch: Boolean = true,     // true: always use the fastest server and switch automatically
    val showSpeed: Boolean = true,      // live download / upload speed on the Home screen
    val speedInNotification: Boolean = true,
    val dns: String = DnsChoice.AUTO,   // DNS provider id (see DnsChoice)
    val customDns: String = "",         // IP address(es) or https:// DoH address, for dns = "custom"
) {
    val targeted get() = mode == MODE_TARGETED
    val usesFree get() = source == SOURCE_FREE || source == SOURCE_BOTH
    val usesProxio get() = source == SOURCE_PROXIO || source == SOURCE_BOTH

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled); put("mode", mode); put("selected", selected)
        put("sites", JSONArray(sites)); put("autoPool", autoPool); put("country", country)
        put("source", source)
        put("autoSwitch", autoSwitch)
        put("showSpeed", showSpeed); put("speedInNotification", speedInNotification)
        put("dns", dns); put("customDns", customDns)
    }

    companion object {
        const val MODE_FULL = "full"
        const val MODE_TARGETED = "targeted"
        const val SOURCE_FREE = "free"       // free public servers
        const val SOURCE_PROXIO = "proxio"   // Proxio.io (your API key)
        const val SOURCE_BOTH = "both"       // Proxio.io + free
        const val SOURCE_OWN = "own"         // your own servers (VLESS, VMess, Trojan, SS, SOCKS, HTTP)
        const val SOURCE_CUSTOM = "custom"   // proxy integration: pasted curl / Python / Node.js code
        const val SOURCE_LIST = "list"       // proxy list file (host:port:user:pass lines)
        fun fromJson(o: JSONObject?): Prefs {
            if (o == null) return Prefs()
            val sites = o.optJSONArray("sites")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            return Prefs(
                enabled = o.optBoolean("enabled"), mode = o.optString("mode", MODE_FULL),
                selected = o.optString("selected", "auto"), sites = sites,
                autoPool = o.optInt("autoPool", 5), country = o.optString("country"),
                source = o.optString("source", SOURCE_FREE),
                autoSwitch = o.optBoolean("autoSwitch", true),
                showSpeed = o.optBoolean("showSpeed", true),
                speedInNotification = o.optBoolean("speedInNotification", true),
                dns = o.optString("dns", DnsChoice.AUTO), customDns = o.optString("customDns"),
            )
        }
    }
}

/** A server list you added yourself (link or pasted text). */
data class Source(
    val id: String, val type: String, val url: String = "", val name: String,
    val count: Int, val skipped: Int, val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("type", type); put("url", url); put("name", name)
        put("count", count); put("skipped", skipped); put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = Source(
            o.getString("id"), o.getString("type"), o.optString("url"), o.optString("name"),
            o.optInt("count"), o.optInt("skipped"), o.optLong("updatedAt"),
        )
    }
}

data class Status(
    val state: String = "off",          // off, connecting, on
    val label: String = "",
    val serverIds: List<String> = emptyList(),
    val error: String = "",
    val warning: String = "",
    val since: Long = 0,
)

data class FreeStatus(
    val state: String = "idle",         // idle, loading, testing
    val progress: Int = 0,
    val working: Int = 0,
    val found: Int = 0,
    val at: Long = 0,
    val error: String = "",
    val sources: Int = 0,
) {
    val busy get() = state == "loading" || state == "testing" || state == "speed"

    fun toJson(): JSONObject = JSONObject().apply {
        put("at", at); put("found", found); put("working", working); put("sources", sources); put("error", error)
    }

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) FreeStatus() else FreeStatus(
            at = o.optLong("at"), found = o.optInt("found"), working = o.optInt("working"),
            sources = o.optInt("sources"), error = o.optString("error"),
        )
    }
}

/** Loading / testing state of the Proxio.io list. */
data class ProxioStatus(
    val state: String = "idle",         // idle, loading, testing
    val at: Long = 0,
    val fetched: Int = 0,
    val working: Int = 0,
    val error: String = "",
) {
    val busy get() = state == "loading" || state == "testing"

    fun toJson(): JSONObject = JSONObject().apply {
        put("at", at); put("fetched", fetched); put("working", working); put("error", error)
    }

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) ProxioStatus() else ProxioStatus(
            at = o.optLong("at"), fetched = o.optInt("fetched"), working = o.optInt("working"), error = o.optString("error"),
        )
    }
}

data class AppState(
    val prefs: Prefs = Prefs(),
    val status: Status = Status(),
    val free: List<Server> = emptyList(),
    val own: List<Server> = emptyList(),
    val proxio: List<Server> = emptyList(),
    val proxioConfig: Proxio.Config = Proxio.Config(),
    val proxioStatus: ProxioStatus = ProxioStatus(),
    val custom: List<Server> = emptyList(),        // proxies from your pasted integration code
    val customCode: String = "",
    val customStatus: ProxioStatus = ProxioStatus(),
    val list: List<Server> = emptyList(),          // proxies from your .txt proxy list
    val listName: String = "",
    val listStatus: ProxioStatus = ProxioStatus(),
    val sources: List<Source> = emptyList(),
    val freeStatus: FreeStatus = FreeStatus(),
    val ipText: String = "",
    val xrayVersion: String = "",       // "" = Xray isn't included in this build
    val ownTesting: Boolean = false,    // your own servers are being tested
    val traffic: Traffic = Traffic(),
    val speedTest: SpeedTest = SpeedTest(),
) {
    val servers: List<Server> get() = (list + custom + proxio + free + own).distinctBy { it.id }
    val on: Boolean get() = prefs.enabled && status.state == "on"
    val connecting: Boolean get() = status.state == "connecting"
}

/** Live VPN traffic: bytes per second and totals since connecting. */
data class Traffic(val down: Long = 0, val up: Long = 0, val totalDown: Long = 0, val totalUp: Long = 0)

/** Result of the speed test in Settings. */
data class SpeedTest(
    val running: Boolean = false,
    val mbps: Double? = null,
    val ms: Int? = null,
    val server: String = "",
    val at: Long = 0,
    val error: String = "",
)

// ---------- helpers ----------

/** 1536 -> "1.5 KB/s". */
fun formatRate(bytesPerSec: Long): String = formatBytes(bytesPerSec) + "/s"

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
}

fun formatMbps(mbps: Double): String =
    if (mbps >= 10) String.format(Locale.US, "%.0f Mbps", mbps) else String.format(Locale.US, "%.1f Mbps", mbps)


/** FNV-1a 32-bit, same as the extension, so ids match. */
fun hashId(str: String): String {
    var h = 0x811c9dc5.toInt()
    for (ch in str) {
        h = h xor ch.code
        h *= 0x01000193
    }
    return String.format("%08x", h.toLong() and 0xffffffffL)
}

fun flagFromCode(code: String): String {
    if (!Regex("^[A-Za-z]{2}$").matches(code)) return "🌐"
    val sb = StringBuilder()
    for (c in code.uppercase()) sb.appendCodePoint(0x1F1E6 + (c - 'A'))
    return sb.toString()
}

fun countryName(code: String): String {
    if (!Regex("^[A-Za-z]{2}$").matches(code)) return "Unknown"
    @Suppress("DEPRECATION")
    val name = Locale("", code.uppercase()).getDisplayCountry(Locale.ENGLISH)
    return name.ifBlank { code.uppercase() }
}

/** "https://www.YouTube.com/watch" -> "youtube.com". Returns "" if it isn't a site. */
fun normalizeSite(input: String): String {
    var s = input.trim().lowercase()
    if (s.isEmpty()) return ""
    s = s.replace(Regex("^[a-z]+://"), "").split('/', '?', '#')[0].replace(Regex(":\\d+$"), "")
    s = s.removePrefix("*.").removePrefix("www.").removeSuffix(".")
    val domain = Regex("^[a-z0-9.-]+\\.[a-z0-9-]{2,}$")
    val ip = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")
    return if (domain.matches(s) || ip.matches(s)) s else ""
}

fun siteMatches(host: String, sites: Collection<String>): Boolean {
    val h = host.lowercase().removeSuffix(".")
    return sites.any { h == it || h.endsWith(".$it") }
}
