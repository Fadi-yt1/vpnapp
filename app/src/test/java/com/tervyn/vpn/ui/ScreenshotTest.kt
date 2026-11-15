package com.tervyn.vpn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.tervyn.vpn.core.AppState
import com.tervyn.vpn.core.BlockLists
import com.tervyn.vpn.core.DnsConfig
import com.tervyn.vpn.core.DnsManager
import com.tervyn.vpn.core.DnsProfile
import com.tervyn.vpn.core.DnsStats
import com.tervyn.vpn.core.FreeStatus
import com.tervyn.vpn.core.History
import com.tervyn.vpn.core.IpInfo
import com.tervyn.vpn.core.Prefs
import com.tervyn.vpn.core.Server
import com.tervyn.vpn.core.Status
import com.tervyn.vpn.core.TestRun
import com.tervyn.vpn.core.Traffic
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.sin

/**
 * Renders every main screen in each theme so the design can be reviewed
 * without a device (CI publishes the images). The states below are test
 * fixtures for the screenshots only; the app itself only ever shows live data.
 */
@RunWith(Parameterized::class)
class ScreenshotTest(private val theme: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun themes() = listOf(Prefs.THEME_PURPLE, Prefs.THEME_DARK, Prefs.THEME_LIGHT)

        private val phone = DeviceConfig.PIXEL_5.copy(
            screenWidth = 720, screenHeight = 1560, xdpi = 320, ydpi = 320, density = Density.XHIGH,
        )
        private val tall = phone.copy(screenHeight = 4000)

        private val servers = listOf(
            Triple("DE", "socks5", 210), Triple("NL", "vless", 260), Triple("US", "wireguard", 340),
            Triple("JP", "trojan", 520), Triple("SG", "http", 610), Triple("FR", "socks5", 280),
            Triple("CA", "shadowsocks", 450), Triple("BR", "http", 900), Triple("GB", "vmess", 300),
        ).mapIndexed { i, (code, proto, ms) ->
            Server.make(proto, "203.0.113.${10 + i}", 1080 + i, code, source = "own", free = false)
                .copy(ms = ms, testedAt = 1, mbps = 80.0 / (i + 1), score = 100 - i)
        }

        private fun wave(n: Int, base: Long, amp: Long, k: Double) = List(n) { base + (amp * (1 + sin(it / k))).toLong() }

        val connected = AppState(
            prefs = Prefs(
                enabled = true, source = Prefs.SOURCE_OWN, favorites = listOf(servers[0].id, servers[2].id),
                recents = listOf(servers[1].id, servers[0].id),
                dns = DnsConfig(mode = DnsConfig.PRESET, preset = "cloudflare", filters = setOf(BlockLists.ADS, BlockLists.MALWARE)),
                dnsProfiles = listOf(DnsProfile("p1", "Home", DnsConfig(mode = DnsConfig.PRESET, preset = "cloudflare")), DnsProfile("p2", "Work", DnsConfig())),
                dnsProfile = "p1", dnsDefaultProfile = "p1", onboarded = true,
            ),
            status = Status(state = "on", label = "Germany · SOCKS5 203.0.113.10:1080", serverIds = listOf(servers[0].id),
                since = System.currentTimeMillis() - 3_725_000, ping = 42, protocol = "socks5"),
            own = servers,
            traffic = Traffic(down = 3_450_000, up = 420_000, totalDown = 812_000_000, totalUp = 64_000_000),
            history = History(wave(60, 1_200_000, 1_500_000, 4.0), wave(60, 150_000, 200_000, 3.0), List(40) { 38 + (it * 7) % 23 }),
            dnsStats = DnsStats(queries = 1284, blocked = 213, viaTunnel = 1022, direct = 0, encrypted = 1022, lastResolver = "cloudflare-dns.com (DoH) · through VPN", lastMs = 31,
                blockedByCategory = mapOf(BlockLists.ADS to 180L, BlockLists.MALWARE to 33L)),
            dnsTest = TestRun(false, System.currentTimeMillis(), listOf(
                DnsManager.TestItem("Resolver", true, "Cloudflare (DoH) · through VPN"),
                DnsManager.TestItem("IPv4 lookup (example.com)", true, "93.184.215.14 via cloudflare-dns.com (DoH)", 28),
                DnsManager.TestItem("IPv6 leak protection", true, "IPv6 traffic is captured and blocked"),
            )),
            ipInfo = IpInfo("203.0.113.10", "Frankfurt", "Hesse", "Germany", "DE", "Example Hosting", true, 0),
            lists = mapOf(BlockLists.ADS to BlockLists.Info(115_802, System.currentTimeMillis()), BlockLists.MALWARE to BlockLists.Info(185_809, System.currentTimeMillis())),
            systemKillSwitch = true to false,
            xrayVersion = "26.9.30",
        )

        val off = AppState(prefs = Prefs(onboarded = true), freeStatus = FreeStatus(state = "testing", progress = 46, working = 12))
    }

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = phone, theme = "android:Theme.Material.NoActionBar")

    @Composable
    private fun Shot(st: AppState, content: @Composable (Nav) -> Unit) {
        val nav = Nav({}, {}, {})
        TervynTheme(theme, systemDark = true, reducedMotion = true, env = GlassEnv(null, blur = false, reducedMotion = true, haptics = false)) {
            Box(Modifier.fillMaxSize()) {
                GlassBackground(on = st.on)
                content(nav)
            }
        }
    }

    @Composable
    private fun WithTabs(st: AppState, tab: Int, content: @Composable (Nav) -> Unit) = Shot(st) { nav ->
        content(nav)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            GlassNavigationBar(listOf(NavItem("Home", TIcons.home), NavItem("Locations", TIcons.globe), NavItem("Security", TIcons.shield), NavItem("Settings", TIcons.gear)), tab, {})
        }
    }

    @Test fun home_connected() = paparazzi.snapshot { WithTabs(connected, 0) { HomeScreen(connected, it) {} } }
    @Test fun home_off() = paparazzi.snapshot { WithTabs(off, 0) { HomeScreen(off, it) {} } }

    @Test fun home_full() {
        paparazzi.unsafeUpdateConfig(deviceConfig = tall)
        paparazzi.snapshot { WithTabs(connected, 0) { HomeScreen(connected, it) {} } }
    }

    @Test fun locations() {
        paparazzi.unsafeUpdateConfig(deviceConfig = tall)
        paparazzi.snapshot { WithTabs(connected, 1) { LocationsScreen(connected, it) } }
    }

    @Test fun security() {
        paparazzi.unsafeUpdateConfig(deviceConfig = tall)
        paparazzi.snapshot { WithTabs(connected, 2) { SecurityScreen(connected, it) } }
    }

    @Test fun settings() {
        paparazzi.unsafeUpdateConfig(deviceConfig = tall)
        paparazzi.snapshot { WithTabs(connected, 3) { SettingsScreen(connected, it) } }
    }

    @Test fun dns() {
        paparazzi.unsafeUpdateConfig(deviceConfig = tall)
        paparazzi.snapshot { Shot(connected) { DnsScreen(connected, it) } }
    }

    @Test fun monitor() {
        paparazzi.unsafeUpdateConfig(deviceConfig = phone.copy(screenHeight = 2600))
        paparazzi.snapshot { Shot(connected) { MonitorScreen(connected, it) } }
    }

    @Test fun appearance() {
        paparazzi.unsafeUpdateConfig(deviceConfig = phone.copy(screenHeight = 2400))
        paparazzi.snapshot { Shot(connected.copy(prefs = connected.prefs.copy(theme = theme))) { AppearanceScreen(connected.copy(prefs = connected.prefs.copy(theme = theme)), it) } }
    }

    @Test fun onboarding() = paparazzi.snapshot { Shot(off) { OnboardingScreen(off, {}) {} } }
}
