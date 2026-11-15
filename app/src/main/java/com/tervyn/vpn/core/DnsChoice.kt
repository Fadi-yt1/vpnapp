package com.tervyn.vpn.core

import com.tervyn.vpn.vpn.DnsClient
import java.net.InetAddress

/**
 * The separate DNS setting. "Automatic" keeps the default: sites that go
 * through the VPN are looked up by the VPN server, everything else by your
 * network's DNS. The other choices send lookups encrypted (DNS over HTTPS)
 * to that provider, and filtering ones (AdGuard, Cloudflare Family) also
 * block what they block, for apps going through the VPN too.
 */
object DnsChoice {
    const val AUTO = "auto"
    const val CUSTOM = "custom"

    data class Provider(val id: String, val name: String, val about: String, val doh: String, val ip: String, val filters: Boolean = false)

    val PROVIDERS = listOf(
        Provider("cloudflare", "Cloudflare 1.1.1.1", "Fast and private", "https://cloudflare-dns.com/dns-query", "1.1.1.1"),
        Provider("google", "Google DNS", "Reliable everywhere", "https://dns.google/dns-query", "8.8.8.8"),
        Provider("quad9", "Quad9", "Blocks malware sites", "https://dns.quad9.net/dns-query", "9.9.9.9", filters = true),
        Provider("adguard", "AdGuard DNS", "Blocks ads and trackers", "https://dns.adguard-dns.com/dns-query", "94.140.14.14", filters = true),
        Provider("cloudflare-family", "Cloudflare Family", "Blocks malware and adult sites", "https://family.cloudflare-dns.com/dns-query", "1.1.1.3", filters = true),
    )

    fun label(prefs: Prefs): String = when (prefs.dns) {
        AUTO -> "Automatic"
        CUSTOM -> "Custom: ${prefs.customDns.ifBlank { "not set" }}"
        else -> PROVIDERS.firstOrNull { it.id == prefs.dns }?.name ?: "Automatic"
    }

    /** Where to send lookups for this setting (first one that answers wins). [system] = your network's DNS. */
    fun upstreams(prefs: Prefs, system: List<InetAddress>): List<DnsClient.Upstream> {
        val fallback = listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("8.8.8.8")).map { DnsClient.Upstream(ip = it) }
        return when (prefs.dns) {
            AUTO -> system.map { DnsClient.Upstream(ip = it) } + fallback
            CUSTOM -> parseCustom(prefs.customDns).ifEmpty { fallback }
            else -> PROVIDERS.firstOrNull { it.id == prefs.dns }?.let {
                listOf(DnsClient.Upstream(doh = it.doh), DnsClient.Upstream(ip = InetAddress.getByName(it.ip)))
            } ?: fallback
        }
    }

    /** "https://…/dns-query" (DNS over HTTPS) or IP addresses separated by commas or spaces. */
    fun parseCustom(text: String): List<DnsClient.Upstream> = text.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }.mapNotNull { t ->
        when {
            t.startsWith("https://", ignoreCase = true) -> DnsClient.Upstream(doh = t)
            Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(t) -> runCatching { DnsClient.Upstream(ip = InetAddress.getByName(t)) }.getOrNull()
            else -> null
        }
    }

    /** Checking names before handing out stand-in addresses only matters when a provider is chosen. */
    fun checksNames(prefs: Prefs) = prefs.dns != AUTO
}
