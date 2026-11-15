package com.tervyn.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tervyn.vpn.R
import com.tervyn.vpn.core.Core
import com.tervyn.vpn.core.Prefs
import com.tervyn.vpn.core.formatBytes
import com.tervyn.vpn.core.formatRate
import com.tervyn.vpn.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress

/**
 * The Android VPN. It captures traffic from all apps (or only the listed
 * sites) and hands it to [TunEngine], which sends it through the free servers.
 * Tervyn's own traffic (server tests, list downloads) stays outside the VPN.
 */
class TervynVpnService : VpnService() {

    companion object {
        private const val TAG = "TervynVpn"
        const val ACTION_CONNECT = "com.tervyn.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.tervyn.vpn.DISCONNECT"
        private const val CHANNEL = "vpn"
        private const val NOTIF_ID = 1

        fun connect(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TervynVpnService::class.java).setAction(ACTION_CONNECT))
        }

        /** Same process, so we can stop directly (the service shuts itself down). */
        fun disconnect() = Core.disconnect()
    }

    private val tunLock = Any()
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var engine: TunEngine? = null
    private val listener: () -> Unit = { updateNotification() }

    override fun onCreate() {
        super.onCreate()
        Core.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            Core.disconnect()
            return START_NOT_STICKY
        }
        // ACTION_CONNECT, "always-on VPN" (android.net.VpnService) or a restart after Android stopped us.
        if (intent == null && !Core.state.value.prefs.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        Core.connect(this)
        return START_STICKY
    }

    /** (Re)builds the VPN interface and starts forwarding packets. Called by Core. */
    fun startTunnel(fullMode: Boolean, router: Router) {
        synchronized(tunLock) {
            val prefs = Core.state.value.prefs
            val b = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(1500)
                .addAddress(com.tervyn.vpn.vpn.Ip.str(FakeDns.TUN_IP), 32)
                .addDnsServer(com.tervyn.vpn.vpn.Ip.str(FakeDns.DNS_IP))
                .setBlocking(true)
                .setConfigureIntent(openAppIntent())
            // Split tunneling (Android per-app VPN). Tervyn's own traffic always goes around the VPN.
            val apps = prefs.splitApps.filter { it != packageName }
            if (prefs.splitMode == Prefs.SPLIT_INCLUDE && apps.isNotEmpty()) {
                for (a in apps) runCatching { b.addAllowedApplication(a) }   // apps not listed (and Tervyn) bypass
            } else {
                b.addDisallowedApplication(packageName)
                if (prefs.splitMode == Prefs.SPLIT_EXCLUDE) for (a in apps) runCatching { b.addDisallowedApplication(a) }
            }
            if (fullMode) {
                b.addRoute("0.0.0.0", 0)
                if (prefs.ipv6Block) {
                    // Capture IPv6 too, so nothing leaks around the VPN (it's dropped; DNS gives IPv4 only).
                    b.addAddress("fd66:7e7e:7e7e::2", 128)
                    b.addRoute("::", 0)
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    // Keep home network devices (printers, TVs, router) reachable.
                    for ((ip, len) in listOf("10.0.0.0" to 8, "172.16.0.0" to 12, "192.168.0.0" to 16, "169.254.0.0" to 16)) {
                        b.excludeRoute(IpPrefix(InetAddress.getByName(ip), len))
                    }
                }
            } else {
                // Only the stand-in addresses of your sites (and our DNS) enter the VPN.
                b.addRoute(com.tervyn.vpn.vpn.Ip.str(FakeDns.NET), 15)
            }
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)

            val newTun = b.establish() ?: throw IllegalStateException("VPN permission was removed. Open Tervyn and tap connect again.")
            stopEngineLocked()
            tun = newTun
            engine = TunEngine(
                FileInputStream(newTun.fileDescriptor), FileOutputStream(newTun.fileDescriptor),
                router, Core.fakeDns,
            ) { Log.d(TAG, it) }.also { it.start() }
        }
        updateNotification()
    }

    /** Android's own kill switch: (always-on VPN, block connections without VPN). Android 10+. */
    fun systemKillSwitch(): Pair<Boolean, Boolean>? =
        if (Build.VERSION.SDK_INT >= 29) runCatching { isAlwaysOn to isLockdownEnabled }.getOrNull() else null

    /** (download, upload) bytes through the VPN so far, or null when the tunnel isn't running. */
    fun trafficBytes(): Pair<Long, Long>? = engine?.let { it.bytesDown.get() to it.bytesUp.get() }

    fun stopTunnel() {
        synchronized(tunLock) { stopEngineLocked() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopEngineLocked() {
        engine?.stop()
        engine = null
        runCatching { tun?.close() }
        tun = null
    }

    override fun onRevoke() {
        // Another VPN app took over, or the user turned us off in Android settings.
        Core.disconnect()
    }

    override fun onDestroy() {
        Core.removeListener(listener)
        synchronized(tunLock) { stopEngineLocked() }
        Core.serviceDestroyed(this)
        super.onDestroy()
    }

    // ---------- notification ----------
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "VPN status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows while Tervyn VPN is connected"
                    setShowBadge(false)
                },
            )
        }
        val st = Core.state.value
        val title = when {
            st.reconnecting -> "Reconnecting…"
            st.failed -> if (st.status.blocking) "Kill switch on · traffic blocked" else "Connection failed"
            st.on -> if (st.prefs.targeted) "Your sites are protected" else "Protected connection"
            else -> "Connecting…"
        }
        val showSpeed = st.on && st.prefs.speedInNotification
        val text = when {
            // Live speed, updated every second.
            st.failed -> "Trying again shortly… tap Disconnect to stop"
            showSpeed -> "↓ ${formatRate(st.traffic.down)}   ↑ ${formatRate(st.traffic.up)}" + (st.status.ping?.let { "   ·  $it ms" } ?: "")
            st.on -> st.status.label + if (st.prefs.targeted) " · ${st.prefs.sites.size} sites" else ""
            st.freeStatus.state == "testing" -> "Finding fast servers… ${st.freeStatus.progress}%"
            else -> "Finding fast servers…"
        }
        val sub = if (showSpeed) st.status.label + " · " + formatBytes(st.traffic.totalDown + st.traffic.totalUp) + " used" else null
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TervynVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(sub)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setColor(0xFF3B82F6.toInt())
            .setContentIntent(openAppIntent())
            .addAction(0, "Disconnect", stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private var lastShown = ""
    private var lastSpeed = ""
    private var lastShownAt = 0L

    private fun updateNotification() {
        val st = Core.state.value
        if (st.status.state == "off") return
        // Android drops updates that come too fast, so only post real changes, at most once a second.
        val base = "${st.status.state}|${st.status.label}|${st.freeStatus.state}|${st.freeStatus.progress}|${st.prefs.sites.size}|${st.prefs.speedInNotification}"
        val speed = if (st.prefs.speedInNotification) "${st.traffic.down}|${st.traffic.up}" else ""
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (base == lastShown && speed == lastSpeed) return
            // Status changes show at once; speed-only changes at most about once a second.
            if (base == lastShown && now - lastShownAt < 900) return
            lastShown = base
            lastSpeed = speed
            lastShownAt = now
        }
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) }
    }
}
