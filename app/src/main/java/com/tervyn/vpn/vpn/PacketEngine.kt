package com.tervyn.vpn.vpn

import com.tervyn.vpn.ovpn.OpenVpnClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** What the VPN interface's packets are handed to. */
interface TunnelEngine {
    val bytesDown: AtomicLong
    val bytesUp: AtomicLong
    fun start()
    fun stop()
}

/** How a DNS query from an app is answered in packet (OpenVPN) mode. */
sealed class DnsAction {
    class Answer(val reply: ByteArray) : DnsAction()
    class Forward(val resolver: Int) : DnsAction()   // send it through the tunnel to this resolver
    object Drop : DnsAction()
}

/**
 * Full-tunnel engine for OpenVPN servers: every IPv4 packet from the VPN
 * interface is encrypted into the OpenVPN tunnel, and packets coming back are
 * written to the interface. The interface keeps Tervyn's own address
 * (198.18.0.2), translated to the address the server gave us, so switching
 * between server types never needs a new interface. DNS sent to Tervyn's DNS
 * address is filtered and answered locally or forwarded through the tunnel.
 * IPv6 is dropped (it would otherwise bypass the tunnel).
 */
class PacketEngine(
    private val input: InputStream,
    private val output: OutputStream,
    private val client: OpenVpnClient,
    private val dns: (ByteArray) -> DnsAction,
    private val log: (String) -> Unit = {},
) : TunnelEngine {
    override val bytesDown = AtomicLong()
    override val bytesUp = AtomicLong()
    @Volatile private var running = false
    private val vpnIp = Ip.parse(client.pushed?.ip ?: "0.0.0.0")
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "tervyn-dns").apply { isDaemon = true } }

    /** Forwarded DNS queries: app port → (resolver, time), so the answers can be returned from Tervyn's DNS address. */
    private val forwarded = ConcurrentHashMap<Int, Pair<Int, Long>>()

    override fun start() {
        running = true
        client.onPacket = { b, off, len -> fromTunnel(b, off, len) }
        Thread({ loop() }, "tervyn-tun-packets").apply { isDaemon = true }.start()
    }

    override fun stop() {
        running = false
        client.onPacket = null
        pool.shutdownNow()
    }

    private fun writeTun(p: ByteArray, off: Int, len: Int) {
        if (!running) return
        try {
            synchronized(output) { output.write(p, off, len) }
            bytesDown.addAndGet(len.toLong())
        } catch (_: IOException) {
        }
    }

    private fun loop() {
        val buf = ByteArray(65535)
        try {
            while (running) {
                val n = input.read(buf)
                if (n <= 0) { if (n < 0) break; continue }
                bytesUp.addAndGet(n.toLong())
                try { toTunnel(buf, n) } catch (e: Exception) { log("packet: $e") }
            }
        } catch (e: IOException) {
            if (running) log("tun closed: $e")
        }
    }

    private fun toTunnel(b: ByteArray, n: Int) {
        if (n < 20 || (b[0].toInt() ushr 4) != 4) return          // IPv4 only
        val ihl = (b[0].toInt() and 0x0f) * 4
        val proto = Ip.u8(b, 9)
        val dst = Ip.i32(b, 16)
        if (dst == FakeDns.DNS_IP) {
            if (proto == Ip.UDP && n >= ihl + 8 && Ip.u16(b, ihl + 2) == 53) handleDns(b.copyOf(n), ihl)
            return
        }
        if (client.closed) return                                  // kill switch: nothing leaves
        rewrite(b, 12, vpnIp)
        client.send(b, 0, n)
    }

    private fun handleDns(p: ByteArray, ihl: Int) {
        val src = Ip.i32(p, 12)
        val sport = Ip.u16(p, ihl)
        val query = p.copyOfRange(ihl + 8, p.size)
        pool.execute {
            when (val a = try { dns(query) } catch (e: Exception) { DnsAction.Drop }) {
                is DnsAction.Answer -> {
                    val r = Ip.udp(FakeDns.DNS_IP, src, 53, sport, a.reply)
                    writeTun(r, 0, r.size)
                }
                is DnsAction.Forward -> {
                    if (client.closed) return@execute
                    forwarded[sport] = a.resolver to System.currentTimeMillis()
                    if (forwarded.size > 2000) {
                        val old = System.currentTimeMillis() - 30_000
                        forwarded.entries.removeIf { it.value.second < old }
                    }
                    rewrite(p, 16, a.resolver)
                    rewrite(p, 12, vpnIp)
                    client.send(p, 0, p.size)
                }
                DnsAction.Drop -> Unit
            }
        }
    }

    private fun fromTunnel(b: ByteArray, off: Int, len: Int) {
        if (len < 20 || (b[off].toInt() ushr 4) != 4) return
        val p = if (off == 0) b else b.copyOfRange(off, off + len)
        if (Ip.i32(p, 16) != vpnIp) return
        val ihl = (p[0].toInt() and 0x0f) * 4
        if (Ip.u8(p, 9) == Ip.UDP && len >= ihl + 8 && Ip.u16(p, ihl) == 53) {
            val f = forwarded[Ip.u16(p, ihl + 2)]
            if (f != null && f.first == Ip.i32(p, 12)) rewrite(p, 12, FakeDns.DNS_IP)
        }
        rewrite(p, 16, FakeDns.TUN_IP)
        writeTun(p, 0, len)
    }

    companion object {
        /** Replaces the IPv4 address at [at] (12 = source, 16 = destination) and fixes the checksums. */
        fun rewrite(p: ByteArray, at: Int, newIp: Int) {
            val old = Ip.i32(p, at)
            if (old == newIp) return
            Ip.put32(p, at, newIp)
            Ip.put16(p, 10, adjust(Ip.u16(p, 10), old, newIp))
            val ihl = (p[0].toInt() and 0x0f) * 4
            val fragOffset = Ip.u16(p, 6) and 0x1fff
            if (fragOffset != 0) return
            when (Ip.u8(p, 9)) {
                Ip.TCP -> if (p.size >= ihl + 18) Ip.put16(p, ihl + 16, adjust(Ip.u16(p, ihl + 16), old, newIp))
                Ip.UDP -> if (p.size >= ihl + 8) {
                    val c = Ip.u16(p, ihl + 6)
                    if (c != 0) Ip.put16(p, ihl + 6, adjust(c, old, newIp).let { if (it == 0) 0xffff else it })
                }
            }
        }

        /** Incremental checksum update for a changed 32-bit value (RFC 1624). */
        fun adjust(sum: Int, old: Int, new: Int): Int {
            var s = (sum.inv() and 0xffff).toLong()
            s += ((old ushr 16).inv() and 0xffff).toLong()
            s += ((old and 0xffff).inv() and 0xffff).toLong()
            s += ((new ushr 16) and 0xffff).toLong()
            s += (new and 0xffff).toLong()
            while (s ushr 16 != 0L) s = (s and 0xffff) + (s ushr 16)
            return (s.inv() and 0xffff).toInt()
        }
    }
}
