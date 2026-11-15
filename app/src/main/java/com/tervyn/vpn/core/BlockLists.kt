package com.tervyn.vpn.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * DNS filtering lists (ads, trackers, malware, phishing), downloaded from
 * maintained public lists and kept on the phone as sorted 64-bit hashes of
 * domain names: compact (8 bytes per domain) and fast to search. A domain is
 * blocked if it, or any parent domain, is on an enabled list.
 */
class BlockLists(private val dir: File) {

    data class Category(val id: String, val name: String, val about: String, val urls: List<String>)

    data class Info(val count: Int = 0, val updatedAt: Long = 0, val error: String = "")

    companion object {
        const val ADS = "ads"
        const val TRACKERS = "trackers"
        const val MALWARE = "malware"
        const val PHISHING = "phishing"
        const val CUSTOM = "custom"
        private const val GH = "https://raw.githubusercontent.com/"

        val CATEGORIES = listOf(
            Category(ADS, "Advertising", "Ad servers in apps and websites", listOf(
                GH + "anudeepND/blacklist/master/adservers.txt",
                GH + "StevenBlack/hosts/master/hosts",
            )),
            Category(TRACKERS, "Tracking", "Analytics and device telemetry", listOf(
                GH + "Perflyst/PiHoleBlocklist/master/android-tracking.txt",
                GH + "hagezi/dns-blocklists/main/wildcard/native.samsung-onlydomains.txt",
                GH + "hagezi/dns-blocklists/main/wildcard/native.xiaomi-onlydomains.txt",
                GH + "hagezi/dns-blocklists/main/wildcard/native.huawei-onlydomains.txt",
                GH + "hagezi/dns-blocklists/main/wildcard/native.tiktok-onlydomains.txt",
                GH + "hagezi/dns-blocklists/main/wildcard/native.winoffice-onlydomains.txt",
            )),
            Category(MALWARE, "Malware", "Malware, scam and threat sites (HaGeZi threat intelligence)", listOf(
                GH + "hagezi/dns-blocklists/main/wildcard/tif.mini-onlydomains.txt",
            )),
            Category(PHISHING, "Phishing", "Sites that steal logins and payment details", listOf(
                GH + "RPiList/specials/master/Blocklisten/Phishing-Angriffe",
            )),
        )

        private val DOMAIN = Regex("^(?=.{4,253}$)([a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?\\.)+[a-z][a-z0-9-]{0,62}$")
        private val SKIP = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback", "0.0.0.0")

        /** FNV-1a 64-bit hash of a lower-case domain. */
        fun hash(s: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (ch in s) {
                h = h xor ch.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }

        /** One line of a hosts file, a plain domain list or an adblock "||domain^" list -> domain, or null. */
        fun parseLine(raw: String): String? {
            var s = raw.substringBefore('#').trim()
            if (s.isEmpty() || s.startsWith("!") || s.startsWith("[")) return null
            if (s.startsWith("||")) s = s.removePrefix("||").substringBefore('^').substringBefore('$')
            else {
                val parts = s.split(Regex("\\s+"))
                s = if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::" || parts[0] == "::1")) parts[1] else parts[0]
            }
            s = s.lowercase().removePrefix("*.").removePrefix(".").trimEnd('.')
            if (s in SKIP || !DOMAIN.matches(s)) return null
            return s
        }

        /** Parses list text into a sorted, de-duplicated hash array. */
        fun build(texts: List<String>): LongArray {
            val set = HashSet<Long>()
            for (t in texts) for (line in t.lineSequence()) parseLine(line)?.let { set.add(hash(it)) }
            return set.toLongArray().also { it.sort() }
        }

        /** The domain and each parent: a.b.example.com, b.example.com, example.com. */
        fun suffixes(domain: String): List<String> {
            val d = domain.lowercase().trimEnd('.')
            val out = ArrayList<String>()
            var i = 0
            while (true) {
                val rest = d.substring(i)
                if (!rest.contains('.')) break
                out += rest
                val next = d.indexOf('.', i)
                if (next < 0) break
                i = next + 1
            }
            return out
        }
    }

    private val sets = ConcurrentHashMap<String, LongArray>()
    private val infos = ConcurrentHashMap<String, Info>()

    init {
        dir.mkdirs()
        for (c in CATEGORIES) runCatching { load(c.id) }
    }

    private fun file(id: String) = File(dir, "$id.bin")

    private fun load(id: String) {
        val f = file(id)
        if (!f.exists()) return
        DataInputStream(f.inputStream().buffered()).use { inp ->
            val updated = inp.readLong()
            val n = inp.readInt()
            val arr = LongArray(n) { inp.readLong() }
            sets[id] = arr
            infos[id] = Info(n, updated)
        }
    }

    fun info(id: String): Info = infos[id] ?: Info()

    fun isLoaded(id: String) = (sets[id]?.size ?: 0) > 0

    /** Downloads and stores one category. Returns the number of domains. */
    fun update(id: String): Int {
        val cat = CATEGORIES.firstOrNull { it.id == id } ?: throw IOException("Unknown list $id")
        val texts = ArrayList<String>()
        var lastErr: Exception? = null
        for (u in cat.urls) {
            try { texts += download(u) } catch (e: Exception) { lastErr = e }
        }
        if (texts.isEmpty()) {
            infos[id] = info(id).copy(error = "Couldn't download the list: ${lastErr?.message}")
            throw IOException("Couldn't download the ${cat.name} list. Check your internet connection.")
        }
        val arr = build(texts)
        val now = System.currentTimeMillis()
        val tmp = File(dir, "$id.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeLong(now)
            out.writeInt(arr.size)
            for (h in arr) out.writeLong(h)
        }
        tmp.renameTo(file(id))
        sets[id] = arr
        infos[id] = Info(arr.size, now)
        return arr.size
    }

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "TervynVPN")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Which list blocks [domain] ([CUSTOM] for your own block list), or null.
     * Your allow list always wins.
     */
    fun match(domain: String, enabled: Set<String>, block: List<String>, allow: List<String>): String? {
        val sfx = suffixes(domain)
        if (sfx.isEmpty()) return null
        if (allow.isNotEmpty() && sfx.any { it in allow }) return null
        if (block.isNotEmpty() && sfx.any { it in block }) return CUSTOM
        if (enabled.isEmpty()) return null
        val hashes = sfx.map { hash(it) }
        for (id in enabled) {
            val arr = sets[id] ?: continue
            if (hashes.any { java.util.Arrays.binarySearch(arr, it) >= 0 }) return id
        }
        return null
    }

    fun remove(id: String) {
        sets.remove(id)
        infos.remove(id)
        file(id).delete()
    }
}
