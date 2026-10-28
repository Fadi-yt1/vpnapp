package com.tervyn.vpn.core

import org.json.JSONObject
import java.net.URLDecoder

/** Everything Xray needs to know about one VLESS / VMess / Trojan / Shadowsocks server. */
data class CoreSpec(
    val proto: String,               // vless, vmess, trojan, shadowsocks
    val address: String,
    val port: Int,
    val name: String = "",
    val uuid: String = "",
    val aid: Int = 0,
    val cipher: String = "auto",     // vmess security
    val password: String = "",       // trojan / shadowsocks
    val method: String = "",         // shadowsocks cipher
    val net: String = "tcp",
    val security: String = "none",   // none, tls, reality
    val sni: String = "",
    val fp: String = "",
    val alpn: List<String> = emptyList(),
    val pbk: String = "",
    val sid: String = "",
    val spx: String = "",
    val host: String = "",
    val path: String = "",
    val serviceName: String = "",
    val mode: String = "",
    val headerType: String = "",
    val allowInsecure: Boolean = false,   // old links; current Xray removed it, so it's ignored
    val pinnedSha256: String = "",       // pcs= : pinned certificate SHA-256 (replaces allowInsecure)
    val verifyName: String = "",         // vcn= : name to check the certificate against
    val flow: String = "",
    val extra: JSONObject? = null,
) {
    /** Transports and settings this app's Xray config builder handles. */
    val supported: Boolean
        get() = when {
            proto == "shadowsocks" -> method.isNotEmpty()
            net !in SUPPORTED_NETS -> false
            security == "reality" && pbk.isEmpty() -> false
            else -> true
        }

    /** "tcp+reality", "ws+tls", … for the server list. */
    val transport: String
        get() {
            if (proto == "shadowsocks") return "tcp"
            val n = if (net == "raw") "tcp" else net
            return if (security != "none" && security.isNotEmpty()) "$n+$security" else n
        }

    private companion object {
        val SUPPORTED_NETS = setOf("tcp", "raw", "ws", "grpc", "xhttp", "splithttp", "httpupgrade")
    }
}

/**
 * Reads share links and server list files (port of the extension's links.js).
 * Supported: vless://, vmess://, trojan://, ss://, socks(5)://, http(s)://
 */
object Links {
    class Parsed(val servers: List<Server>, val skipped: Int, val title: String?)

