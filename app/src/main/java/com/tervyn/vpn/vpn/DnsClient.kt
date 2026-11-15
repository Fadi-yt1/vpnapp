package com.tervyn.vpn.vpn

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** Sends raw DNS queries over UDP or, encrypted, over HTTPS (RFC 8484). */
object DnsClient {
    /** One DNS server: a DNS-over-HTTPS address or a plain IP address (port 53). */
    data class Upstream(val doh: String? = null, val ip: InetAddress? = null)

    /** Asks each upstream in turn and returns the first answer, or null if none answers. */
    fun query(q: ByteArray, upstreams: List<Upstream>, timeoutMs: Int = 4000): ByteArray? {
        for (u in upstreams) {
            try {
                return if (u.doh != null) doh(u.doh, q, timeoutMs) else udp(u.ip ?: continue, q, timeoutMs)
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun udp(server: InetAddress, q: ByteArray, timeoutMs: Int): ByteArray {
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            s.send(DatagramPacket(q, q.size, server, 53))
            val buf = ByteArray(4096)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            return buf.copyOf(p.length)
        }
    }

    fun doh(url: String, q: ByteArray, timeoutMs: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.requestMethod = "POST"
            c.doOutput = true
            c.useCaches = false
            c.setRequestProperty("Content-Type", "application/dns-message")
            c.setRequestProperty("Accept", "application/dns-message")
            c.outputStream.use { it.write(q) }
            if (c.responseCode != 200) throw java.io.IOException("DoH HTTP ${c.responseCode}")
            val out = ByteArrayOutputStream()
            c.inputStream.use { it.copyTo(out) }
            val r = out.toByteArray()
            if (r.size < 12) throw java.io.IOException("Short DNS answer")
            // Keep the app's query id (some servers answer with id 0).
            r[0] = q[0]; r[1] = q[1]
            return r
        } finally {
            c.disconnect()
        }
    }

    /**
     * True when a filtering DNS (AdGuard, Quad9…) refused the name: "no such
     * name", or A records that all point to 0.0.0.0 or 127.x.
     */
    fun isBlocked(reply: ByteArray): Boolean {
        if (reply.size < 12) return false
        val rcode = reply[3].toInt() and 0x0f
        if (rcode == 3) return true
        if (rcode != 0) return false
        val ips = Dns.answerIps(reply)
        return ips.isNotEmpty() && ips.all { it == 0 || (it ushr 24) == 127 }
    }
}
