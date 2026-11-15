package com.tervyn.vpn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tervyn.vpn.core.Core

/** Auto-connect when the phone starts (Settings → Auto-connect → When the phone starts). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val p = Core.state.value.prefs
        if (p.autoConnectBoot || (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED && p.enabled)) Core.startFromBackground()
    }
}
