package com.tervyn.vpn.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Servers from Proxio.io, using your own API key (port of the extension's proxio.js).
 * The key is kept on this phone only and is sent only to proxio.io.
 */
object Proxio {
    private const val API = "https://proxio.io/api/list"
    private val HOST_RE = Regex("^(\\d{1,3}(\\.\\d{1,3}){3}|[a-z0-9.-]+\\.[a-z]{2,})$", RegexOption.IGNORE_CASE)

    /** type: "socks5", "http" or "both". */
    data class Config(val key: String = "", val type: String = "socks5", val limit: Int = 50) {
        val keyHint: String get() = if (key.length > 4) "••••" + key.takeLast(4) else ""

        fun toJson(): JSONObject = JSONObject().put("key", key).put("type", type).put("limit", limit)

        companion object {
            fun fromJson(o: JSONObject?) = if (o == null) Config() else
                Config(o.optString("key"), o.optString("type", "socks5"), o.optInt("limit", 50))
        }
    }

    private fun normProto(p: String?, fallback: String?): String? {
        val v = (p?.takeIf { it.isNotEmpty() } ?: fallback ?: "").lowercase()
        return when {
            v.startsWith("socks5") || v == "socks" -> "socks5"
            v == "http" || v == "https" -> "http"   // HTTP proxy (works for HTTPS sites too)
            else -> null                              // socks4 etc. not supported
        }
    }

    private fun make(proto: String?, host: String?, port: Any?, username: String = "", password: String = "", code: String = ""): Server? {
        val p = port?.toString()?.trim()?.toIntOrNull() ?: return null
        val h = host?.trim() ?: return null
        if (proto == null || !HOST_RE.matches(h) || p !in 1..65535) return null
        val key = "proxio:$proto://$username@$h:$p"
        return Server(
            id = hashId(key), proto = proto, address = h, port = p, username = username, password = password,
            code = if (Regex("^[A-Za-z]{2}$").matches(code)) code.uppercase() else "",
            name = "${proto.uppercase()} $h:$p", free = false, sourceId = Server.PROXIO_SOURCE,
        )
    }

    private fun dec(v: String) = runCatching { URLDecoder.decode(v.replace("+", "%2B"), "UTF-8") }.getOrDefault(v)

    /** One line: proto://user:pass@host:port, user:pass@host:port, host:port:user:pass or host:port. */
    fun parseLine(line: String, defaultProto: String): Server? {
        var s = line.trim()
        if (s.isEmpty() || s.startsWith("#")) return null
        var proto: String? = defaultProto
        Regex("^([a-z0-9]+)://", RegexOption.IGNORE_CASE).find(s)?.let { proto = it.groupValues[1]; s = s.substring(it.value.length) }
        var username = ""
        var password = ""
        val at = s.lastIndexOf('@')
        if (at >= 0) {
            val cred = s.substring(0, at)
            username = cred.substringBefore(':')
            password = cred.substringAfter(':', "")
            s = s.substring(at + 1)
        }
        val parts = s.split(Regex("[\\s,;]+"))[0].split(':')
        if (at < 0 && parts.size >= 4) {
            username = parts[2]
            password = parts.drop(3).joinToString(":")
        }
        return make(normProto(proto, null), parts.getOrNull(0), parts.getOrNull(1), dec(username), dec(password))
    }

    /** Reads whatever shape the API returns: JSON objects, JSON strings, or plain text. */
    fun parseResponse(text: String, defaultProto: String): List<Server> {
        val out = ArrayList<Server>()
        val data: Any? = runCatching { JSONTokener(text).nextValue() }.getOrNull()?.takeIf { it is JSONObject || it is JSONArray }
        if (data == null) {
            for (line in text.lines()) parseLine(line, defaultProto)?.let(out::add)
            return out
        }
        fun pick(o: JSONObject?, vararg keys: String): Any? {
            if (o == null) return null
            for (k in keys) {
                val v = o.opt(k)
                if (v != null && v != JSONObject.NULL && v.toString().isNotEmpty()) return v
            }
            return null
        }
        fun walk(v: Any?, depth: Int) {
            if (depth > 5 || v == null || v == JSONObject.NULL) return
            when (v) {
                is String -> parseLine(v, defaultProto)?.let(out::add)
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), depth + 1)
                is JSONObject -> {
                    val host = pick(v, "ip", "host", "address", "server", "hostname")
                    val port = pick(v, "port")
                    if (host != null && port != null) {
                        val geo = (v.opt("geolocation") ?: v.opt("geo") ?: v.opt("location") ?: v.opt("ip_data")) as? JSONObject
                        val code = (pick(v, "country_code", "countryCode", "cc")
                            ?: pick(geo, "country_code", "countryCode", "country") ?: v.opt("country")) as? String ?: ""
                        val protos = when (val p = pick(v, "protocols", "protocol", "type", "scheme")) {
                            is JSONArray -> List(p.length()) { p.optString(it) }
                            else -> listOf(p?.toString())
                        }
                        val user = pick(v, "username", "user", "login")?.toString() ?: ""
                        val pass = pick(v, "password", "pass")?.toString() ?: ""
                        for (p in protos) make(normProto(p, defaultProto), host.toString(), port, user, pass, code)?.let(out::add)
                        return
                    }
                    val proxy = v.opt("proxy")
                    if (proxy is String) { parseLine(proxy, defaultProto)?.let(out::add); return }
                    for (k in v.keys()) walk(v.opt(k), depth + 1)
                }
            }
        }
        walk(data, 0)
        return out
    }

    /** Downloads the list for each requested type. Throws a readable error on failure. */
    fun fetch(cfg: Config): List<Server> {
        if (cfg.key.isBlank()) throw IOException("Enter your Proxio.io API key first.")
        val types = if (cfg.type == "both") listOf("socks5", "http") else listOf(cfg.type)
        val all = LinkedHashMap<String, Server>()
        for (t in types) {
            val url = URL("$API?type=${URLEncoder.encode(t, "UTF-8")}&limit=${cfg.limit.coerceIn(1, 1000)}")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 20_000
            c.useCaches = false
            c.setRequestProperty("X-Key", cfg.key.trim())
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            c.setRequestProperty("User-Agent", "TervynVPN/1.0 (Android)")
            val code: Int
            val text: String
            try {
                code = c.responseCode
                text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (e: SocketTimeoutException) {
                throw IOException("Proxio.io did not answer in time. Try again.")
            } catch (e: IOException) {
                throw IOException("Could not reach proxio.io. Check your internet connection.")
            } finally {
                c.disconnect()
            }
            when {
                code == 401 || code == 403 -> throw IOException("Proxio.io rejected the API key. Check that it is correct and active.")
                code == 429 -> throw IOException("Proxio.io says too many requests. Wait a minute and try again.")
                code !in 200..299 -> throw IOException("Proxio.io error ($code): ${text.take(120)}")
            }
            for (s in parseResponse(text, t)) all[s.id] = s
        }
        return all.values.toList()
    }
}
