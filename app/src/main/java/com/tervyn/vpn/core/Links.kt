package com.tervyn.vpn.core

import android.util.Base64

/**
 * Reads your own server lists. Plain socks5:// and http:// proxies work on
 * Android directly. VLESS / VMess / Trojan / Shadowsocks need Xray-core, which
 * this app does not include yet, so those lines are counted as skipped.
 */
object Links {
    class Parsed(val servers: List<Server>, val skipped: Int, val title: String?)

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
            val s = runCatching { parseLink(line, sourceId) }.getOrNull()
            if (s == null) skipped++ else out[s.id] = s
        }
        return Parsed(out.values.toList(), skipped, title)
    }

    private fun parseLink(link: String, sourceId: String): Server? {
        val scheme = link.substringBefore("://").lowercase()
        if (scheme !in setOf("socks", "socks5", "socks5h", "http", "https")) return null
        var rest = link.substringAfter("://")
        var name = ""
        if ('#' in rest) {
            name = runCatching { java.net.URLDecoder.decode(rest.substringAfter('#'), "UTF-8") }.getOrDefault(rest.substringAfter('#'))
            rest = rest.substringBefore('#')
        }
        rest = rest.substringBefore('?').trimEnd('/')
        var user = ""
        if ('@' in rest) {
            user = rest.substringBeforeLast('@'); rest = rest.substringAfterLast('@')
        }
        val m = Regex("^\\[([^\\]]+)]:(\\d+)$").find(rest) ?: Regex("^([^:]+):(\\d+)$").find(rest) ?: return null
        var username = ""
        var password = ""
        if (user.isNotEmpty()) {
            var info = runCatching { java.net.URLDecoder.decode(user, "UTF-8") }.getOrDefault(user)
            if (':' !in info) runCatching { info = b64(info) }
            username = info.substringBefore(':')
            password = info.substringAfter(':', "")
        }
        val proto = if (scheme.startsWith("socks")) "socks5" else "http"
        val flagCode = Regex("([\\x{1F1E6}-\\x{1F1FF}]{2})").find(name)?.value?.let { f ->
            f.codePoints().toArray().joinToString("") { (it - 0x1F1E6 + 'A'.code).toChar().toString() }
        } ?: ""
        return Server.make(proto, m.groupValues[1], m.groupValues[2].toInt(), flagCode, sourceId, free = false,
            username = username, password = password, name = name)
    }

    private fun b64(s: String): String {
        val clean = s.trim().replace('-', '+').replace('_', '/').replace(Regex("\\s+"), "")
        return String(Base64.decode(clean, Base64.DEFAULT), Charsets.UTF_8)
    }
}
