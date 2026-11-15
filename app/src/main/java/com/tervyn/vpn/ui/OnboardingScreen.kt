package com.tervyn.vpn.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tervyn.vpn.R
import com.tervyn.vpn.core.AppState
import com.tervyn.vpn.core.Core
import kotlinx.coroutines.launch

private class Slide(val title: String, val body: String, val icon: ImageVector?)

private val SLIDES = listOf(
    Slide("Welcome to Tervyn", "A private VPN with no account, no ads and no tracking. Everything stays on your phone.", null),
    Slide("One tap to protect", "Tap the orb to encrypt your connection. The kill switch keeps apps blocked if the server drops.", TIcons.power),
    Slide("Smart Connect", "Servers are tested for ping and speed, and Tervyn moves you to the fastest one automatically.", TIcons.sparkle),
    Slide("Private DNS & blocking", "Encrypted lookups that stay inside the tunnel, plus blocking for ads, trackers, malware and phishing.", TIcons.dns),
    Slide("Make it yours", "Pick a glass style. You can change it any time in Settings → Appearance.", TIcons.palette),
    Slide("A few permissions", "Allow the VPN (required), notifications for the connection status, and background running so Android doesn't cut the VPN.", TIcons.shield),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(st: AppState, askVpnPermission: () -> Unit, onDone: () -> Unit) {
    val pager = rememberPagerState { SLIDES.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == SLIDES.lastIndex

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (!last) GlassButton("Skip", style = ButtonStyle.Secondary) { onDone() }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
            val s = SLIDES[i]
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                when (i) {
                    0 -> Image(
                        painterResource(R.drawable.logo_full), "Tervyn logo",
                        Modifier.size(132.dp).clip(RoundedCornerShape(36.dp)).border(1.dp, C.glassBorder, RoundedCornerShape(36.dp)),
                    )
                    1 -> GlassConnectionOrb(OrbState.On, {}, size = 230.dp)
                    4 -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        for (pal in Palettes.all) {
                            val chosen = st.prefs.theme == pal.id
                            Column(
                                Modifier.weight(1f).clip(ShapeInner).selectable(chosen, role = Role.RadioButton) { Core.setTheme(pal.id) }.padding(3.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    if (chosen) Modifier.border(2.dp, C.accent, RoundedCornerShape(20.dp)).padding(3.dp) else Modifier.padding(3.dp),
                                ) { ThemePreview(pal, Modifier.fillMaxWidth()) }
                                VSpace(6)
                                Text(pal.name.removeSuffix(" Glass").replace("Deep ", ""), color = if (chosen) C.text else C.muted, fontSize = 12.sp,
                                    fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium)
                            }
                        }
                    }
                    else -> GlassContainer(Modifier.size(150.dp), androidx.compose.foundation.shape.CircleShape) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(s.icon ?: TIcons.shield, null, tint = C.accentLight, modifier = Modifier.size(64.dp))
                        }
                    }
                }
                VSpace(36)
                Text(s.title, color = C.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = Fonts.display, textAlign = TextAlign.Center)
                VSpace(12)
                Text(s.body, color = C.muted, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
        // Page dots
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center) {
            for (i in SLIDES.indices) {
                val w by animateDpAsState(if (i == pager.currentPage) 24.dp else 8.dp, label = "dot")
                Box(Modifier.padding(horizontal = 4.dp).width(w).height(8.dp).clip(ShapePill)
                    .background(if (i == pager.currentPage) C.accent else C.track))
            }
        }
        if (last) {
            PermissionRows(LocalPerms.current)
            VSpace(12)
            GlassButton("Start using Tervyn", Modifier.fillMaxWidth(), if (LocalPerms.current.vpn) ButtonStyle.Primary else ButtonStyle.Secondary) { onDone() }
        } else {
            GlassButton("Continue", Modifier.fillMaxWidth()) { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
        }
    }
}
