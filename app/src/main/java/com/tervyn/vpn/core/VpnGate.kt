package com.tervyn.vpn.core

import com.tervyn.vpn.ovpn.OpenVpnConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * VPN Gate (University of Tsukuba): free public OpenVPN servers run by
 * volunteers. The list is downloaded from the official API; each server comes
 * with its OpenVPN config and the line speed VPN Gate measured for it.
 */
object VpnGate {
    const val SOURCE = "vpngate"
    private val URLS = listOf("https://www.vpngate.net/api/iphone/", "http://www.vpngate.net/api/iphone/")

    /** One server from the list, before Tervyn tests it. */
    class Entry(val host: String, val ip: String, val score: Long, val ping: Int, val speedBps: Long, val code: String, val sessions: Int, val config: String)

    fun download(): String {
        var last: Exception? = null
        for (u in URLS) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 45_000
                c.setRequestProperty("User-Agent", "TervynVPN")
                try {
                    if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                    return c.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("Couldn't download the VPN Gate server list (${last?.message}).")
    }

    /** Parses the API's CSV. Servers with configs Tervyn can't use are skipped. */
    fun parse(csv: String): List<Entry> = csv.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("*") && !it.startsWith("#") }
        .mapNotNull { line ->
            val f = line.split(',', limit = 15)
            if (f.size < 15) return@mapNotNull null
            val config = runCatching { String(Base64.getDecoder().decode(f[14].trim()), Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
            Entry(
                host = f[0], ip = f[1], score = f[2].toLongOrNull() ?: 0, ping = f[3].toIntOrNull() ?: 0,
                speedBps = f[4].toLongOrNull() ?: 0, code = f[6].uppercase(), sessions = f[7].toIntOrNull() ?: 0, config = config,
            )
        }
        .toList()

    /** Entries as Tervyn servers (best first by VPN Gate's score), [max] of them, at most [perCountry] per country. */
    fun toServers(entries: List<Entry>, max: Int = 80, perCountry: Int = 12): List<Server> {
        val perC = HashMap<String, Int>()
        val out = ArrayList<Server>()
        for (e in entries.sortedByDescending { it.score }) {
            if (out.size >= max) break
            val cfg = runCatching { OpenVpnConfig.parse(e.config) }.getOrNull() ?: continue
            val n = perC[e.code] ?: 0
            if (n >= perCountry) continue
            perC[e.code] = n + 1
            val speed = if (e.speedBps > 0) " · %.0f Mbps line".format(e.speedBps / 1_000_000.0) else ""
            out += Server.make(
                "openvpn", cfg.host, cfg.port, e.code, source = SOURCE, free = true,
                name = "VPN Gate ${e.host} (${if (cfg.tcp) "TCP" else "UDP"})$speed",
            ).copy(link = e.config, score = (e.score / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        return out
    }
}
