package com.tervyn.vpn.core

import java.net.URLDecoder

/**
 * Finds proxies in the code examples proxy providers give you (Webshare,
 * Bright Data, Oxylabs, IPRoyal…), for example:
 *
 *   curl --proxy "http://user:pass@1.2.3.4:6014/" https://ipv4.webshare.io/
 *   requests.get(url, proxies={"http": "http://user:pass@1.2.3.4:6014/", ...})
 *   axios.get(url, { proxy: { host: '1.2.3.4', port: 6014, auth: { username: 'user', password: 'pass' } } })
 *   new HttpsProxyAgent('http://user:pass@1.2.3.4:6014')
 *
 * Also reads variables, f-strings / template strings and "a" + b joins, and
 * plain host:port:user:pass lists.
 */
object ProxySnippet {
    const val SOURCE = "custom"

    class Result(val servers: List<Server>, val unsupported: Int)

    private val URL_RE = Regex("""\b(https?|socks5h?|socks4a?|socks)://[^\s'"`),;}\]]+""", RegexOption.IGNORE_CASE)
    private val STR = """"((?:[^"\\]|\\.)*)"|'((?:[^'\\]|\\.)*)'|`([^`]*)`"""

    fun parse(code: String): Result {
        // curl line continuations
        val text = code.replace(Regex("\\\\\\r?\\n"), " ").replace(Regex("\\^\\r?\\n"), " ")
        val vars = variables(text)
        val expanded = expand(text, vars)

        val found = LinkedHashMap<String, Server>()
        var unsupported = 0
        fun add(proto: String?, host: String?, port: Int?, user: String, pass: String) {
            val p = when (proto?.lowercase()) {
                null, "", "http", "https" -> "http"
                "socks5", "socks5h", "socks" -> "socks5"
                else -> { unsupported++; return }
            }
            if (host.isNullOrBlank() || port == null || port !in 1..65535) return
            val s = make(p, host.trim(), port, user, pass)
            found[s.id] = s
        }

        // 1. curl options
        val curlUser = flagValue(expanded, listOf("--proxy-user", "-U"))
        for (v in flagValues(expanded, listOf("--proxy", "-x", "--preproxy"))) addUrl(v, "http", curlUser, ::add)
        for (v in flagValues(expanded, listOf("--socks5", "--socks5-hostname"))) addUrl(v, "socks5", curlUser, ::add)
        for (v in flagValues(expanded, listOf("--socks4", "--socks4a"))) if (v.isNotBlank()) unsupported++

        // 2. Proxy URLs anywhere (requests proxies, agents, env vars…). A URL counts as a
        //    proxy if it has a port or a login and no page path, so the target site is skipped.
        for (m in URL_RE.findAll(expanded)) {
            val u = m.value.trimEnd('.')
            val rest = u.substringAfter("://")
            val hostPart = rest.substringBefore('/')
            val path = rest.substringAfter('/', "")
            val isSocks = u.startsWith("socks", ignoreCase = true)
            val looksLikeProxy = path.isEmpty() && (hostPart.contains('@') || Regex(":\\d{2,5}$").containsMatchIn(hostPart) || isSocks)
            if (looksLikeProxy) addUrl(u, "http", curlUser, ::add)
        }

        // 3. Object / dict / variables: host + port (+ username / password), e.g. axios' proxy option.
        if (found.isEmpty()) {
            val host = pick(vars, "proxy_host", "proxyhost", "host", "hostname", "server", "proxy_server", "ip", "address")
            val port = pick(vars, "proxy_port", "proxyport", "port")?.toIntOrNull()
            if (host != null && port != null && !host.contains('/')) {
                add(
                    pick(vars, "protocol", "scheme", "proxy_type", "type")?.removeSuffix(":"),
                    host, port,
                    pick(vars, "proxy_user", "proxy_username", "username", "user", "login") ?: "",
                    pick(vars, "proxy_pass", "proxy_password", "password", "pass", "pwd") ?: "",
                )
            }
        }

        // 4. Plain lists: host:port:user:pass, user:pass@host:port, host:port
        if (found.isEmpty()) {
            for (line in text.lines()) {
                val l = line.trim()
                if (l.isEmpty() || l.contains(' ') || l.startsWith("#") || l.startsWith("//")) continue
                Proxio.parseLine(l, "http")?.let { s -> add(s.proto, s.address, s.port, s.username, s.password) }
            }
        }
        return Result(found.values.toList(), unsupported)
    }

