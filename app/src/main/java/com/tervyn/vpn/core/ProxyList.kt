package com.tervyn.vpn.core

/**
 * Reads proxy list files like the ones Webshare, IPRoyal, Proxy-Seller and
 * others let you download, one proxy per line:
 *
 *   host:port:user:pass      (Webshare "Download proxy list")
 *   user:pass@host:port
 *   user:pass:host:port
 *   host:port
 *   http://user:pass@host:port, socks5://host:port
 *
 * Lines without a scheme use [defaultProto] ("http" or "socks5"), because the
 * file doesn't say which one it is.
 */
object ProxyList {
    const val SOURCE = "list"

    class Result(val servers: List<Server>, val skipped: Int)

    private val HOST_RE = Regex("^(\\d{1,3}(\\.\\d{1,3}){3}|[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+)$", RegexOption.IGNORE_CASE)

    private fun isHost(s: String) = HOST_RE.matches(s)
    private fun isPort(s: String) = s.toIntOrNull()?.let { it in 1..65535 } == true

    fun parse(text: String, defaultProto: String): Result {
        val out = LinkedHashMap<String, Server>()
        var skipped = 0
        for (raw in text.lines()) {
            val line = raw.trim().trimStart('﻿')
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            val s = parseLine(line, defaultProto)
            if (s == null) skipped++ else out[s.id] = s
        }
        return Result(out.values.toList(), skipped)
    }

    fun parseLine(line: String, defaultProto: String): Server? {
        var s = line.split(Regex("[\\s,;|]+"))[0]
        var proto = defaultProto
        Regex("^([a-z0-9]+)://", RegexOption.IGNORE_CASE).find(s)?.let { proto = it.groupValues[1].lowercase(); s = s.substring(it.value.length) }
        s = s.trimEnd('/')
        proto = when (proto) {
            "http", "https" -> "http"
            "socks5", "socks5h", "socks" -> "socks5"
            else -> return null
        }
        var host: String
        var port: String
        var user = ""
        var pass = ""
        if ('@' in s) {
            val cred = s.substringBeforeLast('@')
            val hp = s.substringAfterLast('@')
            // user:pass@host:port, or the less common host:port@user:pass
            if (isHost(cred.substringBefore(':')) && isPort(cred.substringAfter(':', "")) && !isPort(hp.substringAfter(':', ""))) {
                host = cred.substringBefore(':'); port = cred.substringAfter(':')
                user = hp.substringBefore(':'); pass = hp.substringAfter(':', "")
            } else {
                user = cred.substringBefore(':'); pass = cred.substringAfter(':', "")
                host = hp.substringBefore(':'); port = hp.substringAfter(':', "")
            }
        } else {
            val p = s.split(':')
            when {
                p.size == 2 -> { host = p[0]; port = p[1] }
                p.size >= 4 && isHost(p[0]) && isPort(p[1]) -> { host = p[0]; port = p[1]; user = p[2]; pass = p.drop(3).joinToString(":") }
                p.size >= 4 && isHost(p[p.size - 2]) && isPort(p[p.size - 1]) -> {
                    user = p[0]; pass = p.subList(1, p.size - 2).joinToString(":"); host = p[p.size - 2]; port = p[p.size - 1]
                }
                else -> return null
            }
        }
        if (!isHost(host) || !isPort(port)) return null
        val portNum = port.toInt()
        return Server(
            id = hashId("$SOURCE:$proto://$user@$host:$portNum"), proto = proto, address = host, port = portNum,
            username = user, password = pass, name = "${proto.uppercase()} $host:$portNum", free = false, sourceId = SOURCE,
        )
    }
}
