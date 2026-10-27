package com.tervyn.vpn.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tervyn.vpn.core.AppState
import com.tervyn.vpn.core.Core
import com.tervyn.vpn.core.normalizeSite
import com.tervyn.vpn.vpn.TervynVpnService

class MainActivity : ComponentActivity() {

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) TervynVpnService.connect(this)
        else Toast.makeText(this, "Tervyn needs VPN permission to protect your connection.", Toast.LENGTH_LONG).show()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startVpn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { TervynTheme { App(onPower = ::onPower) } }
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        Core.ensureFree()   // find free servers in the background if the list is old
    }

    /** Someone shared a link to Tervyn: add that site to "Only my sites". */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("https?://\\S+").find(text)?.value ?: text
        val site = normalizeSite(url)
        if (site.isEmpty()) {
            Toast.makeText(this, "That doesn't look like a website link.", Toast.LENGTH_LONG).show()
        } else {
            val added = Core.addSites(listOf(site))
            Toast.makeText(this, if (added > 0) "Added $site to your sites" else "$site is already in your sites", Toast.LENGTH_LONG).show()
        }
        intent.action = null
    }

    private fun onPower() {
        val st = Core.state.value
        if (st.on || st.connecting) {
            TervynVpnService.disconnect()
            return
        }
        // Ask once for notifications (the status notification shows while connected).
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("askedNotif", false)
        ) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedNotif", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startVpn()
    }

    private fun startVpn() {
        val ask = VpnService.prepare(this)
        if (ask != null) vpnPermission.launch(ask) else TervynVpnService.connect(this)
    }
}

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val TABS = listOf(Tab("Home", TIcons.home), Tab("Servers", TIcons.servers), Tab("Sites", TIcons.globe), Tab("Settings", TIcons.gear))

@Composable
fun App(onPower: () -> Unit) {
    val st by Core.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        containerColor = C.bg2,
        topBar = { Header(st) },
        bottomBar = {
            NavigationBar(containerColor = C.bg2, tonalElevation = 0.dp) {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(t.icon, null, modifier = Modifier.size(22.dp)) },
                        label = { Text(t.label, fontSize = 12.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White, selectedTextColor = C.text,
                            indicatorColor = C.accentSoft, unselectedIconColor = C.muted, unselectedTextColor = C.muted,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().background(C.background).padding(pad)) {
            when (tab) {
                0 -> HomeScreen(st, onPower) { tab = it }
                1 -> ServersScreen(st) { tab = 0 }
                2 -> SitesScreen(st)
                else -> SettingsScreen(st)
            }
        }
    }
}

@Composable
private fun Header(st: AppState) {
    Row(
        Modifier.fillMaxWidth().background(C.bg1).statusBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(TIcons.logo, null, modifier = Modifier.size(30.dp))
            HSpace(9)
            Text("Tervyn", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            HSpace(3)
            Text("VPN", color = C.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                modifier = Modifier.padding(top = 5.dp))
        }
        val (label, color) = when {
            st.connecting -> "CONNECTING" to C.amber
            st.on -> "PROTECTED" to C.green
            else -> "OFF" to C.muted
        }
        val shape = RoundedCornerShape(99.dp)
        Row(
            Modifier.clip(shape).background(color.copy(alpha = 0.08f)).border(1.dp, color.copy(alpha = 0.35f), shape)
                .padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            HSpace(7)
            Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        }
    }
}
