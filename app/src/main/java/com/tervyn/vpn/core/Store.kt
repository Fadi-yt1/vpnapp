package com.tervyn.vpn.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Saves settings and server lists on the phone (nothing leaves the device). */
class Store(context: Context) {
    private val sp = context.getSharedPreferences("tervyn", Context.MODE_PRIVATE)

    fun prefs(): Prefs = Prefs.fromJson(obj("prefs"))
    fun savePrefs(p: Prefs) = put("prefs", p.toJson().toString())

    fun freeServers(): List<Server> = servers("freeServers")
    fun saveFreeServers(l: List<Server>) = put("freeServers", JSONArray(l.map { it.toJson() }).toString())

    fun ownServers(): List<Server> = servers("ownServers")
    fun saveOwnServers(l: List<Server>) = put("ownServers", JSONArray(l.map { it.toJson() }).toString())

    fun sources(): List<Source> = arr("sources")?.let { a -> List(a.length()) { Source.fromJson(a.getJSONObject(it)) } } ?: emptyList()
    fun saveSources(l: List<Source>) = put("sources", JSONArray(l.map { it.toJson() }).toString())

    fun proxioServers(): List<Server> = servers("proxioServers")
    fun saveProxioServers(l: List<Server>) = put("proxioServers", JSONArray(l.map { it.toJson() }).toString())

    fun proxioConfig(): Proxio.Config = Proxio.Config.fromJson(obj("proxio"))
    fun saveProxioConfig(c: Proxio.Config) = put("proxio", c.toJson().toString())

    fun proxioStatus(): ProxioStatus = ProxioStatus.fromJson(obj("proxioStatus"))
    fun saveProxioStatus(s: ProxioStatus) = put("proxioStatus", s.toJson().toString())

    fun removeProxio() = sp.edit().remove("proxio").remove("proxioServers").remove("proxioStatus").apply()

    fun customServers(): List<Server> = servers("customServers")
    fun saveCustomServers(l: List<Server>) = put("customServers", JSONArray(l.map { it.toJson() }).toString())
    fun customCode(): String = sp.getString("customCode", "") ?: ""
    fun saveCustomCode(code: String) = put("customCode", code)
    fun customStatus(): ProxioStatus = ProxioStatus.fromJson(obj("customStatus"))
    fun saveCustomStatus(s: ProxioStatus) = put("customStatus", s.toJson().toString())
    fun removeCustom() = sp.edit().remove("customServers").remove("customCode").remove("customStatus").apply()

    fun freeStatus(): FreeStatus = FreeStatus.fromJson(obj("freeStatus"))
    fun saveFreeStatus(f: FreeStatus) = put("freeStatus", f.toJson().toString())

    private fun servers(key: String): List<Server> =
        arr(key)?.let { a -> (0 until a.length()).mapNotNull { runCatching { Server.fromJson(a.getJSONObject(it)) }.getOrNull() } }
            ?: emptyList()

    private fun obj(key: String): JSONObject? = sp.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    private fun arr(key: String): JSONArray? = sp.getString(key, null)?.let { runCatching { JSONArray(it) }.getOrNull() }
    private fun put(key: String, value: String) = sp.edit().putString(key, value).apply()
}
