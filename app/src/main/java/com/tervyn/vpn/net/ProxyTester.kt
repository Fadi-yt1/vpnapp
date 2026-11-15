package com.tervyn.vpn.net

import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Tests servers the same way as the extension: each one must load a Cloudflare
 * page over HTTPS with a valid certificate. That proves it works for secure
 * sites, doesn't tamper with traffic, and tells us the server's real country.
 */
object ProxyTester {
    class Result(val ms: Int, val code: String, val ip: String, val mbps: Double? = null)

    /** Cloudflare sites that answer /cdn-cgi/trace with the visitor's IP and country. */
    private val TRACE_HOSTS = listOf(
        "one.one.one.one", "speed.cloudflare.com", "www.cloudflare.com", "cloudflare-quic.com",
        "blog.cloudflare.com", "developers.cloudflare.com", "radar.cloudflare.com", "workers.cloudflare.com",
    )
    private val counter = AtomicInteger()

    fun test(p: ProxyEndpoint, timeoutMs: Int = 8000): Result? {
        val host = TRACE_HOSTS[Math.floorMod(counter.getAndIncrement(), TRACE_HOSTS.size)]
        val start = System.nanoTime()
        return try {
            val body = httpsGet(ProxyDialer.open(p, host, 443, 6000), host, "/cdn-cgi/trace", timeoutMs)
            val ms = ((System.nanoTime() - start) / 1_000_000).toInt()
            val loc = Regex("(?m)^loc=([A-Z]{2})$").find(body)?.groupValues?.get(1) ?: return null
            val ip = Regex("(?m)^ip=(.+)$").find(body)?.groupValues?.get(1)?.trim() ?: ""
            Result(ms, if (loc == "XX") "" else loc, ip)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Tests many servers in parallel. [onResult] is called for every finished
     * test (null = not working). Stops early after [stopAfter] working servers
     * or [maxTimeMs].
     */
    fun <T : ProxyEndpoint> testMany(
        servers: List<T>,
        threads: Int = 64,
        stopAfter: Int = Int.MAX_VALUE,
        maxTimeMs: Long = Long.MAX_VALUE,
        probe: (ProxyEndpoint) -> Result? = { test(it) },
        onResult: (T, Result?) -> Unit,
    ) {
        if (servers.isEmpty()) return
        val pool = Executors.newFixedThreadPool(threads.coerceAtMost(servers.size))
        val stop = AtomicBoolean(false)
        val working = AtomicInteger()
        try {
            for (s in servers) {
                pool.execute {
                    if (stop.get()) return@execute
                    val r = probe(s)
                    if (stop.get() && r == null) return@execute
                    if (r != null && working.incrementAndGet() >= stopAfter) stop.set(true)
                    synchronized(this) { onResult(s, r) }
                }
            }
            pool.shutdown()
            if (!pool.awaitTermination(maxTimeMs.coerceAtMost(3_600_000), TimeUnit.MILLISECONDS)) stop.set(true)
        } finally {
            stop.set(true)
            pool.shutdownNow()
        }
    }

    private const val SPEED_HOST = "speed.cloudflare.com"

    /**
     * Download speed test through a server: fetches [bytes] from Cloudflare's
     * speed test over HTTPS and measures the transfer. ms = time to first byte.
     */
    fun speedTest(p: ProxyEndpoint, bytes: Int = 1_000_000, timeoutMs: Int = 15_000): Result? {
        val t0 = System.nanoTime()
        return try {
            ProxyDialer.open(p, SPEED_HOST, 443, 6000).use { raw ->
                raw.soTimeout = 8000
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(raw, SPEED_HOST, 443, true) as SSLSocket).use { ssl ->
                    ssl.soTimeout = 8000
                    ssl.startHandshake()
                    if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(SPEED_HOST, ssl.session)) return null
                    val req = "GET /__down?bytes=$bytes HTTP/1.0\r\nHost: $SPEED_HOST\r\nUser-Agent: TervynVPN/1.0\r\nConnection: close\r\n\r\n"
                    ssl.outputStream.apply { write(req.toByteArray()); flush() }
                    val inp = ssl.inputStream
                    val buf = ByteArray(32 * 1024)
                    val head = StringBuilder()
                    var body = 0L
                    var tFirst = 0L
                    val deadline = t0 + timeoutMs * 1_000_000L
                    while (System.nanoTime() < deadline) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        if (tFirst == 0L) {
                            // Still in the headers: find where the body starts.
                            head.append(String(buf, 0, n, Charsets.ISO_8859_1))
                            val end = head.indexOf("\r\n\r\n")
                            if (end < 0) continue
                            val status = head.substring(0, head.indexOf("\r\n")).split(' ').getOrNull(1)?.toIntOrNull()
                            if (status != 200) return null
                            tFirst = System.nanoTime()
                            body = (head.length - end - 4).toLong()
                        } else {
                            body += n
                        }
                        if (body >= bytes) break
                    }
                    val secs = (System.nanoTime() - tFirst) / 1e9
                    if (tFirst == 0L || body < 100_000 || secs <= 0) return null
                    Result(((tFirst - t0) / 1_000_000).toInt(), "", "", mbps = body * 8 / secs / 1e6)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Sends a simple HTTPS GET over an already-open tunnel and returns the body. */
    fun httpsGet(tunnel: Socket, host: String, path: String, timeoutMs: Int = 8000): String {
        tunnel.use { raw ->
            raw.soTimeout = timeoutMs
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket(raw, host, 443, true) as SSLSocket).use { ssl ->
                ssl.soTimeout = timeoutMs
                ssl.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                    throw javax.net.ssl.SSLPeerUnverifiedException("Certificate does not match $host")
                }
                val req = "GET $path HTTP/1.0\r\nHost: $host\r\nUser-Agent: TervynVPN/1.0\r\nAccept: */*\r\nConnection: close\r\n\r\n"
                ssl.outputStream.apply { write(req.toByteArray()); flush() }
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                val inp = ssl.inputStream
                while (buf.size() < 256 * 1024) {
                    val n = inp.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
                val text = buf.toString("UTF-8")
                val status = text.substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                if (status !in 200..299) throw java.io.IOException("HTTP $status")
                return text.substringAfter("\r\n\r\n")
            }
        }
    }
}
