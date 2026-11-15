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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tervyn.vpn.core.AppState
import com.tervyn.vpn.core.Core
import com.tervyn.vpn.core.normalizeSite
import com.tervyn.vpn.vpn.TervynVpnService
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

class MainActivity : ComponentActivity() {

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) TervynVpnService.connect(this)
        else Toast.makeText(this, "Tervyn needs VPN permission to protect your connection.", Toast.LENGTH_LONG).show()
    }

    /** Onboarding / Permissions: ask for VPN permission without connecting yet. */
    private val vpnPermissionOnly = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { permTick++ }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permTick++
        startVpn()
    }

    private val notificationPermissionOnly = registerForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }

    /** Bumped when a permission may have changed, so the screens re-check. */
    private var permTick by mutableIntStateOf(0)

    private fun perms(): Perms {
        val notif = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val battery = Build.VERSION.SDK_INT < 23 || getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        return Perms(
            vpn = VpnService.prepare(this) == null, notifications = notif, battery = battery,
            requestVpn = ::askVpnPermission,
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= 33) notificationPermissionOnly.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            requestBattery = {
                runCatching {
                    @android.annotation.SuppressLint("BatteryLife")
                    val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))
                    startActivity(i)
                }.onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
            },
            openVpnSettings = { runCatching { startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS)) } },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val first = Core.state.value
        C.palette = Palettes.forTheme(first.prefs.theme, resources.configuration.isNightModeActiveCompat())
        setContent {
            val st by Core.state.collectAsStateWithLifecycle()
            val haze = remember { HazeState() }
            val p = st.prefs
            TervynTheme(p.theme, isSystemInDarkTheme(), p.reducedMotion, GlassEnv(haze, p.blur, p.reducedMotion, p.haptics)) {
                val dark = C.isDark
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                val perms = remember(permTick) { perms() }
                androidx.compose.runtime.CompositionLocalProvider(LocalPerms provides perms) {
                    App(st, onPower = ::onPower, askVpnPermission = ::askVpnPermission)
                }
            }
        }
        handleShare(intent)
        // Auto-connect when the app opens (Security → Auto connect).
        if (savedInstanceState == null && first.prefs.autoConnectLaunch && first.prefs.onboarded && !first.on && !first.connecting &&
            VpnService.prepare(this) == null
        ) TervynVpnService.connect(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        permTick++
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

    private fun askVpnPermission() {
        VpnService.prepare(this)?.let { vpnPermissionOnly.launch(it) }
    }

    private fun onPower() {
        val st = Core.state.value
        if (st.on || st.connecting || st.failed) {
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

private fun android.content.res.Configuration.isNightModeActiveCompat(): Boolean =
    (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

/** Moving between screens. Sub-pages stack on top of the four tabs. */
class Nav(
    val open: (String) -> Unit,
    val back: () -> Unit,
    val tab: (Int) -> Unit,
)

object Routes {
    const val DNS = "dns"
    const val MONITOR = "monitor"
    const val SPLIT = "split"
    const val APPEARANCE = "appearance"
    const val SOURCES = "sources"
    const val SITES = "sites"
    const val DIAGNOSTICS = "diagnostics"
    const val CONNECTION = "connection"
    const val AUTOCONNECT = "autoconnect"
    const val ABOUT = "about"
    const val HELP = "help"
    const val PRIVACY = "privacy"
    const val PERMISSIONS = "permissions"
}

private val TABS = listOf(
    NavItem("Home", TIcons.home), NavItem("Locations", TIcons.globe),
    NavItem("Security", TIcons.shield), NavItem("Settings", TIcons.gear),
)

@Composable
fun App(st: AppState, onPower: () -> Unit, askVpnPermission: () -> Unit) {
    val env = LocalGlass.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var stackStr by rememberSaveable { mutableStateOf("") }
    val stack = stackStr.split('/').filter { it.isNotEmpty() }
    val nav = Nav(
        open = { r -> stackStr = (stackStr.split('/').filter { it.isNotEmpty() } + r).joinToString("/") },
        back = { stackStr = stackStr.split('/').filter { it.isNotEmpty() }.dropLast(1).joinToString("/") },
        tab = { t -> tab = t; stackStr = "" },
    )
    BackHandler(enabled = stack.isNotEmpty()) { nav.back() }
    BackHandler(enabled = stack.isEmpty() && tab != 0) { tab = 0 }

    Box(Modifier.fillMaxSize()) {
        GlassBackground(on = st.on, modifier = if (env.haze != null) Modifier.hazeSource(env.haze) else Modifier)
        if (!st.prefs.onboarded) {
            OnboardingScreen(st, askVpnPermission) { Core.setOnboarded() }
        } else {
        val route = stack.lastOrNull() ?: "tab$tab"
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                val forward = !targetState.startsWith("tab") &&
                    (initialState.startsWith("tab") || stack.indexOf(targetState) > stack.indexOf(initialState))
                when {
                    env.reducedMotion -> fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    targetState.startsWith("tab") && initialState.startsWith("tab") -> fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                    forward -> (slideInHorizontally(tween(280)) { it / 4 } + fadeIn(tween(220))) togetherWith fadeOut(tween(160))
                    else -> (slideInHorizontally(tween(280)) { -it / 4 } + fadeIn(tween(220))) togetherWith fadeOut(tween(160))
                }
            },
            label = "page",
            modifier = Modifier.fillMaxSize().imePadding(),
        ) { r ->
            when (r) {
                "tab0" -> HomeScreen(st, nav, onPower)
                "tab1" -> LocationsScreen(st, nav)
                "tab2" -> SecurityScreen(st, nav)
                "tab3" -> SettingsScreen(st, nav)
                Routes.DNS -> DnsScreen(st, nav)
                Routes.MONITOR -> MonitorScreen(st, nav)
                Routes.SPLIT -> SplitTunnelScreen(st, nav)
                Routes.APPEARANCE -> AppearanceScreen(st, nav)
                Routes.SOURCES -> SourcesScreen(st, nav)
                Routes.SITES -> SitesScreen(st, nav)
                Routes.DIAGNOSTICS -> DiagnosticsScreen(st, nav)
                Routes.CONNECTION -> ConnectionScreen(st, nav)
                Routes.AUTOCONNECT -> AutoConnectScreen(st, nav)
                Routes.ABOUT, Routes.HELP, Routes.PRIVACY -> InfoScreen(r, nav)
                Routes.PERMISSIONS -> PermissionsScreen(st, nav)
                else -> HomeScreen(st, nav, onPower)
            }
        }
        if (stack.isEmpty()) {
            GlassNavigationBar(TABS, tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
        }
        }
    }
}

/**
 * Standard scrolling page. [sub]: a page opened on top of a tab (back button,
 * no tab bar); otherwise leaves room for the floating tab bar.
 */
@Composable
fun Page(
    title: String?,
    nav: Nav?,
    sub: Boolean,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(horizontal = 18.dp).padding(top = 6.dp)
            .then(if (sub) Modifier.navigationBarsPadding() else Modifier),
        verticalArrangement = Arrangement.Top,
    ) {
        if (title != null) PageHeader(title, if (sub && nav != null) nav.back else null, subtitle, trailing)
        content()
        if (sub) VSpace(24) else BottomBarSpace()
    }
}

@Composable
fun FullWidth(content: @Composable ColumnScope.() -> Unit) = Column(Modifier.fillMaxWidth(), content = content)
