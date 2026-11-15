package com.tervyn.vpn.core

import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Cloudflare WARP: Cloudflare's free WireGuard service. Tervyn creates a
 * WireGuard key on the phone, registers its public half with Cloudflare (the
 * same free sign-up the 1.1.1.1 app does) and gets back a WireGuard server
 * that Xray connects to. The private key never leaves the phone.
 */
object Warp {
    const val SOURCE = "warp"
    private const val API = "https://api.cloudflareclient.com/v0a2158/reg"

    /** X25519 (RFC 7748) on plain BigIntegers: only used once, to make the key pair. */
    object X25519 {
        private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        private val A24 = BigInteger.valueOf(121665)

        private fun le(b: ByteArray) = BigInteger(1, b.reversedArray())
        private fun toLe(v: BigInteger): ByteArray {
            val be = v.mod(P).toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
            return (ByteArray(32 - be.size) + be).reversedArray()
        }

        fun clamp(k: ByteArray): ByteArray = k.copyOf().also {
            it[0] = (it[0].toInt() and 248).toByte()
            it[31] = ((it[31].toInt() and 127) or 64).toByte()
        }

        fun scalarMult(scalar: ByteArray, u: ByteArray): ByteArray {
            val k = le(clamp(scalar))
            val x1 = le(u.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }).mod(P)
            var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO
            var x3 = x1; var z3 = BigInteger.ONE
            var swap = 0
            for (t in 254 downTo 0) {
                val kt = if (k.testBit(t)) 1 else 0
                swap = swap xor kt
                if (swap == 1) { var tmp = x2; x2 = x3; x3 = tmp; tmp = z2; z2 = z3; z3 = tmp }
                swap = kt
                val a = x2.add(z2).mod(P); val aa = a.multiply(a).mod(P)
                val b = x2.subtract(z2).mod(P); val bb = b.multiply(b).mod(P)
                val e = aa.subtract(bb).mod(P)
                val c = x3.add(z3).mod(P); val d = x3.subtract(z3).mod(P)
                val da = d.multiply(a).mod(P); val cb = c.multiply(b).mod(P)
                x3 = da.add(cb).pow(2).mod(P)
                z3 = x1.multiply(da.subtract(cb).pow(2)).mod(P)
                x2 = aa.multiply(bb).mod(P)
                z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
            }
            if (swap == 1) { x2 = x3; z2 = z3 }
            return toLe(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)))
        }

        fun publicKey(priv: ByteArray): ByteArray = scalarMult(priv, ByteArray(32).also { it[0] = 9 })
    }

    /** Registers a new free WARP device and returns it as a wireguard:// link. */
    fun register(): String {
        val priv = X25519.clamp(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val pub = X25519.publicKey(priv)
        val b64 = Base64.getEncoder()
        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val body = JSONObject()
            .put("key", b64.encodeToString(pub)).put("install_id", "").put("fcm_token", "")
            .put("tos", tos).put("model", "PC").put("serial_number", "").put("locale", "en_US")
            .toString()
        val c = URL(API).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.setRequestProperty("User-Agent", "okhttp/3.12.1")
            c.setRequestProperty("CF-Client-Version", "a-6.10-2158")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Cloudflare WARP sign-up failed (HTTP $code). Try again later.")
            return linkFrom(JSONObject(text), priv)
        } finally {
            c.disconnect()
        }
    }

    /** Builds the wireguard:// link from Cloudflare's answer. */
    fun linkFrom(j: JSONObject, priv: ByteArray): String {
        val cfg = j.optJSONObject("config") ?: j.optJSONObject("result")?.optJSONObject("config")
            ?: throw IOException("Unexpected answer from Cloudflare WARP.")
        val peer = cfg.getJSONArray("peers").getJSONObject(0)
        val peerKey = peer.getString("public_key")
        val ep = peer.optJSONObject("endpoint")
        // Port 2408 is WARP's WireGuard port; the v4 address avoids a DNS lookup.
        val host = ep?.optString("v4")?.substringBefore(':')?.takeIf { it.isNotBlank() && it != "0.0.0.0" } ?: "162.159.192.1"
        val addr = cfg.getJSONObject("interface").getJSONObject("addresses")
        val v4 = addr.optString("v4", "172.16.0.2")
        val v6 = addr.optString("v6")
        val reserved = cfg.optString("client_id").takeIf { it.isNotBlank() }?.let { id ->
            runCatching { Base64.getDecoder().decode(id).take(3).joinToString(",") { (it.toInt() and 0xff).toString() } }.getOrNull()
        }
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        val addresses = listOf("$v4/32") + if (v6.isNotBlank()) listOf("$v6/128") else emptyList()
        return "wireguard://" + enc(Base64.getEncoder().encodeToString(priv)) + "@$host:2408" +
            "?publickey=" + enc(peerKey) + "&address=" + enc(addresses.joinToString(",")) + "&mtu=1280" +
            (reserved?.let { "&reserved=" + enc(it) } ?: "") + "#Cloudflare%20WARP"
    }
}
