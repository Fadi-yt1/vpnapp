package com.tervyn.vpn.vpn

import java.util.concurrent.atomic.AtomicInteger

/** Reading and building raw IPv4 / TCP / UDP packets. */
object Ip {
    const val TCP = 6
    const val UDP = 17

    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    fun i32(b: ByteArray, i: Int) = (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
    fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v ushr 8).toByte(); b[i + 1] = v.toByte() }
    fun put32(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 24).toByte(); b[i + 1] = (v ushr 16).toByte(); b[i + 2] = (v ushr 8).toByte(); b[i + 3] = v.toByte()
    }

    fun str(ip: Int) = "${ip ushr 24}.${(ip ushr 16) and 255}.${(ip ushr 8) and 255}.${ip and 255}"
    fun bytes(ip: Int) = byteArrayOf((ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte())
    fun parse(s: String): Int {
        val p = s.split('.').map { it.toInt() }
        return (p[0] shl 24) or (p[1] shl 16) or (p[2] shl 8) or p[3]
    }

    /** Local, LAN and special addresses that should never go through a proxy. */
    fun isPrivate(ip: Int): Boolean {
        val a = ip ushr 24
        val b = (ip ushr 16) and 255
        return a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) ||
            (a == 100 && b in 64..127)
    }

    fun checksum(b: ByteArray, off: Int, len: Int, initial: Long = 0): Int {
        var sum = initial
        var i = off
        val end = off + len
        while (i < end - 1) { sum += u16(b, i); i += 2 }
        if (i < end) sum += (u8(b, i) shl 8)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return (sum.inv() and 0xffff).toInt()
    }

    private fun pseudo(src: Int, dst: Int, proto: Int, len: Int): Long =
        ((src ushr 16) and 0xffff).toLong() + (src and 0xffff) + ((dst ushr 16) and 0xffff) + (dst and 0xffff) + proto + len

    private val ipId = AtomicInteger()

    private fun ipHeader(b: ByteArray, total: Int, proto: Int, src: Int, dst: Int) {
        b[0] = 0x45; b[1] = 0
        put16(b, 2, total)
        put16(b, 4, ipId.incrementAndGet() and 0xffff)
        put16(b, 6, 0x4000) // don't fragment
        b[8] = 64; b[9] = proto.toByte()
        put32(b, 12, src); put32(b, 16, dst)
        put16(b, 10, checksum(b, 0, 20))
    }

    fun tcp(
        src: Int, dst: Int, sport: Int, dport: Int, seq: Int, ack: Int, flags: Int, window: Int,
        data: ByteArray? = null, off: Int = 0, len: Int = 0, mss: Int = 0,
    ): ByteArray {
        val th = if (mss > 0) 24 else 20
        val b = ByteArray(20 + th + len)
        ipHeader(b, b.size, TCP, src, dst)
        val t = 20
        put16(b, t, sport); put16(b, t + 2, dport)
        put32(b, t + 4, seq); put32(b, t + 8, ack)
        b[t + 12] = ((th / 4) shl 4).toByte()
        b[t + 13] = flags.toByte()
        put16(b, t + 14, window)
        if (mss > 0) { b[t + 20] = 2; b[t + 21] = 4; put16(b, t + 22, mss) }
        if (len > 0) System.arraycopy(data!!, off, b, t + th, len)
        put16(b, t + 16, checksum(b, t, th + len, pseudo(src, dst, TCP, th + len)))
        return b
    }

    fun udp(src: Int, dst: Int, sport: Int, dport: Int, payload: ByteArray): ByteArray {
        val b = ByteArray(28 + payload.size)
        ipHeader(b, b.size, UDP, src, dst)
        put16(b, 20, sport); put16(b, 22, dport); put16(b, 24, 8 + payload.size)
        System.arraycopy(payload, 0, b, 28, payload.size)
        var c = checksum(b, 20, 8 + payload.size, pseudo(src, dst, UDP, 8 + payload.size))
        if (c == 0) c = 0xffff
        put16(b, 26, c)
        return b
    }
}

/** Minimal DNS: read the question, build an answer. */
object Dns {
    const val A = 1
    const val AAAA = 28
    const val HTTPS = 65

    class Question(val name: String, val type: Int, val end: Int)

    fun parse(m: ByteArray): Question? {
        if (m.size < 17) return null
        if (m[2].toInt() and 0x80 != 0) return null          // a reply, not a question
        if (Ip.u16(m, 4) < 1) return null
        var i = 12
        val sb = StringBuilder()
        while (true) {
            if (i >= m.size) return null
            val l = m[i].toInt() and 0xff
            i++
            if (l == 0) break
            if (l and 0xC0 != 0 || i + l > m.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(m, i, l, Charsets.ISO_8859_1))
            i += l
        }
        if (i + 4 > m.size) return null
        return Question(sb.toString().lowercase(), Ip.u16(m, i), i + 4)
    }

