package com.tervyn.vpn.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.tervyn.vpn.core.AppState
import com.tervyn.vpn.core.Core
import com.tervyn.vpn.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap?, val system: Boolean)

/** Apps with a launcher icon (what people think of as "their apps"), sorted by name. */
fun loadApps(ctx: Context): List<AppInfo> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val px = (40 * ctx.resources.displayMetrics.density).toInt()
    return pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != ctx.packageName }
        .map { ai ->
            AppInfo(
                ai.packageName,
                runCatching { ai.loadLabel(pm).toString() }.getOrDefault(ai.packageName),
                runCatching { ai.loadIcon(pm).toBitmap(px, px).asImageBitmap() }.getOrNull(),
                (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }
        .sortedBy { it.label.lowercase() }
}

@Composable
fun SplitTunnelScreen(st: AppState, nav: Nav) {
    val ctx = LocalContext.current
    val p = st.prefs
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { runCatching { loadApps(ctx) }.getOrDefault(emptyList()) } }
    val selected = p.splitApps.toSet()
    val q = query.trim().lowercase()
    val shown = apps.orEmpty().filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }
        .sortedByDescending { it.pkg in selected }

    fun toggle(pkg: String, on: Boolean) {
        val list = if (on) p.splitApps + pkg else p.splitApps - pkg
        val mode = if (p.splitMode == Prefs.SPLIT_ALL && list.isNotEmpty()) Prefs.SPLIT_EXCLUDE else p.splitMode
        Core.setSplit(mode, list)
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { PageHeader("Split tunneling", nav.back, "Choose which apps use the VPN") }
        item {
            GlassTabBar(
                listOf(Prefs.SPLIT_ALL to "All apps", Prefs.SPLIT_EXCLUDE to "Bypass", Prefs.SPLIT_INCLUDE to "Only selected"),
                p.splitMode, { Core.setSplit(it, p.splitApps) },
            )
        }
        item {
            Muted(
                when (p.splitMode) {
                    Prefs.SPLIT_EXCLUDE -> "Selected apps skip the VPN and use your normal connection. Everything else is protected."
                    Prefs.SPLIT_INCLUDE -> "Only the selected apps use the VPN. Everything else uses your normal connection."
                    else -> "Every app uses the VPN. Pick apps below to let them bypass it."
                } + if (st.on) " Changes apply right away." else "",
                Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        if (p.splitMode == Prefs.SPLIT_INCLUDE && p.splitApps.isEmpty()) item {
            Alert("No apps selected: with \"Only selected\" nothing will use the VPN until you pick at least one app.", warn = true)
        }
        item { GlassSearchBar(query, { query = it }, "Search apps") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Select all", Modifier.weight(1f), ButtonStyle.Secondary) {
                    val all = (p.splitApps + shown.map { it.pkg }).distinct()
                    Core.setSplit(if (p.splitMode == Prefs.SPLIT_ALL) Prefs.SPLIT_EXCLUDE else p.splitMode, all)
                }
                GlassButton("Clear", Modifier.weight(1f), ButtonStyle.Secondary, enabled = p.splitApps.isNotEmpty()) {
                    Core.setSplit(p.splitMode, p.splitApps - shown.map { it.pkg }.toSet())
                }
            }
        }
        if (apps == null) item {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner(28.dp) }
        }
        items(shown, key = { it.pkg }) { app ->
            val on = app.pkg in selected
            GlassContainer(Modifier.fillMaxWidth(), ShapeInner, selected = on, padding = PaddingValues(0.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).toggleable(on, role = Role.Checkbox) { toggle(app.pkg, it) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (app.icon != null) Image(app.icon, null, Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)))
                    else IconBubble(TIcons.apps, size = 38.dp)
                    HSpace(12)
                    Column(Modifier.weight(1f)) {
                        Text(app.label, color = C.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted(app.pkg + if (app.system) " · system" else "")
                    }
                    CheckMark(on)
                }
            }
        }
        if (apps != null && shown.isEmpty()) item {
            Muted(if (q.isEmpty()) "No apps found." else "No apps match \"$query\".", Modifier.fillMaxWidth().padding(16.dp), center = true)
        }
    }
}

@Composable
fun CheckMark(on: Boolean) {
    Box(
        Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
            .then(if (on) Modifier.glass(RoundedCornerShape(8.dp), selected = true) else Modifier.glass(RoundedCornerShape(8.dp))),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(TIcons.check, null, tint = C.text, modifier = Modifier.size(16.dp))
    }
}
