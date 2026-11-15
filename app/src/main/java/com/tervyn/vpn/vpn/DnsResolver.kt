package com.tervyn.vpn.vpn

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Sends DNS queries to a resolver over UDP, TCP, DNS-over-TLS or
 * DNS-over-HTTPS, either directly or through the VPN tunnel ([tunnelDial]),
 * keeping connections open between queries so lookups stay fast.
 */
class DnsResolver(
    private val tls: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val verifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
) {
    sealed class Upstream {
        abstract val label: String
        abstract val encrypted: Boolean

        data class Udp(val ip: String) : Upstream() {
            override val label get() = ip
            override val encrypted get() = false
        }
        data class Tcp(val ip: String) : Upstream() {
            override val label get() = "$ip (TCP)"
            override val encrypted get() = false
        }
        data class Dot(val host: String, val port: Int = 853) : Upstream() {
            override val label get() = "$host (DoT)"
            override val encrypted get() = true
        }
        data class Doh(val url: String) : Upstream() {
            override val label get() = "${runCatching { URI(url).host }.getOrDefault(url)} (DoH)"
            override val encrypted get() = true
        }
    }

    class Answer(val reply: ByteArray, val upstream: Upstream, val viaTunnel: Boolean, val ms: Int)

    /** Opens a TCP connection through the VPN to host:port (throws if not possible). */
    var tunnelDial: ((String, Int) -> Socket)? = null

    private class Conn(val socket: Socket, val input: InputStream, val output: OutputStream) {
        var lastUsed = System.currentTimeMillis()
        fun close() = runCatching { socket.close() }
    }

    private val pools = HashMap<String, ArrayDeque<Conn>>()

    /** Drops kept connections (e.g. when the VPN server changes or disconnects). */
    fun reset() {
        val all = synchronized(pools) { pools.values.flatten().also { pools.clear() } }
        all.forEach { it.close() }
    }

    /**
     * Asks each upstream in turn. [viaTunnel] sends the query through the VPN
     * (UDP becomes TCP, since the tunnel carries TCP); otherwise it goes directly.
     * Throws with the last error if none answered.
     */
    fun query(q: ByteArray, upstreams: List<Upstream>, viaTunnel: Boolean, timeoutMs: Int = 6000): Answer {
        var last: Exception? = null
        for (u in upstreams) {
            val t0 = System.nanoTime()
            try {
                val r = when (u) {
                    is Upstream.Udp -> if (viaTunnel) stream(Upstream.Tcp(u.ip), q, true, timeoutMs) else udp(u.ip, q, timeoutMs)
                    else -> stream(u, q, viaTunnel, timeoutMs)
                }
                if (r.size < 12) throw IOException("Short DNS answer")
                r[0] = q[0]; r[1] = q[1]
                return Answer(r, u, viaTunnel, ((System.nanoTime() - t0) / 1_000_000).toInt())
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("No DNS server configured")
    }

    private fun udp(ip: String, q: ByteArray, timeoutMs: Int): ByteArray {
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 53))
            val buf = ByteArray(4096)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            return buf.copyOf(p.length)
        }
    }

    /** TCP-based query on a kept connection; one retry on a fresh connection if the kept one went stale. */
    private fun stream(u: Upstream, q: ByteArray, viaTunnel: Boolean, timeoutMs: Int): ByteArray {
        val key = u.label + if (viaTunnel) "|tunnel" else "|direct"
        for (attempt in 0..1) {
            val conn = (if (attempt == 0) take(key) else null) ?: open(u, viaTunnel, timeoutMs)
            try {
                conn.socket.soTimeout = timeoutMs
                val (reply, keep) = when (u) {
                    is Upstream.Doh -> dohExchange(conn, u, q)
                    else -> framedExchange(conn, q) to true
                }
                if (keep) give(key, conn) else conn.close()
                return reply
            } catch (e: IOException) {
                conn.close()
                if (attempt == 1) throw e
            }
        }
        throw IOException("unreachable")
    }

    private fun take(key: String): Conn? = synchronized(pools) {
        val q = pools[key] ?: return null
        while (q.isNotEmpty()) {
            val c = q.removeFirst()
            if (System.currentTimeMillis() - c.lastUsed < 45_000 && !c.socket.isClosed) return c
            c.close()
        }
        null
    }

    private fun give(key: String, c: Conn) {
        c.lastUsed = System.currentTimeMillis()
        val extra = synchronized(pools) {
            val q = pools.getOrPut(key) { ArrayDeque() }
            if (q.size < 4) { q.addLast(c); null } else c
        }
        extra?.close()
    }

    private fun open(u: Upstream, viaTunnel: Boolean, timeoutMs: Int): Conn {
        val (host, port, useTls) = when (u) {
            is Upstream.Tcp -> Triple(u.ip, 53, false)
            is Upstream.Udp -> Triple(u.ip, 53, false)
            is Upstream.Dot -> Triple(u.host, u.port, true)
            is Upstream.Doh -> {
                val uri = URI(u.url)
                Triple(uri.host, if (uri.port > 0) uri.port else 443, true)
            }
        }
        val raw = if (viaTunnel) {
            (tunnelDial ?: throw IOException("VPN is not connected"))(host, port)
        } else {
            Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(host, port), timeoutMs) }
        }
        try {
            raw.soTimeout = timeoutMs
            if (!useTls) return Conn(raw, BufferedInputStream(raw.getInputStream()), raw.getOutputStream())
            val ssl = tls.createSocket(raw, host, port, true) as SSLSocket
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            if (!verifier.verify(host, ssl.session)) {
                ssl.close()
                throw IOException("Certificate does not match $host")
            }
            return Conn(ssl, BufferedInputStream(ssl.inputStream), ssl.outputStream)
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    /** DNS over TCP / TLS: 2-byte length, then the message. */
    private fun framedExchange(c: Conn, q: ByteArray): ByteArray {
        c.output.write(byteArrayOf((q.size ushr 8).toByte(), q.size.toByte()) + q)
        c.output.flush()
        val len = (readByte(c.input) shl 8) or readByte(c.input)
        return readN(c.input, len)
    }

    /** DNS over HTTPS (RFC 8484) with HTTP/1.1 keep-alive. Returns (reply, keep connection). */
    private fun dohExchange(c: Conn, u: Upstream.Doh, q: ByteArray): Pair<ByteArray, Boolean> {
        val uri = URI(u.url)
        val path = (uri.rawPath ?: "").ifEmpty { "/dns-query" } + (uri.rawQuery?.let { "?$it" } ?: "")
        val head = "POST $path HTTP/1.1\r\nHost: ${uri.host}\r\nUser-Agent: TervynVPN\r\n" +
            "Content-Type: application/dns-message\r\nAccept: application/dns-message\r\n" +
            "Content-Length: ${q.size}\r\nConnection: keep-alive\r\n\r\n"
        c.output.write(head.toByteArray(Charsets.ISO_8859_1) + q)
        c.output.flush()
        val status = readLine(c.input)
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Bad DoH reply")
        var length = -1
        var chunked = false
        var close = false
        while (true) {
            val line = readLine(c.input)
            if (line.isEmpty()) break
            val k = line.substringBefore(':').trim().lowercase()
            val v = line.substringAfter(':', "").trim().lowercase()
            when (k) {
                "content-length" -> length = v.toIntOrNull() ?: -1
                "transfer-encoding" -> chunked = v.contains("chunked")
                "connection" -> close = v == "close"
            }
        }
        val body = when {
            chunked -> {
                val out = ByteArrayOutputStream()
                while (true) {
                    val size = readLine(c.input).substringBefore(';').trim().toInt(16)
                    if (size == 0) { readLine(c.input); break }
                    out.write(readN(c.input, size))
                    readLine(c.input)
                }
                out.toByteArray()
            }
            length >= 0 -> readN(c.input, length)
            else -> { close = true; c.input.readBytes() }
        }
        if (code != 200) throw IOException("DoH server answered HTTP $code")
        return body to !close
    }

    private fun readByte(i: InputStream): Int {
        val b = i.read()
        if (b < 0) throw IOException("Connection closed")
        return b
    }

    private fun readN(i: InputStream, n: Int): ByteArray {
        if (n < 0 || n > 65535) throw IOException("Bad length $n")
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = i.read(b, got, n - got)
            if (r < 0) throw IOException("Connection closed")
            got += r
        }
        return b
    }

    private fun readLine(i: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = readByte(i)
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) throw IOException("Header too long")
        }
    }
}
