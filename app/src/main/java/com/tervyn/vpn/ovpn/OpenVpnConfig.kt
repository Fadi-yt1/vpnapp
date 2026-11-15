package com.tervyn.vpn.ovpn

import java.io.IOException

/**
 * The parts of an OpenVPN client config (.ovpn) that Tervyn's OpenVPN client
 * uses: one remote, TCP or UDP, a CBC or GCM cipher, inline certificates.
 * VPN Gate configs are exactly this shape.
 */
data class OpenVpnConfig(
    val host: String,
    val port: Int,
    val tcp: Boolean,
    val cipher: String,            // e.g. AES-128-CBC, AES-256-GCM
    val auth: String,              // e.g. SHA1, SHA256 (CBC only)
    val ca: String,                // PEM
    val cert: String,              // PEM, may be empty
    val key: String,               // PEM, may be empty
    val compLzo: Boolean,
    val userPass: Boolean,         // auth-user-pass: send username / password
    val username: String = "",
    val password: String = "",
) {
    val remote get() = "$host:$port"

    companion object {
        val SUPPORTED_CIPHERS = setOf("AES-128-CBC", "AES-192-CBC", "AES-256-CBC", "AES-128-GCM", "AES-192-GCM", "AES-256-GCM")

        private fun block(text: String, tag: String): String =
            Regex("<$tag>\\s*([\\s\\S]*?)\\s*</$tag>").find(text)?.groupValues?.get(1)?.trim().orEmpty()

        /** Parses a config. Throws with a readable reason when it can't be used. */
        fun parse(text: String, username: String = "", password: String = ""): OpenVpnConfig {
            var host = ""
            var port = 1194
            var proto = "udp"
            var cipher = "AES-128-CBC"
            var auth = "SHA1"
            var comp = false
            var userPass = false
            var dataCiphers: String? = null
            val stripped = text.replace(Regex("<([a-z-]+)>[\\s\\S]*?</\\1>"), "")
            for (raw in stripped.lines()) {
                val line = raw.substringBefore('#').substringBefore(';').trim()
                if (line.isEmpty()) continue
                val p = line.split(Regex("\\s+"))
                when (p[0].lowercase()) {
                    "remote" -> if (host.isEmpty()) {
                        host = p.getOrElse(1) { "" }
                        p.getOrNull(2)?.toIntOrNull()?.let { port = it }
                        p.getOrNull(3)?.let { proto = it }
                    }
                    "port" -> p.getOrNull(1)?.toIntOrNull()?.let { port = it }
                    "proto" -> p.getOrNull(1)?.let { proto = it }
                    "cipher" -> p.getOrNull(1)?.let { cipher = it.uppercase() }
                    "data-ciphers", "ncp-ciphers" -> dataCiphers = p.getOrNull(1)
                    "auth" -> p.getOrNull(1)?.let { auth = it.uppercase() }
                    "comp-lzo" -> comp = true   // even "comp-lzo no" adds the 1-byte framing
                    "compress" -> comp = true
                    "auth-user-pass" -> userPass = true
                    "tls-auth", "tls-crypt", "tls-crypt-v2" -> throw IOException("This OpenVPN server needs ${p[0]}, which Tervyn doesn't support yet.")
                    "dev" -> if (p.getOrNull(1)?.startsWith("tap") == true) throw IOException("TAP (layer 2) OpenVPN servers aren't supported.")
                }
            }
            if (Regex("<(tls-auth|tls-crypt)>").containsMatchIn(text)) throw IOException("This OpenVPN server needs tls-auth / tls-crypt, which Tervyn doesn't support yet.")
            if (host.isEmpty()) throw IOException("The OpenVPN config has no \"remote\" line.")
            if (cipher !in SUPPORTED_CIPHERS) {
                cipher = dataCiphers?.split(':')?.map { it.uppercase() }?.firstOrNull { it in SUPPORTED_CIPHERS }
                    ?: throw IOException("Cipher $cipher isn't supported.")
            }
            val ca = block(text, "ca")
            if (ca.isEmpty()) throw IOException("The OpenVPN config has no <ca> certificate.")
            return OpenVpnConfig(
                host = host, port = port, tcp = proto.lowercase().startsWith("tcp"), cipher = cipher, auth = auth,
                ca = ca, cert = block(text, "cert"), key = block(text, "key"), compLzo = comp,
                userPass = userPass, username = username, password = password,
            )
        }
    }
}
