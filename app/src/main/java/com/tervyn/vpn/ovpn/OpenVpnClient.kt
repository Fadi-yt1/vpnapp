package com.tervyn.vpn.ovpn

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * A small OpenVPN client (TLS mode, key-method 2) written for Tervyn:
 * TCP or UDP transport, TLS control channel with client certificates,
 * AES-CBC + HMAC or AES-GCM data channel, keepalive pings. It carries raw IP
 * packets: [send] encrypts a packet into the tunnel and [onPacket] receives
 * packets coming back.
 */
class OpenVpnClient(
    private val cfg: OpenVpnConfig,
    private val protectTcp: (Socket) -> Unit = {},
    private val protectUdp: (DatagramSocket) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    /** Settings the server pushed after connecting. */
    data class Pushed(
        val ip: String,
        val mask: String,
        val gateway: String,
        val dns: List<String>,
        val peerId: Int?,
        val cipher: String,
        val ping: Int,
        val pingRestart: Int,
        val compLzo: Boolean,
        val raw: String,
    )

    @Volatile var onPacket: ((ByteArray, Int, Int) -> Unit)? = null
    @Volatile var pushed: Pushed? = null
        private set
    @Volatile var lastRecv = 0L
        private set
    @Volatile var closed = false
        private set
    @Volatile var error: String = ""
        private set
    val bytesIn = java.util.concurrent.atomic.AtomicLong()
    val bytesOut = java.util.concurrent.atomic.AtomicLong()

    /** True while packets keep arriving (pings included). */
    val alive: Boolean
        get() {
            val p = pushed ?: return false
            return !closed && System.currentTimeMillis() - lastRecv < minOf(p.pingRestart, 60).coerceAtLeast(20) * 1000L
        }

    private val rnd = SecureRandom()
    private lateinit var link: Link
    private val lock = Object()

    // ---- control channel ----
    private val localSid = ByteArray(8).also { rnd.nextBytes(it) }
    private var remoteSid: ByteArray? = null
    private var nextOutId = 0
    private var expectedIn = 0
    private val inOrder = TreeMap<Int, ByteArray>()
    private val pendingAcks = ArrayList<Int>()
    private val unacked = LinkedHashMap<Int, Pair<ByteArray, Long>>()
    private var gotServerReset = false

    // ---- TLS ----
    private lateinit var engine: SSLEngine
    private var netIn = ByteBuffer.allocate(64 * 1024)
    private val appIn = ByteArrayOutputStream()
    private var handshakeDone = false
    private var km2Sent = false
    private var km2Parsed = false
    private var pushRequested = false
    private val clientR1 = ByteArray(32)
    private val clientR2 = ByteArray(32)
    private val preMaster = ByteArray(48)

    // ---- data channel ----
    private var gcm = false
    private lateinit var encKey: SecretKeySpec
    private lateinit var decKey: SecretKeySpec
    private lateinit var encMac: Mac
    private lateinit var decMac: Mac
    private var encImplicitIv = ByteArray(8)
    private var decImplicitIv = ByteArray(8)
    private var outPid = 0
    private var dataKeyId = 0
    private val sendLock = Object()
    private val encCipher by lazy { Cipher.getInstance(if (gcm) "AES/GCM/NoPadding" else "AES/CBC/PKCS5Padding") }
    private val decCipher by lazy { Cipher.getInstance(if (gcm) "AES/GCM/NoPadding" else "AES/CBC/PKCS5Padding") }

    private val ready = CountDownLatch(1)
    private var reader: Thread? = null
    private var pinger: Thread? = null

    // =====================================================================

    /** Connects and waits for the server's settings. Throws with a readable reason. */
    fun connect(timeoutMs: Long = 25_000): Pushed {
        link = openLink(cfg, timeoutMs.toInt().coerceAtMost(15_000), protectTcp, protectUdp)
        engine = makeEngine(cfg)
        rnd.nextBytes(clientR1); rnd.nextBytes(clientR2); rnd.nextBytes(preMaster)
        reader = Thread({ readLoop() }, "ovpn-read").apply { isDaemon = true; start() }
        synchronized(lock) { sendControl(P_CONTROL_HARD_RESET_CLIENT_V2, ByteArray(0)) }
        val deadline = System.currentTimeMillis() + timeoutMs
        // UDP: resend unacknowledged control packets while waiting.
        while (!ready.await(if (cfg.tcp) 500 else 1000, TimeUnit.MILLISECONDS)) {
            if (closed) break
            if (System.currentTimeMillis() > deadline) { fail("The OpenVPN server didn't finish connecting in time."); break }
            if (!cfg.tcp) synchronized(lock) { resendUnacked() }
        }
        val p = pushed
        if (p == null || closed) {
            close()
            throw IOException(error.ifEmpty { "Couldn't connect to the OpenVPN server." })
        }
        pinger = Thread({ pingLoop() }, "ovpn-ping").apply { isDaemon = true; start() }
        return p
    }

    fun close() {
        closed = true
        runCatching { link.close() }
        reader?.interrupt()
        pinger?.interrupt()
        ready.countDown()
    }

    private fun fail(msg: String) {
        if (error.isEmpty()) error = msg
        log("ovpn: $msg")
        close()
    }

    // =====================================================================
    // Transport

    private interface Link {
        fun send(p: ByteArray)
        fun recv(): ByteArray
        fun close()
    }

    private class TcpLink(val s: Socket) : Link {
        private val inp = DataInputStream(s.getInputStream().buffered(64 * 1024))
        private val out: OutputStream = s.getOutputStream()
        override fun send(p: ByteArray) {
            val b = ByteArray(p.size + 2)
            b[0] = (p.size ushr 8).toByte(); b[1] = p.size.toByte()
            System.arraycopy(p, 0, b, 2, p.size)
            synchronized(this) { out.write(b); out.flush() }
        }
        override fun recv(): ByteArray {
            val n = inp.readUnsignedShort()
            return ByteArray(n).also { inp.readFully(it) }
        }
        override fun close() { s.close() }
    }

    private class UdpLink(val s: DatagramSocket) : Link {
        private val buf = ByteArray(65535)
        override fun send(p: ByteArray) = s.send(DatagramPacket(p, p.size))
        override fun recv(): ByteArray {
            val pk = DatagramPacket(buf, buf.size)
            s.receive(pk)
            return buf.copyOf(pk.length)
        }
        override fun close() = s.close()
    }

    // =====================================================================
    // Control channel (reliability layer)

    private fun sendControl(op: Int, payload: ByteArray) {
        val id = nextOutId++
        val b = ByteArrayOutputStream()
        b.write((op shl 3) or 0)
        b.write(localSid)
        writeAcks(b)
        b.write(int32(id))
        b.write(payload)
        val pkt = b.toByteArray()
        unacked[id] = pkt to System.currentTimeMillis()
        link.send(pkt)
    }

    private fun writeAcks(b: ByteArrayOutputStream) {
        val n = minOf(pendingAcks.size, 8)
        b.write(n)
        if (n > 0) {
            for (i in 0 until n) b.write(int32(pendingAcks[i]))
            repeat(n) { pendingAcks.removeAt(0) }
            b.write(remoteSid ?: ByteArray(8))
        }
    }

    private fun flushAcks() {
        while (pendingAcks.isNotEmpty()) {
            val b = ByteArrayOutputStream()
            b.write((P_ACK_V1 shl 3) or 0)
            b.write(localSid)
            writeAcks(b)
            link.send(b.toByteArray())
        }
    }

    private fun resendUnacked() {
        val now = System.currentTimeMillis()
        for ((id, v) in unacked.entries.toList()) {
            if (now - v.second > 2000) {
                runCatching { link.send(v.first) }
                unacked[id] = v.first to now
            }
        }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val p = try { link.recv() } catch (e: SocketTimeoutException) {
                    if (!cfg.tcp && pushed == null) synchronized(lock) { resendUnacked() }
                    continue
                }
                if (p.isEmpty()) continue
                lastRecv = System.currentTimeMillis()
                val op = (p[0].toInt() and 0xff) ushr 3
                if (op == P_DATA_V1 || op == P_DATA_V2) {
                    if (pushed != null || km2Parsed) handleData(p, op)
                } else {
                    synchronized(lock) { handleControl(p, op) }
                }
            }
        } catch (e: Exception) {
            if (!closed) fail("Connection to the OpenVPN server closed (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    private fun handleControl(p: ByteArray, op: Int) {
        val r = ByteBuffer.wrap(p)
        r.get()
        val sid = ByteArray(8).also { r.get(it) }
        val nAck = r.get().toInt() and 0xff
        repeat(nAck) { unacked.remove(r.int) }
        if (nAck > 0) r.position(r.position() + 8)
        when (op) {
            P_ACK_V1 -> return
            P_CONTROL_HARD_RESET_SERVER_V2, P_CONTROL_V1 -> {
                if (op == P_CONTROL_HARD_RESET_SERVER_V2) {
                    if (gotServerReset) { pendingAcks += r.int; flushAcks(); return }
                    remoteSid = sid
                    gotServerReset = true
                }
                val id = r.int
                val payload = ByteArray(r.remaining()).also { r.get(it) }
                pendingAcks += id
                if (id >= expectedIn) inOrder[id] = payload
                while (true) {
                    val next = inOrder.remove(expectedIn) ?: break
                    expectedIn++
                    if (next.isNotEmpty()) netInPut(next)
                }
                if (op == P_CONTROL_HARD_RESET_SERVER_V2) {
                    engine.beginHandshake()
                }
                pumpTls()
                flushAcks()
            }
            P_CONTROL_SOFT_RESET_V1 -> {
                // The server wants new keys (usually after an hour). Reconnecting is simpler and just as safe.
                fail("The OpenVPN server asked to renew keys; reconnecting.")
            }
            else -> Unit
        }
    }

    private fun netInPut(b: ByteArray) {
        if (netIn.remaining() < b.size) {
            val bigger = ByteBuffer.allocate((netIn.capacity() + b.size) * 2)
            netIn.flip(); bigger.put(netIn); netIn = bigger
        }
        netIn.put(b)
    }

    // =====================================================================
    // TLS over the control channel

    private fun pumpTls() {
        var guard = 0
        while (guard++ < 200 && !closed) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    while (true) { val t = engine.delegatedTask ?: break; t.run() }
                }
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrapAndSend(ByteArray(0))
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP, SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN -> {
                    if (!unwrapOnce()) break
                }
                else -> {
                    if (!handshakeDone) onHandshakeDone()
                    if (netIn.position() == 0 || !unwrapOnce()) break
                }
            }
        }
        if (handshakeDone) processAppData()
    }

    /** Returns false when more data from the server is needed. */
    private fun unwrapOnce(): Boolean {
        netIn.flip()
        var out = ByteBuffer.allocate(engine.session.applicationBufferSize + 1024)
        var res: SSLEngineResult
        while (true) {
            res = engine.unwrap(netIn, out)
            if (res.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                out = ByteBuffer.allocate(out.capacity() * 2)
                continue
            }
            break
        }
        netIn.compact()
        out.flip()
        if (out.hasRemaining()) appIn.write(out.array(), 0, out.limit())
        if (res.status == SSLEngineResult.Status.CLOSED) { fail("The OpenVPN server closed the TLS session."); return false }
        if (res.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) return false
        if (res.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED && !handshakeDone) onHandshakeDone()
        return res.bytesConsumed() > 0 || res.bytesProduced() > 0 || res.handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_UNWRAP
    }

    private fun wrapAndSend(app: ByteArray) {
        val src = ByteBuffer.wrap(app)
        do {
            var out = ByteBuffer.allocate(engine.session.packetBufferSize + 1024)
            val res = engine.wrap(src, out)
            if (res.status == SSLEngineResult.Status.CLOSED) { fail("TLS closed."); return }
            out.flip()
            val bytes = ByteArray(out.remaining()).also { out.get(it) }
            var i = 0
            while (i < bytes.size) {
                val n = minOf(CONTROL_CHUNK, bytes.size - i)
                sendControl(P_CONTROL_V1, bytes.copyOfRange(i, i + n))
                i += n
            }
            if (res.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                while (true) { val t = engine.delegatedTask ?: break; t.run() }
            }
        } while (src.hasRemaining())
    }

    private fun onHandshakeDone() {
        handshakeDone = true
        log("ovpn: TLS ${engine.session.protocol} ${engine.session.cipherSuite}")
        if (!km2Sent) {
            km2Sent = true
            wrapAndSend(keyMethod2())
        }
    }

    private fun keyMethod2(): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(int32(0))
        b.write(2)
        b.write(preMaster); b.write(clientR1); b.write(clientR2)
        writeString(b, optionsString())
        if (cfg.userPass) { writeString(b, cfg.username); writeString(b, cfg.password) }
        else { b.write(0); b.write(0); b.write(0); b.write(0) }
        writeString(b, "IV_VER=2.6.8\nIV_PLAT=android\nIV_PROTO=2\nIV_NCP=2\nIV_CIPHERS=${cipherList()}\nIV_TCPNL=1\n" +
            (if (cfg.compLzo) "IV_LZO_STUB=1\nIV_COMP_STUB=1\n" else ""))
        return b.toByteArray()
    }

    private fun cipherList(): String = (listOf(cfg.cipher) + listOf("AES-256-GCM", "AES-128-GCM")).distinct().joinToString(":")

    private fun optionsString(): String {
        val bits = keyBytes(cfg.cipher) * 8
        val isGcm = cfg.cipher.endsWith("GCM")
        return "V4,dev-type tun,link-mtu 1559,tun-mtu 1500,proto ${if (cfg.tcp) "TCPv4_CLIENT" else "UDPv4"}," +
            (if (cfg.compLzo) "comp-lzo," else "") +
            "cipher ${cfg.cipher},auth ${if (isGcm) "[null-digest]" else cfg.auth},keysize $bits,key-method 2,tls-client"
    }

    private fun processAppData() {
        val data = appIn.toByteArray()
        if (data.isEmpty()) return
        var pos = 0
        if (!km2Parsed) {
            if (data.size < 71) return
            val optLen = ((data[69].toInt() and 0xff) shl 8) or (data[70].toInt() and 0xff)
            if (data.size < 71 + optLen) return
            val serverR1 = data.copyOfRange(5, 37)
            val serverR2 = data.copyOfRange(37, 69)
            log("ovpn: server options: ${String(data, 71, maxOf(0, optLen - 1))}")
            pos = 71 + optLen
            deriveKeys(serverR1, serverR2, cfg.cipher)
            km2Parsed = true
        }
        // The rest: NUL-terminated text messages (PUSH_REPLY, AUTH_FAILED…).
        var start = pos
        var consumed = pos
        for (i in pos until data.size) {
            if (data[i].toInt() == 0) {
                if (i > start) onMessage(String(data, start, i - start, Charsets.UTF_8))
                start = i + 1
                consumed = i + 1
            }
        }
        appIn.reset()
        if (consumed < data.size) appIn.write(data, consumed, data.size - consumed)
        if (km2Parsed && !pushRequested) {
            pushRequested = true
            wrapAndSend("PUSH_REQUEST".toByteArray() + 0)
        }
    }

    private fun onMessage(m: String) {
        log("ovpn: ${m.take(300)}")
        when {
            m.startsWith("PUSH_REPLY") -> onPushReply(m)
            m.startsWith("AUTH_FAILED") -> fail("The OpenVPN server refused the login" + m.removePrefix("AUTH_FAILED").trimStart(',', ' ').let { if (it.isNotEmpty()) ": $it" else "." })
            m.startsWith("RESTART") || m.startsWith("HALT") -> fail("The OpenVPN server asked to disconnect.")
        }
    }

    private fun onPushReply(m: String) {
        if (pushed != null) return
        var ip = ""; var mask = "255.255.255.255"; var gw = ""; var peerId: Int? = null
        var cipher = cfg.cipher; var ping = 10; var restart = 120; var comp = cfg.compLzo
        val dns = ArrayList<String>()
        for (opt in m.removePrefix("PUSH_REPLY").split(',')) {
            val p = opt.trim().split(Regex("\\s+"))
            when (p[0]) {
                "ifconfig" -> { ip = p.getOrElse(1) { "" }; mask = p.getOrElse(2) { mask } }
                "route-gateway" -> gw = p.getOrElse(1) { "" }
                "dhcp-option" -> if (p.getOrNull(1) == "DNS") p.getOrNull(2)?.let { dns += it }
                "peer-id" -> peerId = p.getOrNull(1)?.toIntOrNull()
                "cipher" -> p.getOrNull(1)?.uppercase()?.let { if (it in OpenVpnConfig.SUPPORTED_CIPHERS) cipher = it }
                "ping" -> p.getOrNull(1)?.toIntOrNull()?.let { ping = it }
                "ping-restart" -> p.getOrNull(1)?.toIntOrNull()?.let { restart = it }
                "comp-lzo", "compress" -> comp = true
                "key-derivation" -> if (p.getOrNull(1) == "tls-ekm") { fail("This server needs tls-ekm key derivation, which isn't supported."); return }
            }
        }
        if (ip.isEmpty()) { fail("The OpenVPN server didn't give an IP address."); return }
        if (cipher != cfg.cipher) deriveKeys(lastServerR1!!, lastServerR2!!, cipher)
        pushed = Pushed(ip, mask, gw, dns, peerId, cipher, ping.coerceIn(2, 60), restart, comp, m)
        lastRecv = System.currentTimeMillis()
        ready.countDown()
    }

    // =====================================================================
    // Keys

    private var lastServerR1: ByteArray? = null
    private var lastServerR2: ByteArray? = null

    private fun deriveKeys(serverR1: ByteArray, serverR2: ByteArray, cipher: String) {
        lastServerR1 = serverR1; lastServerR2 = serverR2
        val sSid = remoteSid ?: throw IOException("no server session")
        val master = prf(preMaster, "OpenVPN master secret", clientR1 + serverR1, 48)
        val k = prf(master, "OpenVPN key expansion", clientR2 + serverR2 + localSid + sSid, 256)
        gcm = cipher.endsWith("GCM")
        val kl = keyBytes(cipher)
        // Client: sends with key slot 0, receives with key slot 1 (each slot: 64 cipher + 64 hmac bytes).
        encKey = SecretKeySpec(k, 0, kl, "AES")
        decKey = SecretKeySpec(k, 128, kl, "AES")
        if (gcm) {
            encImplicitIv = k.copyOfRange(64, 72)
            decImplicitIv = k.copyOfRange(192, 200)
        } else {
            val (alg, ml) = macAlg(cfg.auth)
            encMac = Mac.getInstance(alg).apply { init(SecretKeySpec(k, 64, ml, alg)) }
            decMac = Mac.getInstance(alg).apply { init(SecretKeySpec(k, 192, ml, alg)) }
        }
    }

    // =====================================================================
    // Data channel

    /** Encrypts one IP packet into the tunnel. */
    fun send(pkt: ByteArray, off: Int = 0, len: Int = pkt.size) {
        if (closed || pushed == null) return
        val p = pushed!!
        try {
            synchronized(sendLock) {
                val pid = ++outPid
                val header = if (p.peerId != null) byteArrayOf(((P_DATA_V2 shl 3) or dataKeyId).toByte(),
                    (p.peerId ushr 16).toByte(), (p.peerId ushr 8).toByte(), p.peerId.toByte())
                else byteArrayOf(((P_DATA_V1 shl 3) or dataKeyId).toByte())
                val plain = ByteArrayOutputStream(len + 8)
                val out: ByteArray
                if (gcm) {
                    if (p.compLzo) plain.write(0xFA)
                    plain.write(pkt, off, len)
                    val pidB = int32(pid)
                    encCipher.init(Cipher.ENCRYPT_MODE, encKey, GCMParameterSpec(128, pidB + encImplicitIv))
                    if (header.size == 4) encCipher.updateAAD(header)
                    encCipher.updateAAD(pidB)
                    val ct = encCipher.doFinal(plain.toByteArray())   // ciphertext + tag
                    val tag = ct.copyOfRange(ct.size - 16, ct.size)
                    out = header + pidB + tag + ct.copyOfRange(0, ct.size - 16)
                } else {
                    plain.write(int32(pid))
                    if (p.compLzo) plain.write(0xFA)
                    plain.write(pkt, off, len)
                    val iv = ByteArray(16).also { rnd.nextBytes(it) }
                    encCipher.init(Cipher.ENCRYPT_MODE, encKey, IvParameterSpec(iv))
                    val ct = encCipher.doFinal(plain.toByteArray())
                    encMac.update(iv); val mac = encMac.doFinal(ct)
                    out = header + mac + iv + ct
                }
                link.send(out)
                bytesOut.addAndGet(len.toLong())
            }
        } catch (e: Exception) {
            if (!closed) fail("Sending through the OpenVPN server failed (${e.message}).")
        }
    }

    private fun handleData(p: ByteArray, op: Int) {
        val h = if (op == P_DATA_V2) 4 else 1
        val plain: ByteArray
        try {
            if (gcm) {
                if (p.size < h + 20) return
                val pidB = p.copyOfRange(h, h + 4)
                val tag = p.copyOfRange(h + 4, h + 20)
                decCipher.init(Cipher.DECRYPT_MODE, decKey, GCMParameterSpec(128, pidB + decImplicitIv))
                if (op == P_DATA_V2) decCipher.updateAAD(p, 0, 4)
                decCipher.updateAAD(pidB)
                plain = decCipher.doFinal(p.copyOfRange(h + 20, p.size) + tag)
            } else {
                val ml = decMac.macLength
                if (p.size < h + ml + 16) return
                decMac.update(p, h + ml, p.size - h - ml)
                val mac = decMac.doFinal()
                if (!MessageDigest.isEqual(mac, p.copyOfRange(h, h + ml))) return
                decCipher.init(Cipher.DECRYPT_MODE, decKey, IvParameterSpec(p, h + ml, 16))
                val dec = decCipher.doFinal(p, h + ml + 16, p.size - h - ml - 16)
                plain = dec.copyOfRange(4, dec.size)
            }
        } catch (e: Exception) {
            return   // corrupted or replayed packet
        }
        var off = 0
        var len = plain.size
        if (len > 0 && (plain[0].toInt() and 0xff) == 0xFA) { off = 1; len-- }
        if (len == PING.size && plain.copyOfRange(off, off + len).contentEquals(PING)) return
        if (len <= 0) return
        bytesIn.addAndGet(len.toLong())
        onPacket?.invoke(plain, off, len)
    }

    private fun pingLoop() {
        try {
            while (!closed) {
                Thread.sleep((pushed?.ping ?: 10) * 1000L)
                send(PING)
            }
        } catch (_: InterruptedException) {
        }
    }

    // =====================================================================

    companion object {
        const val P_CONTROL_SOFT_RESET_V1 = 3
        const val P_CONTROL_V1 = 4
        const val P_ACK_V1 = 5
        const val P_DATA_V1 = 6
        const val P_CONTROL_HARD_RESET_CLIENT_V2 = 7
        const val P_CONTROL_HARD_RESET_SERVER_V2 = 8
        const val P_DATA_V2 = 9
        private const val CONTROL_CHUNK = 1100

        val PING = byteArrayOf(0x2a, 0x18, 0x7b, 0xf3.toByte(), 0x64, 0x1e, 0xb4.toByte(), 0xcb.toByte(),
            0x07, 0xed.toByte(), 0x2d, 0x0a, 0x98.toByte(), 0x1f, 0xc7.toByte(), 0x48)

        private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

        private fun writeString(b: ByteArrayOutputStream, s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            val n = bytes.size + 1
            b.write(n ushr 8); b.write(n and 0xff)
            b.write(bytes); b.write(0)
        }

        private fun keyBytes(cipher: String) = when {
            cipher.startsWith("AES-256") -> 32
            cipher.startsWith("AES-192") -> 24
            else -> 16
        }

        private fun macAlg(auth: String): Pair<String, Int> = when (auth.uppercase().replace("-", "")) {
            "SHA256" -> "HmacSHA256" to 32
            "SHA512" -> "HmacSHA512" to 64
            "SHA384" -> "HmacSHA384" to 48
            "MD5" -> "HmacMD5" to 16
            else -> "HmacSHA1" to 20
        }

        /** TLS 1.0 PRF (P_MD5 xor P_SHA1), as OpenVPN uses for key-method 2. */
        fun prf(secret: ByteArray, label: String, seed: ByteArray, len: Int): ByteArray {
            val half = (secret.size + 1) / 2
            val s1 = secret.copyOfRange(0, half)
            val s2 = secret.copyOfRange(secret.size - half, secret.size)
            val ls = label.toByteArray() + seed
            val a = pHash("HmacMD5", s1, ls, len)
            val b = pHash("HmacSHA1", s2, ls, len)
            return ByteArray(len) { (a[it].toInt() xor b[it].toInt()).toByte() }
        }

        private fun pHash(alg: String, secret: ByteArray, seed: ByteArray, len: Int): ByteArray {
            val mac = Mac.getInstance(alg).apply { init(SecretKeySpec(secret, alg)) }
            val out = ByteArrayOutputStream()
            var a = seed
            while (out.size() < len) {
                a = mac.doFinal(a)
                mac.update(a); mac.update(seed)
                out.write(mac.doFinal())
            }
            return out.toByteArray().copyOf(len)
        }

        private fun openLink(cfg: OpenVpnConfig, timeoutMs: Int, protectTcp: (Socket) -> Unit, protectUdp: (DatagramSocket) -> Unit): Link {
            val addr = InetSocketAddress(cfg.host, cfg.port)
            if (addr.isUnresolved) throw IOException("Couldn't find ${cfg.host}.")
            return if (cfg.tcp) {
                val s = Socket()
                protectTcp(s)
                s.tcpNoDelay = true
                try { s.connect(addr, timeoutMs) } catch (e: Exception) {
                    s.close(); throw IOException("Couldn't reach the OpenVPN server ${cfg.remote} (${e.message}).")
                }
                s.soTimeout = 0
                TcpLink(s)
            } else {
                val s = DatagramSocket()
                protectUdp(s)
                s.connect(addr)
                s.soTimeout = 1000
                UdpLink(s)
            }
        }

        // ---- TLS setup ----

        private fun certs(pem: String): List<X509Certificate> {
            if (pem.isBlank()) return emptyList()
            val cf = CertificateFactory.getInstance("X.509")
            return Regex("-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----").findAll(pem)
                .mapNotNull { runCatching { cf.generateCertificate(ByteArrayInputStream(it.value.toByteArray())) as X509Certificate }.getOrNull() }
                .toList()
        }

        private fun pemBody(pem: String): ByteArray =
            Base64.getMimeDecoder().decode(pem.lines().filter { !it.startsWith("-----") }.joinToString(""))

        private fun derLen(n: Int): ByteArray = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            n < 0x10000 -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), n.toByte())
            else -> byteArrayOf(0x83.toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
        }

        private fun der(tag: Int, body: ByteArray) = byteArrayOf(tag.toByte()) + derLen(body.size) + body

        private fun privateKey(pem: String): PrivateKey? {
            if (pem.isBlank()) return null
            val body = pemBody(pem)
            val pkcs8 = when {
                pem.contains("BEGIN RSA PRIVATE KEY") -> {
                    // PKCS#1 → PKCS#8: wrap with the rsaEncryption algorithm identifier.
                    val algId = der(0x30, byteArrayOf(0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00))
                    der(0x30, byteArrayOf(0x02, 0x01, 0x00) + algId + der(0x04, body))
                }
                pem.contains("BEGIN PRIVATE KEY") -> body
                else -> return null
            }
            for (alg in listOf("RSA", "EC")) {
                runCatching { return KeyFactory.getInstance(alg).generatePrivate(PKCS8EncodedKeySpec(pkcs8)) }
            }
            return null
        }

        private fun makeEngine(cfg: OpenVpnConfig): SSLEngine {
            val ca = certs(cfg.ca)
            if (ca.isEmpty()) throw IOException("The OpenVPN config's CA certificate couldn't be read.")
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            ca.forEachIndexed { i, c -> ks.setCertificateEntry("ca$i", c) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            val pkix = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            // Trust the server if it chains to the config's CA, or *is* that certificate (self-signed servers).
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    try {
                        pkix.checkServerTrusted(chain, authType)
                    } catch (e: Exception) {
                        val leaf = chain.firstOrNull() ?: throw e
                        val pinned = ca.any { it == leaf || it.publicKey == leaf.publicKey } ||
                            ca.any { c -> runCatching { leaf.verify(c.publicKey); true }.getOrDefault(false) }
                        if (!pinned) throw e
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = ca.toTypedArray()
            }
            val km = run {
                val key = privateKey(cfg.key)
                val chain = certs(cfg.cert)
                if (key == null || chain.isEmpty()) null
                else {
                    val kk = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
                    kk.setKeyEntry("client", key, CharArray(0), chain.toTypedArray())
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(kk, CharArray(0)) }.keyManagers
                }
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(km, arrayOf<TrustManager>(tm), SecureRandom())
            return ctx.createSSLEngine().apply { useClientMode = true }
        }

        /**
         * Is this OpenVPN server up? Sends the opening handshake packet and times
         * the server's answer (ms), or null when it doesn't answer.
         */
        fun probe(cfg: OpenVpnConfig, timeoutMs: Int = 5000, protectTcp: (Socket) -> Unit = {}, protectUdp: (DatagramSocket) -> Unit = {}): Int? {
            val sid = ByteArray(8).also { SecureRandom().nextBytes(it) }
            val pkt = byteArrayOf((P_CONTROL_HARD_RESET_CLIENT_V2 shl 3).toByte()) + sid + byteArrayOf(0) + int32(0)
            var link: Link? = null
            return try {
                val t0 = System.nanoTime()
                link = openLink(cfg, timeoutMs, protectTcp, protectUdp)
                if (cfg.tcp) (link as TcpLink).s.soTimeout = timeoutMs
                val deadline = System.currentTimeMillis() + timeoutMs
                link.send(pkt)
                while (System.currentTimeMillis() < deadline) {
                    val r = try { link.recv() } catch (e: SocketTimeoutException) { if (!cfg.tcp) { link.send(pkt); continue } else throw e }
                    if (r.isNotEmpty() && ((r[0].toInt() and 0xff) ushr 3) == P_CONTROL_HARD_RESET_SERVER_V2) {
                        return ((System.nanoTime() - t0) / 1_000_000).toInt()
                    }
                }
                null
            } catch (e: Exception) {
                null
            } finally {
                runCatching { link?.close() }
            }
        }
    }
}
