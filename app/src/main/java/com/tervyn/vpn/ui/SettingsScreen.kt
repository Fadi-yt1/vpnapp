package com.tervyn.vpn.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tervyn.vpn.BuildConfig
import com.tervyn.vpn.core.AppState
import com.tervyn.vpn.core.Core

@Composable
fun SettingsScreen(st: AppState) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 4.dp)) {
        SectionLabel("Backup servers kept ready")
        Segmented(listOf("2", "3", "5", "8").map { it to "$it servers" }, st.prefs.autoPool.toString()) {
            Core.setAutoPool(it.toInt())
        }
        VSpace(6)
        Muted("Auto mode uses the fastest server and switches to a backup instantly if it stops working.")

        SectionLabel("Preferred country")
        CountryPicker(st.prefs.country, countryList(st), "Any country (fastest)", Core::setCountry)

        SectionLabel("Always-on VPN")
        Card {
            Text("Connect automatically and block the internet when the VPN is off", color = C.text, fontWeight = FontWeight.SemiBold)
            VSpace(4)
            Muted("In Android's VPN settings, tap the gear next to Tervyn VPN and turn on \"Always-on VPN\" (and optionally \"Block connections without VPN\").")
            VSpace(10)
            GhostButton("Open VPN settings", Modifier.fillMaxWidth()) {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
            }
        }

        SectionLabel("Quick Settings tile")
        Card {
            Muted("Swipe down twice, tap the pencil (edit) and drag \"Tervyn VPN\" into your tiles to connect with one tap.")
        }

        SectionLabel("Privacy and safety")
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Muted("• No accounts, ads or tracking. Your settings stay on your phone.")
                Muted("• Free public servers are run by unknown people. Secure (https) sites stay encrypted end to end, and Tervyn only keeps servers that pass a secure-site check.")
                Muted("• Avoid banking and other sensitive logins through free servers. \"Only my sites\" mode is the safest way to use them.")
                Muted("• Video calls and some games use UDP, which free servers can't carry. They fall back to TCP or use your normal connection in \"Only my sites\" mode.")
            }
        }

        VSpace(16)
        Text("Tervyn VPN ${BuildConfig.VERSION_NAME}", color = C.muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
        VSpace(20)
    }
}
