package com.tervyn.vpn.xray

import com.tervyn.vpn.core.CoreSpec
import org.json.JSONArray
import org.json.JSONObject

/** Builds Xray-core configs (port of the extension's xray.js). */
object XrayConfig {

    /** One local SOCKS inbound per server, each routed to its own outbound. */
    class Entry(val tag: String, val spec: CoreSpec, val port: Int)

    fun build(entries: List<Entry>, user: String, pass: String): JSONObject {
        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray()
        for (e in entries) {
            inbounds.put(obj(
                "tag" to "in-${e.tag}", "listen" to "127.0.0.1", "port" to e.port, "protocol" to "socks",
                "settings" to obj(
                    "auth" to "password",
                    "accounts" to JSONArray().put(obj("user" to user, "pass" to pass)),
                    "udp" to false,
                ),
            ))
            outbounds.put(outbound(e.spec, "out-${e.tag}"))
            rules.put(obj("type" to "field", "inboundTag" to JSONArray().put("in-${e.tag}"), "outboundTag" to "out-${e.tag}"))
        }
        outbounds.put(obj("tag" to "block", "protocol" to "blackhole"))
        return obj(
            "log" to obj("loglevel" to "warning"),
            // Xray looks up server names itself, so it works even where the system resolver isn't reachable.
            "dns" to obj("servers" to JSONArray().put("1.1.1.1").put("8.8.8.8").put("9.9.9.9"), "queryStrategy" to "UseIPv4"),
            "inbounds" to inbounds,
            "outbounds" to outbounds,
            "routing" to obj("domainStrategy" to "AsIs", "rules" to rules),
        )
    }

    fun outbound(s: CoreSpec, tag: String): JSONObject {
        val ob = when (s.proto) {
            "vless" -> obj(
                "protocol" to "vless",
                "settings" to obj("vnext" to JSONArray().put(obj(
                    "address" to s.address, "port" to s.port,
                    "users" to JSONArray().put(clean(obj("id" to s.uuid, "encryption" to "none", "flow" to s.flow))),
                ))),
            )
            "vmess" -> obj(
                "protocol" to "vmess",
                "settings" to obj("vnext" to JSONArray().put(obj(
                    "address" to s.address, "port" to s.port,
                    "users" to JSONArray().put(obj("id" to s.uuid, "alterId" to s.aid, "security" to s.cipher.ifEmpty { "auto" })),
                ))),
            )
            "trojan" -> obj(
                "protocol" to "trojan",
                "settings" to obj("servers" to JSONArray().put(obj("address" to s.address, "port" to s.port, "password" to s.password))),
            )
            "shadowsocks" -> obj(
                "protocol" to "shadowsocks",
                "settings" to obj("servers" to JSONArray().put(obj(
                    "address" to s.address, "port" to s.port, "method" to s.method, "password" to s.password,
                ))),
            )
            else -> throw IllegalArgumentException("Unsupported protocol ${s.proto}")
        }
        val stream = if (s.proto == "shadowsocks") JSONObject() else streamSettings(s)
        // Resolve the server's name with Xray's own DNS (IPv4).
        stream.put("sockopt", obj("domainStrategy" to "UseIPv4"))
        ob.put("streamSettings", stream)
        ob.put("tag", tag)
        return ob
    }

    private fun streamSettings(s: CoreSpec): JSONObject {
        var net = s.net.ifEmpty { "tcp" }
        if (net == "tcp") net = "raw"
        if (net == "splithttp") net = "xhttp"
        val st = obj("network" to net, "security" to s.security.ifEmpty { "none" })
        val alpn = if (s.alpn.isEmpty()) null else JSONArray(s.alpn)
        when (s.security) {
            "tls" -> st.put("tlsSettings", clean(obj(
                "serverName" to s.sni.ifEmpty { s.host.split(',')[0] },
                "fingerprint" to s.fp.ifEmpty { "chrome" },
                "alpn" to alpn,
                // "allowInsecure" was removed from Xray; certificate pinning replaced it.
                "pinnedPeerCertSha256" to s.pinnedSha256,
                "verifyPeerCertByName" to s.verifyName,
            )))
            "reality" -> st.put("realitySettings", clean(obj(
                "serverName" to s.sni,
                "fingerprint" to s.fp.ifEmpty { "chrome" },
                "publicKey" to s.pbk,
                "shortId" to s.sid,
                "spiderX" to s.spx,
            )))
        }
        when (net) {
            "ws" -> st.put("wsSettings", clean(obj("path" to s.path.ifEmpty { "/" }, "host" to s.host)))
            "grpc" -> st.put("grpcSettings", clean(obj("serviceName" to s.serviceName, "multiMode" to (s.mode == "multi"))))
            "xhttp" -> st.put("xhttpSettings", clean(obj(
                "host" to s.host, "path" to s.path.ifEmpty { "/" }, "mode" to s.mode.ifEmpty { "auto" }, "extra" to s.extra,
            )))
            "httpupgrade" -> st.put("httpupgradeSettings", clean(obj("path" to s.path.ifEmpty { "/" }, "host" to s.host)))
            "raw" -> if (s.headerType == "http") {
                val headers = JSONObject()
                if (s.host.isNotEmpty()) headers.put("Host", JSONArray(s.host.split(',')))
                st.put("rawSettings", obj("header" to obj(
                    "type" to "http",
                    "request" to obj("path" to JSONArray().put(s.path.ifEmpty { "/" }), "headers" to headers),
                )))
            }
        }
        return st
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in pairs) o.put(k, v ?: JSONObject.NULL)
        return o
    }

    /** Drops empty values, like the extension's clean(). */
    private fun clean(o: JSONObject): JSONObject {
        val out = JSONObject()
        for (k in o.keys()) {
            val v = o.opt(k)
            if (v == null || v == JSONObject.NULL || v == "" || (v is JSONArray && v.length() == 0)) continue
            out.put(k, v)
        }
        return out
    }
}