    private fun make(proto: String, host: String, port: Int, user: String, pass: String) = Server(
        id = hashId("$SOURCE:$proto://$user@$host:$port"), proto = proto, address = host, port = port,
        username = user, password = pass, name = "${proto.uppercase()} $host:$port", free = false, sourceId = SOURCE,
    )

    /** scheme://user:pass@host:port/ or host:port (curl assumes [defaultProto]). */
    private fun addUrl(
        raw: String, defaultProto: String, curlUser: String?,
        add: (String?, String?, Int?, String, String) -> Unit,
    ) {
        var s = raw.trim().trim('"', '\'')
        if (s.isEmpty()) return
        var proto = defaultProto
        Regex("^([a-z0-9]+)://", RegexOption.IGNORE_CASE).find(s)?.let { proto = it.groupValues[1]; s = s.substring(it.value.length) }
        s = s.substringBefore('/').substringBefore('?')
        var user = ""
        var pass = ""
        if ('@' in s) {
            val cred = s.substringBeforeLast('@')
            user = dec(cred.substringBefore(':'))
            pass = dec(cred.substringAfter(':', ""))
            s = s.substringAfterLast('@')
        } else if (curlUser != null) {
            user = curlUser.substringBefore(':')
            pass = curlUser.substringAfter(':', "")
        }
        val m = Regex("^\\[?([^\\]]+?)]?:(\\d{1,5})$").find(s)
        val (host, port) = if (m != null) m.groupValues[1] to m.groupValues[2].toInt()
            else s to 1080 // curl's default proxy port
        add(proto, host, port, user, pass)
    }

    // ---------- curl flags ----------
    private fun flagValues(text: String, names: List<String>): List<String> {
        val out = ArrayList<String>()
        for (n in names) {
            val re = Regex("""(?:^|\s)${Regex.escape(n)}(?:\s+|=)(?:"([^"]*)"|'([^']*)'|(\S+))""")
            for (m in re.findAll(text)) out += m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
        }
        return out
    }

    private fun flagValue(text: String, names: List<String>) = flagValues(text, names).firstOrNull { it.isNotBlank() }

    // ---------- variables and strings ----------
    /** name = "value", name: 'value', "name": "value", name = 6014 … (first one wins). */
    private fun variables(text: String): Map<String, String> {
        val vars = LinkedHashMap<String, String>()
        val re = Regex("""["']?([A-Za-z_][\w]*)["']?\s*(?::|=(?!=))\s*(?:$STR|(\d+)\b)""")
        for (m in re.findAll(text)) {
            val name = m.groupValues[1].lowercase()
            val value = m.groupValues.drop(2).firstOrNull { it.isNotEmpty() } ?: ""
            if (name !in vars && value.isNotEmpty()) vars[name] = value
        }
        return vars
    }

    /** Fills in f"…{x}…", `…${x}…` and "a" + b + "c" with variable values. */
    private fun expand(text: String, vars: Map<String, String>): String {
        fun v(name: String) = vars[name.trim().lowercase()]
        var t = text
        // "a" + b + 'c' (Python / JavaScript string joins)
        val part = """(?:$STR|[A-Za-z_][\w.]*)"""
        val join = Regex("""$part(?:\s*\+\s*$part)+""")
        t = join.replace(t) { m ->
            if (!m.value.contains('"') && !m.value.contains('\'') && !m.value.contains('`')) return@replace m.value
            val sb = StringBuilder()
            for (p in Regex(part).findAll(m.value)) {
                val lit = p.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
                when {
                    lit != null -> sb.append(lit)
                    p.value.startsWith("\"") || p.value.startsWith("'") || p.value.startsWith("`") -> {}
                    else -> sb.append(v(p.value.substringAfterLast('.')) ?: return@replace m.value)
                }
            }
            "\"" + sb + "\""
        }
        // ${x} and {x}
        t = Regex("""\$\{\s*([A-Za-z_][\w.]*)\s*}""").replace(t) { v(it.groupValues[1].substringAfterLast('.')) ?: it.value }
        t = Regex("""\{\s*([A-Za-z_][\w]*)\s*}""").replace(t) { v(it.groupValues[1]) ?: it.value }
        return t
    }

    private fun pick(vars: Map<String, String>, vararg keys: String): String? {
        for (k in keys) vars[k]?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    private fun dec(v: String) = runCatching { URLDecoder.decode(v.replace("+", "%2B"), "UTF-8") }.getOrDefault(v)
}
