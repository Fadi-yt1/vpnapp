package com.tervyn.vpn.vpn

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Decides where each connection goes. Implemented by the app (and by tests). */
interface Router {
    /** true = all traffic goes through the VPN, false = only listed sites. */
    val fullMode: Boolean
    fun shouldProxy(domain: String): Boolean
    /** Connection to host:port through the VPN server(s). */
    fun connectProxied(host: String, port: Int): Socket
    /** Normal connection (not through the VPN). */
    fun connectDirect(host: String, port: Int): Socket
    /** Your normal DNS servers, used for sites that don't go through the VPN. */
    fun upstreamDns(): List<InetAddress>
}

/**
 * Reads IP packets from the VPN interface, terminates each TCP connection
 * locally and relays its data through the proxy. DNS is answered locally.
 * Other UDP (e.g. QUIC) is dropped, so apps fall back to TCP.
 */
class TunEngine(
    private val input: InputStream,
    private val output: OutputStream,
    val router: Router,
    private val fakeDns: FakeDns,
    private val log: (String) -> Unit = {},
) {
    data class Key(val src: Int, val sport: Int, val dst: Int, val dport: Int)

    private val conns = ConcurrentHashMap<Key, TcpConn>()
    val pool = Executors.newCachedThreadPool { r -> Thread(r, "tervyn-io").apply { isDaemon = true } }
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "tervyn-timer").apply { isDaemon = true }
    }

    @Volatile
    var running = false
        private set

    val connectionCount get() = conns.size

    fun start() {
        running = true
        Thread({ loop() }, "tervyn-tun").apply { isDaemon = true }.start()
        timer.scheduleAtFixedRate({ tick() }, 250, 250, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        running = false
        for (c in conns.values.toList()) c.shutdown()
        conns.clear()
        timer.shutdownNow()
        pool.shutdownNow()
    }

    fun write(packet: ByteArray) {
        if (!running) return
        try {
            synchronized(output) { output.write(packet) }
        } catch (_: IOException) {
        }
    }

    internal fun remove(key: Key, conn: TcpConn) {
        conns.remove(key, conn)
    }

    private fun loop() {
        val buf = ByteArray(65535)
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                try {
                    handle(buf, n)
                } catch (e: Exception) {
                    log("packet error: $e")
                }
            }
        } catch (e: IOException) {
            if (running) log("tun closed: $e")
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        for (c in conns.values) {
            try { c.tick(now) } catch (_: Exception) {}
        }
    }

    private fun handle(b: ByteArray, n: Int) {
        if (n < 20 || (b[0].toInt() ushr 4) != 4) return        // IPv6 is dropped (DNS never hands it out)
        val ihl = (b[0].toInt() and 0x0f) * 4
        val total = minOf(Ip.u16(b, 2), n)
        if (ihl < 20 || total < ihl + 8) return
        if (Ip.u16(b, 6) and 0x3fff != 0) return                 // fragments: not used by TCP with MSS
        val proto = Ip.u8(b, 9)
        val src = Ip.i32(b, 12)
        val dst = Ip.i32(b, 16)
        when (proto) {
            Ip.UDP -> {
                val sport = Ip.u16(b, ihl)
                val dport = Ip.u16(b, ihl + 2)
                val len = minOf(Ip.u16(b, ihl + 4) - 8, total - ihl - 8)
                if (dport == 53 && len > 0) {
                    onDns(src, sport, dst, dport, b.copyOfRange(ihl + 8, ihl + 8 + len))
                }
            }
            Ip.TCP -> onTcp(b, ihl, total, src, dst)
        }
    }

    // ---------- DNS ----------
    private fun onDns(src: Int, sport: Int, dst: Int, dport: Int, payload: ByteArray) {
        val q = Dns.parse(payload) ?: return
        val reply = { data: ByteArray -> write(Ip.udp(dst, src, dport, sport, data)) }
        val proxied = q.name.isNotEmpty() && router.shouldProxy(q.name)
        when {
            proxied && q.type == Dns.A -> reply(Dns.reply(payload, q, fakeDns.ipFor(q.name)))
            // No IPv6 / HTTPS records for VPN sites, so apps use the stand-in IPv4 address.
            proxied && (q.type == Dns.AAAA || q.type == Dns.HTTPS) -> reply(Dns.reply(payload, q, null))
            else -> pool.execute { forwardDns(payload, reply) }
        }
    }

    private fun forwardDns(payload: ByteArray, reply: (ByteArray) -> Unit) {
        for (server in router.upstreamDns()) {
            try {
                DatagramSocket().use { s ->
                    s.soTimeout = 3000
                    s.send(DatagramPacket(payload, payload.size, server, 53))
                    val buf = ByteArray(4096)
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    reply(buf.copyOf(p.length))
                    return
                }
            } catch (_: Exception) {
            }
        }
    }

    // ---------- TCP ----------
    private fun onTcp(b: ByteArray, ihl: Int, total: Int, src: Int, dst: Int) {
        if (total < ihl + 20) return
        val t = ihl
        val sport = Ip.u16(b, t)
        val dport = Ip.u16(b, t + 2)
        val seq = Ip.i32(b, t + 4)
        val ack = Ip.i32(b, t + 8)
        val doff = (Ip.u8(b, t + 12) ushr 4) * 4
        val flags = Ip.u8(b, t + 13)
        val window = Ip.u16(b, t + 14)
        if (doff < 20 || t + doff > total) return
        val dataOff = t + doff
        val len = total - dataOff
        val key = Key(src, sport, dst, dport)

        val conn = conns[key]
        if (conn != null) {
            conn.onPacket(flags, seq, ack, window, b, dataOff, len)
            return
        }
        if (flags and Ip.RST != 0) return
        if (flags and Ip.SYN == 0 || flags and Ip.ACK != 0) {
            // Unknown connection (e.g. from before a reconnect): reset it.
            resetUnknown(src, sport, dst, dport, seq, ack, flags, len)
            return
        }

        // New connection: work out where it should go.
        var mss = 536
        var o = t + 20
        while (o < dataOff) {
            val kind = Ip.u8(b, o)
            if (kind == 0) break
            if (kind == 1) { o++; continue }
            if (o + 1 >= dataOff) break
            val olen = Ip.u8(b, o + 1)
            if (olen < 2) break
            if (kind == 2 && olen == 4 && o + 3 < dataOff) mss = Ip.u16(b, o + 2)
            o += olen
        }

        val target: String
        val proxied: Boolean
        if (dst == FakeDns.DNS_IP) {
            resetUnknown(src, sport, dst, dport, seq, ack, flags, len)   // e.g. DNS-over-TLS probes
            return
        } else if (FakeDns.isFake(dst)) {
            val domain = fakeDns.domainFor(dst)
            if (domain == null) { resetUnknown(src, sport, dst, dport, seq, ack, flags, len); return }
            target = domain
            proxied = router.shouldProxy(domain)
        } else {
            target = Ip.str(dst)
            proxied = router.fullMode && !Ip.isPrivate(dst)
        }

        val c = TcpConn(this, key, seq, window, mss, target, dport, proxied)
        conns[key] = c
        c.start()
    }

    private fun resetUnknown(src: Int, sport: Int, dst: Int, dport: Int, seq: Int, ack: Int, flags: Int, len: Int) {
        if (flags and Ip.ACK != 0) {
            write(Ip.tcp(dst, src, dport, sport, ack, 0, Ip.RST, 0))
        } else {
            var segLen = len
            if (flags and Ip.SYN != 0) segLen++
            if (flags and Ip.FIN != 0) segLen++
            write(Ip.tcp(dst, src, dport, sport, 0, seq + segLen, Ip.RST or Ip.ACK, 0))
        }
    }
}
