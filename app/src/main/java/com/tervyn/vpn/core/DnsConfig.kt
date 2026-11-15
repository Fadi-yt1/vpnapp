package com.tervyn.vpn.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * DNS settings (DNS Center).
 *
 * - [AUTO]: sites that go through the VPN are looked up by the VPN server itself
 *   (nothing leaves your phone); other lookups use your network's DNS.
 * - [PRESET]: a known provider over DNS-over-HTTPS.
 * - [CUSTOM]: your own DNS servers (IPv4 / IPv6).
 * - [PRIVATE]: your own encrypted resolver, DNS-over-HTTPS URL or DNS-over-TLS hostname.
 */
data class DnsConfig(
    val mode: String = AUTO,
    val preset: String = "cloudflare",
    val primary: String = "",
    val secondary: String = "",
    val privateType: String = DOH,      // DOH or DOT
    val privateHost: String = "",       // https://… for DoH, a hostname for DoT
    val leakProtection: Boolean = true, // send lookups through the VPN tunnel, never in the clear outside it
    val filters: Set<String> = emptySet(),
    val blockList: List<String> = emptyList(),
    val allowList: List<String> = emptyList(),
) {
    val presetInfo: Preset? get() = PRESETS.firstOrNull { it.id == preset }

    /** Short name of the resolver, e.g. "Cloudflare (DoH)". */
    val label: String
        get() = when (mode) {
            PRESET -> (presetInfo?.name ?: preset) + " (DoH)"
            CUSTOM -> listOf(primary, secondary).filter { it.isNotBlank() }.joinToString(", ").ifEmpty { "Custom (not set)" }
            PRIVATE -> (if (privateType == DOT) "DoT " else "DoH ") + privateHost
            else -> "Automatic"
        }

    /** Server addresses shown in the DNS Center. */
    val addresses: String
        get() = when (mode) {
            PRESET -> presetInfo?.let { "${it.ip1}, ${it.ip2} · ${it.doh}" } ?: ""
            CUSTOM -> listOf(primary, secondary).filter { it.isNotBlank() }.joinToString(", ")
            PRIVATE -> privateHost
            else -> "VPN server for VPN sites, your network for others"
        }

    val encrypted: Boolean get() = mode == PRESET || mode == PRIVATE

    /** null when valid, else a message for the user. */
    fun validate(): String? = when (mode) {
        CUSTOM -> when {
            primary.isBlank() -> "Enter a primary DNS server."
            !isIp(primary) -> "\"$primary\" isn't a valid IPv4 or IPv6 address."
            secondary.isNotBlank() && !isIp(secondary) -> "\"$secondary\" isn't a valid IPv4 or IPv6 address."
            else -> null
        }
        PRIVATE -> when {
            privateType == DOH && !Regex("^https://[A-Za-z0-9.-]+(:\\d+)?(/\\S*)?$").matches(privateHost) ->
                "Enter a DNS-over-HTTPS address like https://dns.example.com/dns-query"
            privateType == DOT && !isHostname(privateHost) -> "Enter a DNS-over-TLS hostname like dns.example.com"
            else -> null
        }
        PRESET -> if (presetInfo == null) "Choose a DNS provider." else null
        else -> null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode); put("preset", preset); put("primary", primary); put("secondary", secondary)
        put("privateType", privateType); put("privateHost", privateHost); put("leakProtection", leakProtection)
        put("filters", JSONArray(filters.toList())); put("blockList", JSONArray(blockList)); put("allowList", JSONArray(allowList))
    }

    data class Preset(val id: String, val name: String, val about: String, val ip1: String, val ip2: String, val doh: String, val dot: String)

    companion object {
        const val AUTO = "auto"
        const val PRESET = "preset"
        const val CUSTOM = "custom"
        const val PRIVATE = "private"
        const val DOH = "doh"
        const val DOT = "dot"

        val PRESETS = listOf(
            Preset("cloudflare", "Cloudflare", "Fast and private", "1.1.1.1", "1.0.0.1", "https://cloudflare-dns.com/dns-query", "one.one.one.one"),
            Preset("google", "Google", "Reliable everywhere", "8.8.8.8", "8.8.4.4", "https://dns.google/dns-query", "dns.google"),
            Preset("quad9", "Quad9", "Blocks known malware sites", "9.9.9.9", "149.112.112.112", "https://dns.quad9.net/dns-query", "dns.quad9.net"),
            Preset("adguard", "AdGuard", "Blocks ads and trackers", "94.140.14.14", "94.140.15.15", "https://dns.adguard-dns.com/dns-query", "dns.adguard-dns.com"),
            Preset("cloudflare-family", "Cloudflare Family", "Blocks malware and adult sites", "1.1.1.3", "1.0.0.3", "https://family.cloudflare-dns.com/dns-query", "family.cloudflare-dns.com"),
        )

        private val IPV4 = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")
        private val IPV6 = Regex("^[0-9A-Fa-f:.]+$")

        fun isIpv4(s: String) = IPV4.matches(s.trim())

        /** IPv4, or IPv6 literal (checked without any network lookup). */
        fun isIp(s: String): Boolean {
            val t = s.trim()
            if (isIpv4(t)) return true
            if (!t.contains(':') || !IPV6.matches(t) || t.count { it == ':' } > 7) return false
            return runCatching { java.net.InetAddress.getByName(t) is java.net.Inet6Address }.getOrDefault(false)
        }

        fun isHostname(s: String) = Regex("^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$").matches(s.trim())

        fun fromJson(o: JSONObject?): DnsConfig {
            if (o == null) return DnsConfig()
            fun list(k: String) = o.optJSONArray(k)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            return DnsConfig(
                mode = o.optString("mode", AUTO), preset = o.optString("preset", "cloudflare"),
                primary = o.optString("primary"), secondary = o.optString("secondary"),
                privateType = o.optString("privateType", DOH), privateHost = o.optString("privateHost"),
                leakProtection = o.optBoolean("leakProtection", true),
                filters = list("filters").toSet(), blockList = list("blockList"), allowList = list("allowList"),
            )
        }

        /** Settings from before the DNS Center (dns = provider id / "custom" / "auto"). */
        fun migrate(old: String, custom: String): DnsConfig = when {
            old == "auto" || old.isBlank() -> DnsConfig()
            old == "custom" -> {
                val parts = custom.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }
                val doh = parts.firstOrNull { it.startsWith("https://") }
                if (doh != null) DnsConfig(mode = PRIVATE, privateType = DOH, privateHost = doh)
                else DnsConfig(mode = CUSTOM, primary = parts.getOrElse(0) { "" }, secondary = parts.getOrElse(1) { "" })
            }
            PRESETS.any { it.id == old } -> DnsConfig(mode = PRESET, preset = old)
            else -> DnsConfig()
        }
    }
}

/** A saved, named DNS setup. */
data class DnsProfile(val id: String, val name: String, val config: DnsConfig) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("config", config.toJson())

    companion object {
        fun fromJson(o: JSONObject) = DnsProfile(o.getString("id"), o.optString("name"), DnsConfig.fromJson(o.optJSONObject("config")))
    }
}

/** DNS activity since connecting: genuine counters from the VPN's DNS handling. */
data class DnsStats(
    val queries: Long = 0,
    val blocked: Long = 0,
    val viaTunnel: Long = 0,       // lookups sent to the resolver through the VPN server
    val direct: Long = 0,          // lookups sent outside the tunnel (sites that don't use the VPN)
    val encrypted: Long = 0,       // lookups sent over DoH / DoT
    val failed: Long = 0,
    val lastResolver: String = "",
    val lastMs: Int = 0,
    val lastError: String = "",
    val blockedByCategory: Map<String, Long> = emptyMap(),
)
