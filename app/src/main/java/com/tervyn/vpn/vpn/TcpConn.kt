package com.tervyn.vpn.vpn

import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * One TCP connection from an app. We play the part of the website: finish the
 * handshake once the real connection (through the proxy) is open, then copy
 * data both ways with simple flow control and retransmission.
 *
 * "client" = the app on the phone, "upstream" = the socket to the proxy/site.
 */
class TcpConn(
    private val engine: TunEngine,
    private val key: TunEngine.Key,
    clientIsn: Int,
    clientWindow: Int,
    clientMss: Int,
    private val target: String,
    private val targetPort: Int,
    private val proxied: Boolean,
) {
    private enum class State { CONNECTING, SYN_ACK_SENT, ESTABLISHED, CLOSED }

    private companion object {
        const val OUR_MSS = 1460
        const val OUR_WINDOW = 65535
        const val MAX_QUEUE = 1 shl 20          // bytes waiting to be written upstream
        const val MAX_UNACKED = 256 * 1024      // bytes sent to the app but not yet acknowledged
        const val MAX_OOO = 512 * 1024          // out-of-order bytes from the app we keep
        const val MIN_RTO = 400L
        const val IDLE_MS = 10 * 60 * 1000L
        const val CLOSING_MS = 60 * 1000L
        val SHUTDOWN = ByteArray(0)
    }

    private val lock = Object()
    private var state = State.CONNECTING

    private var rcvNxt = clientIsn + 1           // next byte we expect from the app
    private val iss = Random.nextInt()
    private var sndUna = iss                     // oldest byte the app hasn't acknowledged
    private var sndNxt = iss + 1                 // next byte we send (the SYN uses iss)
    private var wnd = clientWindow               // the app's receive window (no scaling)
    private val mss = clientMss.coerceIn(536, OUR_MSS)

    // Data sent to the app but not acknowledged yet, starting at bufSeq.
    private var buf = ByteArray(16 * 1024)
    private var bufLen = 0
    private var bufSeq = iss + 1

    private var finSent = false
    private var finSeq = 0
    private var finAcked = false
    private var clientFin = false

    private var lastActivity = System.currentTimeMillis()
    private var lastSend = 0L
    private var lastProgress = 0L
    private var rto = MIN_RTO
    private var retries = 0
    private var dupAcks = 0
    private var inRecovery = false
    private var recover = 0

    // Segments from the app that arrived ahead of a missing one.
    private val ooo = ArrayList<Pair<Int, ByteArray>>()
    private var oooBytes = 0

    @Volatile
    private var socket: Socket? = null

    // Writes to upstream run on the engine pool, one at a time, in order.
    private val writeQ = ArrayDeque<ByteArray>()
    private var writing = false
    private val queued = AtomicInteger()

    private val src get() = key.src
    private val dst get() = key.dst

    fun start() {
        engine.pool.execute { connectUpstream() }
    }

    private fun connectUpstream() {
        val s = try {
            if (proxied) engine.router.connectProxied(target, targetPort)
            else engine.router.connectDirect(target, targetPort)
        } catch (e: Exception) {
            synchronized(lock) {
                if (state != State.CLOSED) {
                    // Refuse the app's connection so it fails fast instead of hanging.
                    send(0, rcvNxt, Ip.RST or Ip.ACK)
                    closeLocked()
                }
            }
            return
        }
        synchronized(lock) {
            if (state == State.CLOSED) { runCatching { s.close() }; return }
            socket = s
            state = State.SYN_ACK_SENT
            sendSynAck()
        }
        engine.pool.execute { readUpstream(s) }
    }

    // ---------- packets from the app ----------
    fun onPacket(flags: Int, seq: Int, ack: Int, window: Int, b: ByteArray, off: Int, len: Int) {
        synchronized(lock) {
            if (state == State.CLOSED) return
            lastActivity = System.currentTimeMillis()

            if (flags and Ip.RST != 0) { closeLocked(); return }
            if (flags and Ip.SYN != 0) {
                if (state == State.SYN_ACK_SENT) sendSynAck() else if (state == State.ESTABLISHED) sendAck()
                return
            }
            if (flags and Ip.ACK == 0) return
            if (state == State.CONNECTING) return
            if (state == State.SYN_ACK_SENT) {
                if (ack != iss + 1) return
                state = State.ESTABLISHED
                sndUna = iss + 1
                lastProgress = lastActivity
                lock.notifyAll()
            }

            // Acknowledgement of what we sent.
            wnd = window
            val advanced = ack - sndUna
            if (advanced > 0 && sndNxt - ack >= 0) {
                val dataAcked = minOf(ack - bufSeq, bufLen)
                if (dataAcked > 0) {
                    System.arraycopy(buf, dataAcked, buf, 0, bufLen - dataAcked)
                    bufLen -= dataAcked
                    bufSeq += dataAcked
                }
                if (finSent && ack - (finSeq + 1) >= 0) finAcked = true
                sndUna = ack
                dupAcks = 0
                retries = 0
                rto = MIN_RTO
                lastProgress = lastActivity
                if (inRecovery) {
                    // Partial ACK: the next segment was lost too, re-send it right away.
                    if (ack - recover >= 0) inRecovery = false else retransmit(onlyFirst = true)
                }
                lock.notifyAll()
            } else if (advanced == 0 && len == 0 && sndNxt != sndUna && flags and Ip.FIN == 0) {
                if (++dupAcks == 3 && !inRecovery) {
                    retransmit(onlyFirst = true)
                    inRecovery = true
                    recover = sndNxt
                }
                lock.notifyAll() // the window may have opened
            } else {
                lock.notifyAll()
            }

            // Data from the app.
            var accepted = len == 0
            if (len > 0) {
                val d = seq - rcvNxt
                if (!clientFin && queued.get() < MAX_QUEUE) {
                    if (d <= 0 && d + len > 0) {
                        // New data (possibly overlapping what we already have).
                        enqueue(b.copyOfRange(off - d, off + len))
                        rcvNxt = seq + len
                        accepted = true
                        drainOutOfOrder()
                    } else if (d > 0 && d < MAX_OOO && oooBytes + len <= MAX_OOO && ooo.none { it.first == seq }) {
                        ooo.add(seq to b.copyOfRange(off, off + len))
                        oooBytes += len
                    }
                }
                sendAck()
            }

            // The app finished sending.
            if (flags and Ip.FIN != 0) {
                if (!clientFin && accepted && seq + len == rcvNxt) {
                    rcvNxt += 1
                    clientFin = true
                    enqueue(SHUTDOWN)
                    sendAck()
                } else if (clientFin) {
                    sendAck()
                }
            }
            if (clientFin && finAcked) closeLocked()
        }
    }

    /** Hand over kept segments that now follow on from what we have. */
    private fun drainOutOfOrder() {
        var progress = true
        while (progress && ooo.isNotEmpty()) {
            progress = false
            val it = ooo.iterator()
            while (it.hasNext()) {
                val (seq, data) = it.next()
                val d = seq - rcvNxt
                if (d > 0) continue
                it.remove()
                oooBytes -= data.size
                if (d + data.size > 0) {
                    enqueue(data.copyOfRange(-d, data.size))
                    rcvNxt = seq + data.size
                    progress = true
                }
            }
        }
    }

    // ---------- data from upstream ----------
    private fun readUpstream(s: Socket) {
        val tmp = ByteArray(16 * 1024)
        try {
            val inp = s.getInputStream()
            while (true) {
                var allow = 0
                synchronized(lock) {
                    while (true) {
                        if (state == State.CLOSED) return
                        if (state == State.ESTABLISHED) {
                            allow = minOf(wnd - (sndNxt - sndUna), MAX_UNACKED - bufLen)
                            if (allow > 0) break
                        }
                        lock.wait(500)
                    }
                }
                val n = inp.read(tmp, 0, minOf(allow, tmp.size))
                synchronized(lock) {
                    if (state == State.CLOSED) return
                    if (n < 0) {
                        sendFin()
                        return
                    }
                    if (n > 0) appendAndSend(tmp, n)
                }
            }
        } catch (_: InterruptedException) {
        } catch (_: IOException) {
            synchronized(lock) {
                if (state != State.CLOSED) {
                    send(sndNxt, rcvNxt, Ip.RST or Ip.ACK)
                    closeLocked()
                }
            }
        }
    }

    private fun appendAndSend(data: ByteArray, n: Int) {
        if (bufLen + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + n))
        System.arraycopy(data, 0, buf, bufLen, n)
        val startOff = bufLen
        bufLen += n
        if (sndNxt == sndUna) lastProgress = System.currentTimeMillis()
        var o = 0
        while (o < n) {
            val len = minOf(mss, n - o)
            val last = o + len >= n
            send(sndNxt, rcvNxt, Ip.ACK or (if (last) Ip.PSH else 0), buf, startOff + o, len)
            sndNxt += len
            o += len
        }
    }

    private fun sendFin() {
        if (finSent) return
        finSeq = sndNxt
        finSent = true
        if (sndNxt == sndUna) lastProgress = System.currentTimeMillis()
        send(finSeq, rcvNxt, Ip.FIN or Ip.ACK)
        sndNxt += 1
    }

    // ---------- timers ----------
    fun tick(now: Long) {
        synchronized(lock) {
            when (state) {
                State.CLOSED -> return
                State.CONNECTING -> if (now - lastActivity > 30_000) { closeLocked(); return }
                State.SYN_ACK_SENT -> if (now - lastSend > rto) {
                    if (++retries > 6) { send(sndNxt, rcvNxt, Ip.RST or Ip.ACK); closeLocked(); return }
                    rto = minOf(rto * 2, 16_000)
                    sendSynAck()
                }
                State.ESTABLISHED -> if (sndNxt != sndUna && now - lastProgress > rto) {
                    if (++retries > 8) { send(sndNxt, rcvNxt, Ip.RST or Ip.ACK); closeLocked(); return }
                    retransmit(onlyFirst = false)
                    inRecovery = false
                    rto = minOf(rto * 2, 16_000)
                    lastProgress = now
                }
            }
            val closing = clientFin || finSent
            if (now - lastActivity > (if (closing) CLOSING_MS else IDLE_MS)) {
                send(sndNxt, rcvNxt, Ip.RST or Ip.ACK)
                closeLocked()
            }
        }
    }

    /** Re-send unacknowledged data (go-back-N), and the FIN if it's still unacked. */
    private fun retransmit(onlyFirst: Boolean) {
        var seq = bufSeq
        var o = 0
        val limit = if (onlyFirst) minOf(bufLen, mss) else minOf(bufLen, maxOf(wnd, mss))
        while (o < limit) {
            val len = minOf(mss, limit - o)
            send(seq, rcvNxt, Ip.ACK or Ip.PSH, buf, o, len)
            seq += len
            o += len
        }
        if (finSent && !finAcked && o >= bufLen) send(finSeq, rcvNxt, Ip.FIN or Ip.ACK)
    }

    // ---------- helpers ----------
    private fun sendSynAck() {
        engine.write(Ip.tcp(dst, src, key.dport, key.sport, iss, rcvNxt, Ip.SYN or Ip.ACK, OUR_WINDOW, mss = OUR_MSS))
        lastSend = System.currentTimeMillis()
    }

    private fun sendAck() = send(sndNxt, rcvNxt, Ip.ACK)

    private fun send(seq: Int, ack: Int, flags: Int, data: ByteArray? = null, off: Int = 0, len: Int = 0) {
        engine.write(Ip.tcp(dst, src, key.dport, key.sport, seq, ack, flags, OUR_WINDOW, data, off, len))
        lastSend = System.currentTimeMillis()
    }

    private fun enqueue(data: ByteArray) {
        val start: Boolean
        synchronized(writeQ) {
            writeQ.addLast(data)
            queued.addAndGet(data.size)
            start = !writing
            if (start) writing = true
        }
        if (start) engine.pool.execute { drain() }
    }

    private fun drain() {
        val s = socket ?: return
        try {
            val out = s.getOutputStream()
            while (true) {
                val item = synchronized(writeQ) {
                    writeQ.removeFirstOrNull() ?: run { writing = false; null }
                } ?: return
                if (item === SHUTDOWN) {
                    runCatching { s.shutdownOutput() }
                } else {
                    out.write(item)
                    queued.addAndGet(-item.size)
                }
            }
        } catch (_: IOException) {
            synchronized(writeQ) { writing = false }
            synchronized(lock) {
                if (state != State.CLOSED) {
                    send(sndNxt, rcvNxt, Ip.RST or Ip.ACK)
                    closeLocked()
                }
            }
        }
    }

    private fun closeLocked() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        runCatching { socket?.close() }
        engine.remove(key, this)
        lock.notifyAll()
    }

    /** Engine is stopping: close quietly. */
    fun shutdown() {
        synchronized(lock) { closeLocked() }
    }
}
