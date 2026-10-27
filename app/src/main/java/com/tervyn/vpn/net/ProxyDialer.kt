package com.tervyn.vpn.net

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

/** The proxy itself didn't answer properly (dead or broken server). Try the next one. */
class ProxyDeadException(msg: String, cause: Throwable? = null) : IOException(msg, cause)

/** The proxy works but could not reach the website. Another server won't help much. */
class TargetFailedException(msg: String) : IOException(msg)

/** The bits of a server we need to connect through it. */
interface ProxyEndpoint {
    val proto: String     // "socks5" or "http"
    val address: String
    val port: Int
    val username: String
    val password: String
}

/** Opens a TCP connection to host:port through a SOCKS5 or HTTP (CONNECT) proxy. */
object ProxyDialer {

    fun open(p: ProxyEndpoint, host: String, port: Int, timeoutMs: Int = 8000): Socket {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            try {
                s.connect(InetSocketAddress(p.address, p.port), timeoutMs)
            } catch (e: IOException) {
                throw ProxyDeadException("Can't reach server ${p.address}:${p.port}", e)
            }
            s.soTimeout = timeoutMs
            if (p.proto == "socks5") socks5(s, p, host, port) else httpConnect(s, p, host, port)
            s.soTimeout = 0
            return s
        } catch (e: Throwable) {
            runCatching { s.close() }
            if (e is IOException && e !is ProxyDeadException && e !is TargetFailedException) {
                throw ProxyDeadException("Server ${p.address}:${p.port} failed: ${e.message}", e)
            }
            throw e
        }
    }

    private fun socks5(s: Socket, p: ProxyEndpoint, host: String, port: Int) {
        val out = s.getOutputStream()
        val inp = s.getInputStream()
        val auth = p.username.isNotEmpty()
        out.write(if (auth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        out.flush()
        val hello = readN(inp, 2)
        if (hello[0].toInt() != 5) throw ProxyDeadException("Not a SOCKS5 server")
        when (hello[1].toInt() and 0xff) {
            0 -> {}
            2 -> {
                val u = p.username.toByteArray()
                val pw = p.password.toByteArray()
                out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(pw.size.toByte()) + pw)
                out.flush()
                val r = readN(inp, 2)
                if (r[1].toInt() != 0) throw ProxyDeadException("Wrong username or password for this server")
            }
            else -> throw ProxyDeadException("SOCKS5 server wants a login")
        }
        val ipv4 = parseIpv4(host)
        val req = java.io.ByteArrayOutputStream()
        req.write(byteArrayOf(5, 1, 0))
        if (ipv4 != null) {
            req.write(1); req.write(ipv4)
        } else {
            val h = host.toByteArray()
            req.write(3); req.write(h.size); req.write(h)
        }
        req.write(port shr 8); req.write(port and 0xff)
        out.write(req.toByteArray())
        out.flush()
        val head = readN(inp, 4)
        if (head[0].toInt() != 5) throw ProxyDeadException("Bad SOCKS5 reply")
        val rep = head[1].toInt() and 0xff
        // Read the bound address so no stray bytes are left in the stream.
        when (head[3].toInt()) {
            1 -> readN(inp, 4)
            4 -> readN(inp, 16)
            3 -> readN(inp, inp.read().also { if (it < 0) throw ProxyDeadException("Closed") })
            else -> if (rep == 0) throw ProxyDeadException("Bad SOCKS5 address type")
        }
        if (rep == 0) readN(inp, 2) else {
            // 1 = general failure: on free servers this usually means the server itself is broken.
            if (rep == 1) throw ProxyDeadException("SOCKS5 server failure")
            throw TargetFailedException("Server could not reach $host (code $rep)")
        }
    }

    private fun httpConnect(s: Socket, p: ProxyEndpoint, host: String, port: Int) {
        val target = "$host:$port"
        val sb = StringBuilder("CONNECT $target HTTP/1.1\r\nHost: $target\r\nProxy-Connection: keep-alive\r\n")
        if (p.username.isNotEmpty()) {
            val token = Base64.getEncoder().encodeToString("${p.username}:${p.password}".toByteArray())
            sb.append("Proxy-Authorization: Basic $token\r\n")
        }
        sb.append("\r\n")
        s.getOutputStream().apply { write(sb.toString().toByteArray()); flush() }
        // Read the reply headers byte by byte so we never eat data meant for the app.
        val inp = s.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = inp.read()
            if (b < 0) throw ProxyDeadException("Server closed the connection")
            head.append(b.toChar())
            if (head.length > 16384) throw ProxyDeadException("Reply too long")
        }
        val code = head.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        when (code) {
            200 -> {}
            407 -> throw ProxyDeadException("Server needs a login")
            in 500..599 -> if (code == 502 || code == 504) throw TargetFailedException("Server could not reach $host ($code)")
                else throw ProxyDeadException("Server error $code")
            else -> throw ProxyDeadException("Server refused ($code)")
        }
    }

    fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            out[i] = n.toByte()
        }
        return out
    }

    private fun readN(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = inp.read(b, got, n - got)
            if (r < 0) throw ProxyDeadException("Server closed the connection")
            got += r
        }
        return b
    }
}