    /** A standard query for [name] / [type] (recursion desired). */
    fun buildQuery(name: String, type: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val id = (System.nanoTime() and 0xffff).toInt()
        out.write(byteArrayOf((id ushr 8).toByte(), id.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        for (l in name.trimEnd('.').split('.')) { out.write(l.length); out.write(l.toByteArray()) }
        out.write(byteArrayOf(0, (type ushr 8).toByte(), type.toByte(), 0, 1))
        return out.toByteArray()
    }

    fun answerCount(m: ByteArray): Int = if (m.size >= 12) Ip.u16(m, 6) else 0

    /** Reply for a blocked name: A -> 0.0.0.0, AAAA -> ::, anything else -> no records. */
    fun blocked(query: ByteArray, q: Question): ByteArray {
        if (q.type == AAAA) {
            val base = reply(query, q, null)
            val out = ByteArray(base.size + 28)
            System.arraycopy(base, 0, out, 0, base.size)
            Ip.put16(out, 6, 1)
            var o = base.size
            out[o++] = 0xC0.toByte(); out[o++] = 0x0C
            Ip.put16(out, o, AAAA); o += 2
            Ip.put16(out, o, 1); o += 2
            Ip.put32(out, o, 60); o += 4
            Ip.put16(out, o, 16)   // 16 zero bytes follow: ::
            return out
        }
        return reply(query, q, if (q.type == A) 0 else null)
    }

    /** "Server failure" reply, so apps fail fast instead of waiting. */
    fun servfail(query: ByteArray, q: Question): ByteArray =
        reply(query, q, null).also { it[3] = (0x80 or 2).toByte() }

    /** Lowest TTL in the answer section (seconds), or null if there are no answers. */
    fun minTtl(m: ByteArray): Int? {
        try {
            val qd = Ip.u16(m, 4)
            val an = Ip.u16(m, 6)
            if (an == 0) return null
            var i = 12
            fun skipName() {
                while (true) {
                    val l = m[i].toInt() and 0xff
                    if (l == 0) { i += 1; return }
                    if (l and 0xC0 == 0xC0) { i += 2; return }
                    i += 1 + l
                }
            }
            repeat(qd) { skipName(); i += 4 }
            var min = Int.MAX_VALUE
            repeat(an) {
                skipName()
                val ttl = Ip.i32(m, i + 4)
                if (ttl in 0 until min) min = ttl
                i += 10 + Ip.u16(m, i + 8)
            }
            return if (min == Int.MAX_VALUE) null else min
        } catch (_: IndexOutOfBoundsException) {
            return null
        }
    }

    /** IPv4 addresses in the answer section of a reply. */
    fun answerIps(m: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        try {
            val qd = Ip.u16(m, 4)
            val an = Ip.u16(m, 6)
            var i = 12
            fun skipName() {
                while (true) {
                    val l = m[i].toInt() and 0xff
                    if (l == 0) { i += 1; return }
                    if (l and 0xC0 == 0xC0) { i += 2; return }
                    i += 1 + l
                }
            }
            repeat(qd) { skipName(); i += 4 }
            repeat(an) {
                skipName()
                val type = Ip.u16(m, i)
                val len = Ip.u16(m, i + 8)
                i += 10
                if (type == A && len == 4) out += Ip.i32(m, i)
                i += len
            }
        } catch (_: IndexOutOfBoundsException) {
        }
        return out
    }

    /** Answer with one A record ([ip]) or with no records at all (ip = null). */
    fun reply(query: ByteArray, q: Question, ip: Int?, ttl: Int = 60): ByteArray {
        val out = ByteArray(q.end + if (ip != null) 16 else 0)
        System.arraycopy(query, 0, out, 0, q.end)
        out[2] = (0x80 or (query[2].toInt() and 0x79)).toByte() // response, keep opcode + RD
        out[3] = 0x80.toByte()                                  // recursion available, no error
        Ip.put16(out, 4, 1)
        Ip.put16(out, 6, if (ip != null) 1 else 0)
        Ip.put16(out, 8, 0)
        Ip.put16(out, 10, 0)
        if (ip != null) {
            var o = q.end
            out[o++] = 0xC0.toByte(); out[o++] = 0x0C            // name = the question's name
            Ip.put16(out, o, A); o += 2
            Ip.put16(out, o, 1); o += 2                          // class IN
            Ip.put32(out, o, ttl); o += 4
            Ip.put16(out, o, 4); o += 2
            Ip.put32(out, o, ip)
        }
        return out
    }
}

/**
 * Hands out stand-in addresses from 198.18.0.0/15 for site names. When an app
 * then connects to such an address we know which site it wants, so the proxy
 * can look up the real address itself (no DNS leaks, and per-site routing
 * works for every app).
 */
class FakeDns {
    companion object {
        val NET = Ip.parse("198.18.0.0")
        private val MASK = Ip.parse("255.254.0.0")
        val DNS_IP = Ip.parse("198.18.0.1")
        val TUN_IP = Ip.parse("198.18.0.2")
        private val FIRST = Ip.parse("198.18.1.0")
        private const val SIZE = 0x20000 - 0x100 - 1
        fun isFake(ip: Int) = (ip and MASK) == NET
    }

    private val byDomain = HashMap<String, Int>()
    private val byIp = HashMap<Int, String>()
    private var next = 0

    @Synchronized
    fun ipFor(domain: String): Int {
        byDomain[domain]?.let { return it }
        val ip = FIRST + (next % SIZE)
        next++
        byIp.remove(ip)?.let { byDomain.remove(it) }
        byDomain[domain] = ip
        byIp[ip] = domain
        return ip
    }

    @Synchronized
    fun domainFor(ip: Int): String? = byIp[ip]
}
