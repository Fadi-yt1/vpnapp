package com.tervyn.vpn.core

import com.tervyn.vpn.vpn.Dns
import com.tervyn.vpn.vpn.DnsClient
import com.tervyn.vpn.vpn.DnsResolver
import com.tervyn.vpn.vpn.DnsResolver.Upstream
import com.tervyn.vpn.vpn.Ip

/**
 * What happens to every DNS lookup an app makes while the VPN is on:
 * filtering, resolving with your chosen resolver (through the VPN tunnel when
 * leak protection is on), caching, and honest counters for the DNS Center.
 */
class DnsManager(
    val lists: BlockLists,
    private val systemDns: () -> List<String>,
    val resolver: DnsResolver = DnsResolver(),
) {
    private val lock = Any()
    private var stats = DnsStats()

    private val cache = object : LinkedHashMap<String, Pair<Long, ByteArray>>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, ByteArray>>?) = size > 3000
    }

    /** Domain -> IPv4 address from your resolver (VPN sites connect to this address), with expiry. */
    private val resolved = object : LinkedHashMap<String, Pair<Long, Int>>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Int>>?) = size > 5000
    }

    fun stats(): DnsStats = synchronized(lock) { stats }

    fun resetStats() = synchronized(lock) { stats = DnsStats() }

    /** Call when the DNS setup or the VPN server changes. */
    fun clear() {
        synchronized(cache) { cache.clear() }
        synchronized(resolved) { resolved.clear() }
        resolver.reset()
    }

    /** The address your resolver gave for [domain], if any (still valid). */
    fun resolvedIp(domain: String): String? = synchronized(resolved) {
        resolved[domain]?.takeIf { it.first > System.currentTimeMillis() }?.second?.let { Ip.str(it) }
    }

    /**
     * Resolvers for a lookup. [viaTunnel]: plain DNS only ever travels inside
     * the tunnel; directly we only use encrypted transports for chosen providers.
     */
    fun upstreams(cfg: DnsConfig, viaTunnel: Boolean): List<Upstream> = when (cfg.mode) {
        DnsConfig.PRESET -> cfg.presetInfo?.let { p ->
            listOf(Upstream.Doh(p.doh)) + if (viaTunnel) listOf(Upstream.Udp(p.ip1), Upstream.Udp(p.ip2)) else emptyList()
        } ?: emptyList()
        DnsConfig.CUSTOM -> listOf(cfg.primary, cfg.secondary).filter { it.isNotBlank() }.map { Upstream.Udp(it.trim()) }
        DnsConfig.PRIVATE ->
            if (cfg.privateType == DnsConfig.DOT) listOf(Upstream.Dot(cfg.privateHost.trim()))
            else listOf(Upstream.Doh(cfg.privateHost.trim()))
        // Automatic: inside the tunnel use public resolvers; outside, your network's DNS.
        else -> if (viaTunnel) listOf(Upstream.Udp("1.1.1.1"), Upstream.Udp("8.8.8.8"))
            else systemDns().map { Upstream.Udp(it) } + Upstream.Udp("1.1.1.1")
    }

    /**
     * Answers one query. [proxied]: the name goes through the VPN. [fake] gives
     * the stand-in address for a name. Returns null to drop the query.
     */
    fun handle(query: ByteArray, q: Dns.Question, proxied: Boolean, cfg: DnsConfig, tunnelUp: Boolean, fake: (String) -> Int): ByteArray? {
        synchronized(lock) { stats = stats.copy(queries = stats.queries + 1) }

        // 1. Filtering (your allow list always wins)
        val cat = lists.match(q.name, cfg.filters, cfg.blockList, cfg.allowList)
        if (cat != null) {
            synchronized(lock) {
                val by = stats.blockedByCategory.toMutableMap()
                by[cat] = (by[cat] ?: 0) + 1
                stats = stats.copy(blocked = stats.blocked + 1, blockedByCategory = by)
            }
            return Dns.blocked(query, q)
        }

        if (proxied) {
            // VPN sites are reached over IPv4 through the VPN server: no IPv6 or HTTPS records.
            if (q.type == Dns.AAAA || q.type == Dns.HTTPS) return Dns.reply(query, q, null)
            // Automatic: the VPN server looks the name up itself; nothing leaves the phone.
            if (cfg.mode == DnsConfig.AUTO && q.type == Dns.A) return Dns.reply(query, q, fake(q.name))
            val viaTunnel = tunnelUp && cfg.leakProtection
            val ans = resolve(query, q, upstreams(cfg, viaTunnel), viaTunnel) ?: return Dns.servfail(query, q)
            if (q.type != Dns.A) return ans
            // A filtering provider (AdGuard, Quad9…) refused the name: pass its answer on.
            if (DnsClient.isBlocked(ans)) return ans
            Dns.answerIps(ans).firstOrNull()?.let { ip ->
                val ttl = (Dns.minTtl(ans) ?: 300).coerceIn(30, 3600)
                synchronized(resolved) { resolved[q.name] = (System.currentTimeMillis() + ttl * 1000L) to ip }
            }
            return Dns.reply(query, q, fake(q.name))
        }

        // Not a VPN site ("Only my sites" mode): your network's DNS, or your chosen resolver directly.
        return resolve(query, q, upstreams(cfg, false), false)
    }

    private fun resolve(query: ByteArray, q: Dns.Question, ups: List<Upstream>, viaTunnel: Boolean): ByteArray? {
        val key = "${q.name}|${q.type}"
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[key]?.takeIf { it.first > now }?.let { (_, r) -> return r.copyOf().also { it[0] = query[0]; it[1] = query[1] } }
        }
        if (ups.isEmpty()) return null
        return try {
            val a = resolver.query(query, ups, viaTunnel)
            val ttl = (Dns.minTtl(a.reply) ?: 60).coerceIn(30, 3600)
            synchronized(cache) { cache[key] = (now + ttl * 1000L) to a.reply.copyOf() }
            synchronized(lock) {
                stats = stats.copy(
                    viaTunnel = stats.viaTunnel + if (viaTunnel) 1 else 0,
                    direct = stats.direct + if (viaTunnel) 0 else 1,
                    encrypted = stats.encrypted + if (a.upstream.encrypted) 1 else 0,
                    lastResolver = a.upstream.label + if (viaTunnel) " · through VPN" else "",
                    lastMs = a.ms, lastError = "",
                )
            }
            a.reply
        } catch (e: Exception) {
            synchronized(lock) { stats = stats.copy(failed = stats.failed + 1, lastError = e.message ?: e.toString()) }
            null
        }
    }

    // ---------- diagnostics ----------

    data class TestItem(val name: String, val ok: Boolean, val detail: String, val ms: Int? = null)

    /** DNS Center → Test: real queries with the current setup, plus how lookups are routed. */
    fun test(cfg: DnsConfig, tunnelUp: Boolean, fullMode: Boolean, ipv6Blocked: Boolean): List<TestItem> {
        val out = ArrayList<TestItem>()
        val viaTunnel = tunnelUp && cfg.leakProtection
        val ups = upstreams(cfg, viaTunnel)
        out += TestItem("Resolver", ups.isNotEmpty(), cfg.label + if (viaTunnel) " · through VPN" else " · direct")

        fun ask(name: String, type: Int, list: List<Upstream>): Pair<DnsResolver.Answer?, String> = try {
            resolver.query(Dns.buildQuery(name, type), list, viaTunnel) to ""
        } catch (e: Exception) { null to (e.message ?: e.toString()) }

        val (a4, e4) = ask("example.com", Dns.A, ups)
        out += if (a4 != null) TestItem("IPv4 lookup (example.com)", Dns.answerIps(a4.reply).isNotEmpty(),
            Dns.answerIps(a4.reply).joinToString { Ip.str(it) }.ifEmpty { "no address" } + " via ${a4.upstream.label}", a4.ms)
        else TestItem("IPv4 lookup (example.com)", false, e4)

        val (a6, e6) = ask("google.com", Dns.AAAA, ups)
        out += if (a6 != null) TestItem("IPv6 lookup (google.com)", Dns.answerCount(a6.reply) > 0,
            "${Dns.answerCount(a6.reply)} record(s) via ${a6.upstream.label}", a6.ms)
        else TestItem("IPv6 lookup (google.com)", false, e6)

        // Each configured server on its own.
        for ((i, u) in ups.take(3).withIndex()) {
            val (r, err) = ask("cloudflare.com", Dns.A, listOf(u))
            out += TestItem(if (i == 0) "Primary server reachable" else "Server ${i + 1} reachable", r != null,
                if (r != null) u.label else "${u.label}: $err", r?.ms)
        }

        out += TestItem("Encrypted DNS", cfg.encrypted || cfg.mode == DnsConfig.AUTO,
            when {
                cfg.mode == DnsConfig.AUTO -> "VPN sites are looked up by the VPN server inside the tunnel"
                cfg.encrypted -> "Lookups use ${if (cfg.mode == DnsConfig.PRIVATE && cfg.privateType == DnsConfig.DOT) "DNS over TLS" else "DNS over HTTPS"}"
                viaTunnel -> "Plain DNS, but only inside the VPN tunnel"
                else -> "Plain DNS outside the tunnel: your network can see these lookups"
            })

        val s = stats()
        val leakOk = tunnelUp && fullMode && (cfg.mode == DnsConfig.AUTO || cfg.leakProtection)
        out += TestItem("DNS leak protection", leakOk,
            when {
                !tunnelUp -> "Connect the VPN to protect DNS"
                !fullMode -> "\"Only my sites\" mode: other sites use your normal DNS"
                cfg.mode != DnsConfig.AUTO && !cfg.leakProtection -> "Leak protection is off: lookups go to the resolver outside the tunnel"
                else -> "All app DNS goes through Tervyn; port 53 is captured and DNS-over-TLS bypass is blocked" +
                    if (s.direct > 0) " (${s.direct} lookup(s) sent outside the tunnel)" else ""
            })
        out += TestItem("IPv6 leak protection", tunnelUp && fullMode && ipv6Blocked,
            if (tunnelUp && fullMode && ipv6Blocked) "IPv6 traffic is captured and blocked" else "IPv6 traffic can bypass the VPN")

        if (cfg.filters.isNotEmpty()) {
            val loaded = cfg.filters.filter { lists.isLoaded(it) }
            out += TestItem("Filtering lists", loaded.size == cfg.filters.size,
                loaded.joinToString { id -> "${BlockLists.CATEGORIES.firstOrNull { it.id == id }?.name ?: id}: ${lists.info(id).count} domains" }
                    .ifEmpty { "Lists not downloaded yet" })
        }
        return out
    }
}
