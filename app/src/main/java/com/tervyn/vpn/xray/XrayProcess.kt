package com.tervyn.vpn.xray

import com.tervyn.vpn.core.Links
import com.tervyn.vpn.core.Server
import com.tervyn.vpn.net.ProxyEndpoint
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** A local SOCKS5 port that leads to one VLESS / VMess / Trojan / Shadowsocks server. */
data class LocalEndpoint(
    override val port: Int,
    override val username: String,
    override val password: String,
) : ProxyEndpoint {
    override val proto = "socks5"
    override val address = "127.0.0.1"
}

/**
 * Runs one Xray-core process for a set of servers. Each server gets its own
 * password-protected SOCKS port on 127.0.0.1, so the VPN engine can use it
 * like any other proxy (and other apps on the phone can't).
 */
class XrayProcess(
    private val binary: File,
    private val workDir: File,
    private val name: String,
    servers: List<Server>,
) {
    val servers: List<Server> = servers.filter { it.isCore }
    private val user = "tervyn"
    private val pass = randomToken()
    private var process: Process? = null

    /** Server id -> local SOCKS endpoint. Filled by [start]. */
    var endpoints: Map<String, LocalEndpoint> = emptyMap()
        private set

    /** Servers whose settings Xray refused (left out of the config). */
    var rejected: List<Server> = emptyList()
        private set

    val alive: Boolean get() = process?.isAlive == true
    val logFile: File get() = File(workDir, "xray-$name.log")
    private val configFile: File get() = File(workDir, "xray-$name.json")

    fun start() {
        stop()
        workDir.mkdirs()
        var usable = servers.filter { Links.spec(it.link) != null }
        rejected = servers - usable.toSet()
        if (usable.isEmpty()) throw IOException("None of these servers can be read.")

        // One bad server makes Xray refuse the whole config: find and drop such servers.
        var ports = freePorts(usable.size)
        if (!test(configFor(usable, ports))) {
            val good = usable.filter { s -> test(configFor(listOf(s), freePorts(1))) }
            rejected = rejected + (usable - good.toSet())
            usable = good
            if (usable.isEmpty()) throw IOException("Xray could not use these servers: ${lastError()}")
            ports = freePorts(usable.size)
        }
        configFile.writeText(configFor(usable, ports).toString(2))
        val pb = ProcessBuilder(binary.absolutePath, "run", "-c", configFile.absolutePath)
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(logFile)
        pb.environment()["XRAY_LOCATION_ASSET"] = workDir.absolutePath
        val p = pb.start()
        process = p

        // Wait until every port accepts connections.
        val deadline = System.currentTimeMillis() + 8000
        for (port in ports) {
            while (!portOpen(port)) {
                if (!p.isAlive) throw IOException("Xray stopped: ${lastError()}")
                if (System.currentTimeMillis() > deadline) { stop(); throw IOException("Xray did not start in time.") }
                Thread.sleep(50)
            }
        }
        endpoints = usable.mapIndexed { i, s -> s.id to LocalEndpoint(ports[i], user, pass) }.toMap()
    }

    fun stop() {
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) p.destroyForcibly()
        }
        process = null
        endpoints = emptyMap()
    }

    private fun configFor(list: List<Server>, ports: List<Int>) = XrayConfig.build(
        list.mapIndexed { i, s -> XrayConfig.Entry("s$i", Links.spec(s.link)!!, ports[i]) }, user, pass,
    )

    /** `xray run -test`: checks a config without starting anything. */
    private fun test(cfg: org.json.JSONObject): Boolean {
        val f = File(workDir, "xray-$name-test.json")
        f.writeText(cfg.toString())
        val p = ProcessBuilder(binary.absolutePath, "run", "-test", "-c", f.absolutePath)
            .directory(workDir).redirectErrorStream(true).redirectOutput(logFile)
            .also { it.environment()["XRAY_LOCATION_ASSET"] = workDir.absolutePath }
            .start()
        val done = p.waitFor(15, TimeUnit.SECONDS)
        if (!done) p.destroyForcibly()
        f.delete()
        return done && p.exitValue() == 0
    }

    fun lastError(): String = runCatching {
        logFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(3).joinToString(" ").take(300)
    }.getOrDefault("")

    companion object {
        private val rnd = SecureRandom()

        fun randomToken(): String {
            val b = ByteArray(16)
            rnd.nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        fun freePorts(n: Int): List<Int> {
            val sockets = List(n) { ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")) }
            val ports = sockets.map { it.localPort }
            sockets.forEach { runCatching { it.close() } }
            return ports
        }

        fun portOpen(port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200); true }
        } catch (_: IOException) {
            false
        }

        /** Stops Xray processes left over from before (e.g. if Android killed the app). Call before starting any. */
        fun killLeftovers(binary: File) {
            val me = File("/proc/self").canonicalFile.name
            File("/proc").listFiles { f -> f.name.all(Char::isDigit) && f.name != me }?.forEach { f ->
                val cmd = runCatching { File(f, "cmdline").readText() }.getOrDefault("")
                if (cmd.startsWith(binary.absolutePath)) {
                    runCatching { Runtime.getRuntime().exec(arrayOf("kill", "-9", f.name)).waitFor() }
                }
            }
        }
    }
}