    private val FLAG_RE = Regex("([\\x{1F1E6}-\\x{1F1FF}]{2})")
    private val cache = object : LinkedHashMap<String, CoreSpec?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CoreSpec?>?) = size > 500
    }

    fun parse(input: String, sourceId: String): Parsed {
        var text = input.trim()
        if (text.isNotEmpty() && !text.contains("://")) {
            runCatching { text = b64(text) }
        }
        val out = LinkedHashMap<String, Server>()
        var skipped = 0
        var title: String? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                Regex("^#profile-title:\\s*(.*)$").find(line)?.let { title = it.groupValues[1] }
                continue
            }
            if (!line.contains("://")) {
                // "1.2.3.4:1080" style lines: assume SOCKS5
                val m = Regex("^([\\w.-]+):(\\d{2,5})$").find(line)
                if (m != null) {
                    val s = Server.make("socks5", m.groupValues[1], m.groupValues[2].toInt(), source = sourceId, free = false)
                    out[s.id] = s
                }
                continue
            }
            val s = runCatching { parseLine(line, sourceId) }.getOrNull()
            if (s == null) skipped++ else out[s.id] = s
        }
        return Parsed(out.values.toList(), skipped, title)
    }

    private fun parseLine(link: String, sourceId: String): Server? {
        val scheme = link.substringBefore("://").lowercase()
        if (scheme in setOf("socks", "socks5", "socks5h", "http", "https")) return parseDirect(link, scheme, sourceId)
        val spec = parseCore(link) ?: return null
        if (!spec.supported) return null
        val (code, nameCountry) = countryFromName(spec.name)
        return Server(
            id = hashId(link), proto = spec.proto, address = spec.address, port = spec.port,
            code = code, name = spec.name.ifBlank { nameCountry ?: "${spec.address}:${spec.port}" },
            free = false, sourceId = sourceId, link = link,
        )
    }

    /** Parses a VLESS / VMess / Trojan / Shadowsocks link. Cached, because Xray configs are built often. */
    fun spec(link: String): CoreSpec? = synchronized(cache) {
        if (cache.containsKey(link)) cache[link] else runCatching { parseCore(link) }.getOrNull().also { cache[link] = it }
    }

    fun parseCore(line: String): CoreSpec? {
        val link = line.trim()
        val scheme = link.substringBefore("://").lowercase()
        if (scheme == "vmess") {
            val j = JSONObject(b64(link.substring(8).substringBefore('#')))
            val net = j.optString("net", "tcp").ifEmpty { "tcp" }.lowercase()
            val tls = j.optString("tls")
            return CoreSpec(
                proto = "vmess", address = j.getString("add"), port = j.get("port").toString().toInt(),
                uuid = j.getString("id"), aid = j.optString("aid", "0").toIntOrNull() ?: 0,
                cipher = j.optString("scy", "auto").ifEmpty { "auto" }, name = j.optString("ps"),
                net = net,
                security = when (tls) { "tls" -> "tls"; "reality" -> "reality"; else -> "none" },
                sni = j.optString("sni"), fp = j.optString("fp"),
                alpn = j.optString("alpn").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                host = j.optString("host"), path = j.optString("path"),
                serviceName = if (net == "grpc") j.optString("path") else "",
                mode = if (j.optString("type") == "multi") "multi" else "",
                headerType = if (j.optString("type") == "http") "http" else "",
                pbk = j.optString("pbk"), sid = j.optString("sid"), spx = j.optString("spx"),
            )
        }

        val p = split(link)
        when (scheme) {
            "vless", "trojan" -> {
                val (address, port) = hostPort(p.hostPort)
                var s = streamFromParams(p.params, scheme, address, port, p.name)
                if (scheme == "trojan" && p.params["security"] == null) s = s.copy(security = "tls")
                return if (scheme == "vless") s.copy(uuid = pct(p.user)) else s.copy(password = pct(p.user))
            }
            "ss" -> {
                if (p.params["plugin"] != null) return null // ss plugins are not supported
                val method: String
                val password: String
                val hp: Pair<String, Int>
                if (p.user.isNotEmpty()) {
                    var info = pct(p.user)
                    if (':' !in info) info = b64(info)
                    method = info.substringBefore(':')
                    password = info.substringAfter(':')
                    hp = hostPort(p.hostPort)
                } else {
                    // legacy: ss://base64(method:password@host:port)
                    val dec = b64(p.hostPort)
                    val at = dec.lastIndexOf('@')
                    method = dec.substring(0, at).substringBefore(':')
                    password = dec.substring(0, at).substringAfter(':')
                    hp = hostPort(dec.substring(at + 1))
                }
                return CoreSpec("shadowsocks", hp.first, hp.second, p.name, method = method, password = password)
            }
        }
        return null
    }

    private fun parseDirect(link: String, scheme: String, sourceId: String): Server? {
        val p = split(link)
        val (address, port) = hostPort(p.hostPort)
        var username = ""
        var password = ""
        if (p.user.isNotEmpty()) {
            var info = pct(p.user)
            if (':' !in info) runCatching { info = b64(info) }
            username = info.substringBefore(':')
            password = info.substringAfter(':', "")
        }
        val (code, _) = countryFromName(p.name)
        val proto = if (scheme.startsWith("socks")) "socks5" else "http"
        return Server.make(proto, address, port, code, sourceId, free = false, username = username, password = password, name = p.name)
    }

    private class Split(val user: String, val hostPort: String, val params: Map<String, String>, val name: String)

    /** scheme://userinfo@host:port?query#name, without relying on URL() quirks for custom schemes. */
    private fun split(link: String): Split {
        var rest = link.substringAfter("://")
        var name = ""
        if ('#' in rest) { name = pct(rest.substringAfter('#')); rest = rest.substringBefore('#') }
        var query = ""
        if ('?' in rest) { query = rest.substringAfter('?'); rest = rest.substringBefore('?') }
        rest = rest.trimEnd('/')
        var user = ""
        if ('@' in rest) { user = rest.substringBeforeLast('@'); rest = rest.substringAfterLast('@') }
        val params = HashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val k = form(pair.substringBefore('='))
            if (k !in params) params[k] = form(pair.substringAfter('=', ""))
        }
        return Split(user, rest, params, name)
    }

    private fun streamFromParams(p: Map<String, String>, proto: String, address: String, port: Int, name: String) = CoreSpec(
        proto = proto, address = address, port = port, name = name,
        net = (p["type"] ?: "tcp").lowercase(),
        security = (p["security"] ?: "none").lowercase(),
        sni = p["sni"] ?: p["peer"] ?: "",
        fp = p["fp"] ?: "",
        alpn = (p["alpn"] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() },
        pbk = p["pbk"] ?: "", sid = p["sid"] ?: "", spx = p["spx"] ?: "",
        host = p["host"] ?: "", path = p["path"] ?: "",
        serviceName = p["serviceName"] ?: p["servicename"] ?: "",
        mode = p["mode"] ?: "", headerType = p["headerType"] ?: "",
        allowInsecure = p["allowInsecure"] == "1" || p["insecure"] == "1" || p["allowInsecure"] == "true",
        pinnedSha256 = p["pcs"] ?: p["pinnedPeerCertSha256"] ?: "",
        verifyName = p["vcn"] ?: p["verifyPeerCertByName"] ?: "",
        flow = p["flow"] ?: "",
        extra = p["extra"]?.takeIf { it.isNotEmpty() && it != "null" }?.let { runCatching { JSONObject(it) }.getOrNull() },
    )

    /** Handles host:port and [ipv6]:port. */
    private fun hostPort(hp: String): Pair<String, Int> {
        val m = Regex("^\\[([^\\]]+)]:(\\d+)$").find(hp) ?: Regex("^([^:]+):(\\d+)$").find(hp)
            ?: throw IllegalArgumentException("bad host:port $hp")
        return m.groupValues[1] to m.groupValues[2].toInt()
    }

    /** Country code + country text from a name like "🇩🇪 Germany ┃ fast". */
    private fun countryFromName(name: String): Pair<String, String?> {
        val flag = FLAG_RE.find(name)?.value ?: return "" to null
        val code = flag.codePoints().toArray().joinToString("") { (it - 0x1F1E6 + 'A'.code).toChar().toString() }
        val after = name.substringAfter(flag).split('┃', '|', ',')[0].trim()
        return code to after.ifEmpty { null }
    }

    /** decodeURIComponent: "+" stays "+". */
    private fun pct(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    /** URLSearchParams decoding: "+" becomes a space. */
    private fun form(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    fun b64(s: String): String {
        var clean = s.trim().replace('-', '+').replace('_', '/').replace(Regex("\\s+"), "")
        while (clean.length % 4 != 0) clean += "="
        return String(java.util.Base64.getDecoder().decode(clean), Charsets.UTF_8)
    }
}
