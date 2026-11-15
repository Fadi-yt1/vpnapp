package com.tervyn.vpn.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tervyn.vpn.core.AppState

/** What Tervyn needs from Android, and how to ask for it (provided by MainActivity). */
class Perms(
    val vpn: Boolean,
    val notifications: Boolean,
    val battery: Boolean,
    val requestVpn: () -> Unit,
    val requestNotifications: () -> Unit,
    val requestBattery: () -> Unit,
    val openVpnSettings: () -> Unit,
) {
    /** Needed for the VPN to work at all, or to keep it running reliably. */
    val missing: Int get() = listOf(vpn, notifications, battery).count { !it }
}

val LocalPerms = staticCompositionLocalOf { Perms(true, true, true, {}, {}, {}, {}) }

private class PermItem(val icon: ImageVector, val title: String, val why: String, val granted: Boolean, val request: () -> Unit)

private fun permItems(p: Perms) = listOf(
    PermItem(TIcons.shield, "VPN connection", "Required: lets Tervyn route your apps' traffic through the encrypted tunnel.", p.vpn, p.requestVpn),
    PermItem(TIcons.info, "Notifications", if (Build.VERSION.SDK_INT >= 33) "Shows the connection status and live speed, and keeps the VPN running in the background."
        else "Allowed on this Android version.", p.notifications, p.requestNotifications),
    PermItem(TIcons.bolt, "Run in background", "Stops Android's battery saver from cutting the VPN when the screen is off.", p.battery, p.requestBattery),
)

@Composable
fun PermissionRows(p: Perms) {
    GroupCard {
        permItems(p).forEachIndexed { i, item ->
            if (i > 0) RowDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBubble(item.icon, if (item.granted) C.green else C.amber, 36.dp)
                HSpace(12)
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
                    Text(item.why, color = C.muted, fontSize = 12.sp)
                }
                HSpace(8)
                if (item.granted) GlassStatusBadge("ON", C.green)
                else GlassButton("Allow", style = ButtonStyle.Primary, onClick = item.request)
            }
        }
    }
}

/** Home: shown only while something is missing. */
@Composable
fun PermissionBanner(nav: Nav) {
    val p = LocalPerms.current
    if (p.missing == 0) return
    VSpace(12)
    CardRow(onClick = { nav.open(Routes.PERMISSIONS) }) {
        IconBubble(TIcons.alert, C.amber)
        Column(Modifier.weight(1f)) {
            Text(if (!p.vpn) "Allow the VPN to connect" else "Finish setup: ${p.missing} permission${if (p.missing == 1) "" else "s"}",
                color = C.text, fontWeight = FontWeight.SemiBold)
            Muted(if (!p.vpn) "Android needs your OK once before Tervyn can protect you." else "Keeps the VPN connected reliably in the background.")
        }
    }
}

@Composable
fun PermissionsScreen(st: AppState, nav: Nav) {
    val p = LocalPerms.current
    Page("Permissions", nav, sub = true, subtitle = "What Tervyn needs from Android") {
        PermissionRows(p)
        SectionLabel("Always-on VPN")
        GlassCard(padding = PaddingValues(18.dp)) {
            Text("Keep Tervyn connected", color = C.text, fontWeight = FontWeight.SemiBold)
            VSpace(4)
            Muted("In Android's VPN settings, tap the gear next to Tervyn VPN and turn on \"Always-on VPN\". Add \"Block connections without VPN\" for a system-wide kill switch." +
                (st.systemKillSwitch?.let { (on, block) -> "\nNow: Always-on ${if (on) "on" else "off"}, block without VPN ${if (block) "on" else "off"}." } ?: ""))
            VSpace(12)
            GlassButton("Open VPN settings", Modifier.fillMaxWidth(), ButtonStyle.Secondary, onClick = p.openVpnSettings)
        }
        VSpace(10)
        Muted("Tervyn never asks for your location, contacts, files or camera.", Modifier.padding(horizontal = 6.dp))
    }
}
