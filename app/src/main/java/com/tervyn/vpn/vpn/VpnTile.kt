package com.tervyn.vpn.vpn

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.tervyn.vpn.core.Core
import com.tervyn.vpn.ui.MainActivity

/** Quick Settings tile: tap to connect / disconnect without opening the app. */
class VpnTile : TileService() {
    private val listener: () -> Unit = { refresh() }

    override fun onStartListening() {
        Core.addListener(listener)
        refresh()
    }

    override fun onStopListening() {
        Core.removeListener(listener)
    }

    override fun onClick() {
        val st = Core.state.value
        if (st.on || st.connecting) {
            TervynVpnService.disconnect()
        } else if (VpnService.prepare(this) == null) {
            TervynVpnService.connect(this)
        } else {
            // First time: Android has to ask for VPN permission inside the app.
            openApp()
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val st = Core.state.value
        tile.state = when {
            st.on -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                st.connecting -> "Connecting…"
                st.on -> st.status.label.ifEmpty { "On" }
                else -> "Off"
            }
        }
        tile.updateTile()
    }

    companion object {
        fun requestUpdate(ctx: Context) {
            runCatching { requestListeningState(ctx, ComponentName(ctx, VpnTile::class.java)) }
        }
    }
}
