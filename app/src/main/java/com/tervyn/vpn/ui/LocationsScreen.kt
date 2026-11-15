package com.tervyn.vpn.ui

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tervyn.vpn.core.AppState
import com.tervyn.vpn.core.Core
import com.tervyn.vpn.core.Prefs
import com.tervyn.vpn.core.Server
import com.tervyn.vpn.core.countryName
import java.text.DateFormat
import java.util.Date
import kotlin.math.hypot

/** The dotted world map and country positions, loaded once from the app's assets. */
object WorldMap {
    class Data(val cols: Int, val rows: Int, val latTop: Float, val latBottom: Float, val dots: List<Pair<Int, Int>>, val centroids: Map<String, Pair<Float, Float>>)

    @Volatile private var cached: Data? = null

    fun load(ctx: Context): Data {
        cached?.let { return it }
        val lines = ctx.assets.open("worldmap.txt").bufferedReader().readLines()
        val header = lines.first()
        val lat = Regex("latitude (-?[\\d.]+)\\.\\.(-?[\\d.]+)").find(header)
        val top = lat?.groupValues?.get(1)?.toFloatOrNull() ?: 78f
        val bottom = lat?.groupValues?.get(2)?.toFloatOrNull() ?: -58f
        val grid = lines.drop(1).filter { it.isNotBlank() }
        val dots = ArrayList<Pair<Int, Int>>()
        grid.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> if (ch == '1') dots += x to y } }
        val cents = ctx.assets.open("country_centroids.csv").bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { l ->
                val p = l.split(',')
                val la = p.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null
                val lo = p.getOrNull(2)?.toFloatOrNull() ?: return@mapNotNull null
                p[0].trim().uppercase() to (la to lo)
            }.toMap()
        return Data(grid.maxOf { it.length }, grid.size, top, bottom, dots, cents).also { cached = it }
    }
}

/**
 * Interactive dotted world map. Countries with working servers glow; the
 * country in use pulses. Tap near a marker to pick that country.
 */
@Composable
fun WorldMapView(
    countries: Map<String, Int>,        // code -> working servers
    highlight: String?,                 // country in use / selected
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val data = remember { runCatching { WorldMap.load(ctx) }.getOrNull() } ?: return
    val reduced = LocalGlass.current.reducedMotion
    val t = rememberInfiniteTransition(label = "map")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    val p = C.palette
    val markers = countries.keys.mapNotNull { code -> data.centroids[code]?.let { code to it } }

    fun project(lat: Float, lon: Float, w: Float, h: Float): Offset {
        val x = (lon + 180f) / 360f * w
        val y = (data.latTop - lat) / (data.latTop - data.latBottom) * h
        return Offset(x, y)
    }

    Canvas(
        modifier.fillMaxWidth().aspectRatio(data.cols / data.rows.toFloat())
            .semantics { contentDescription = "World map with ${markers.size} server countries" }
            .pointerInput(markers) {
                detectTapGestures { pos ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val best = markers.minByOrNull { (_, ll) -> val o = project(ll.first, ll.second, w, h); hypot(o.x - pos.x, o.y - pos.y) }
                    if (best != null) {
                        val o = project(best.second.first, best.second.second, w, h)
                        if (hypot(o.x - pos.x, o.y - pos.y) < w * 0.07f) onPick(best.first)
                    }
                }
            },
    ) {
        val cw = size.width / data.cols
        val ch = size.height / data.rows
        val dotR = minOf(cw, ch) * 0.32f
        val land = p.text.copy(alpha = if (p.isDark) 0.16f else 0.18f)
        for ((x, y) in data.dots) drawCircle(land, dotR, Offset((x + 0.5f) * cw, (y + 0.5f) * ch))
        for ((code, ll) in markers) {
            val o = project(ll.first, ll.second, size.width, size.height)
            val hot = code == highlight
            val n = countries[code] ?: 0
            val r = (3.2f + minOf(n, 30) * 0.12f).dp.toPx()
            drawCircle(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.55f), Color.Transparent), o, r * 3.2f), r * 3.2f, o)
            drawCircle(if (hot) p.accent2 else p.accent, r, o)
            drawCircle(Color.White.copy(alpha = 0.9f), r * 0.42f, o)
            if (hot) {
                val f = if (reduced) 0.5f else pulse
                drawCircle(p.accent2.copy(alpha = 1f - f), r + f * 16.dp.toPx(), o, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

@Composable
fun LocationsScreen(st: AppState, nav: Nav) {
    var query by rememberSaveable { mutableStateOf("") }
    var view by rememberSaveable { mutableStateOf("top") }
    var country by rememberSaveable { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    val prefs = st.prefs
    val fs = st.freeStatus
    val busy = when (prefs.source) {
        Prefs.SOURCE_CUSTOM -> st.customStatus.busy
        Prefs.SOURCE_LIST -> st.listStatus.busy
        Prefs.SOURCE_OWN -> st.ownTesting
        Prefs.SOURCE_PROXIO -> st.proxioStatus.busy
        Prefs.SOURCE_BOTH -> st.proxioStatus.busy || fs.busy
        Prefs.SOURCE_VPNGATE -> st.vpngateStatus.busy || st.ownTesting
        else -> fs.busy
    }
    val all = st.servers
    val working = all.filter { it.ms != null }
    val byCountry = working.filter { it.code.isNotEmpty() }.groupingBy { it.code }.eachCount()
    val inUse = if (st.on) st.status.serverIds.firstOrNull()?.let { id -> all.firstOrNull { it.id == id } } else null
    val q = query.trim().lowercase()
    fun matches(s: Server) = q.isEmpty() || "${s.name} ${s.address} ${s.country} ${s.code} ${s.proto}".lowercase().contains(q)
    fun sorted(l: List<Server>) = l.sortedBy { if (it.ms != null) it.rank.toLong() else if (it.testedAt == 0L) 50_000_000L else 100_000_000L }

    val list: List<Server> = when (view) {
        "fav" -> sorted(all.filter { it.id in prefs.favorites })
        "recent" -> prefs.recents.mapNotNull { id -> all.firstOrNull { it.id == id } }
        "all" -> sorted(all)
        else -> working.sortedBy { it.rank }.take(20)
    }.filter { (country.isEmpty() || it.code == country) && matches(it) }
    val shown = if (showAll || view != "all") list else list.take(60)

    fun pick(s: Server) { Core.select(s.id); nav.tab(0) }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            PageHeader("Locations", subtitle = "${working.size} working servers in ${byCountry.size} countries") {
                GlassIconButton(TIcons.refresh, "Find fastest servers", size = 42.dp, selected = busy) { if (!busy) Core.testAll() }
            }
        }
        item {
            GlassCard(padding = PaddingValues(10.dp)) {
                WorldMapView(byCountry, if (country.isNotEmpty()) country else inUse?.code, { code -> country = if (country == code) "" else code; if (view == "top" && byCountry[code] == null) view = "all" })
                if (country.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp)) {
                        Text("${com.tervyn.vpn.core.flagFromCode(country)} ${countryName(country)} · ${byCountry[country] ?: 0} working",
                            color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("Show all", color = C.accentLight, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(onClickLabel = "Clear country filter") { country = "" }.padding(4.dp))
                    }
                }
            }
        }
        item { GlassSearchBar(query, { query = it }, "Search country, city or host") }
        item {
            GlassTabBar(listOf("top" to "Top 20", "all" to "All", "fav" to "Favorites", "recent" to "Recent"), view, { view = it })
        }
        if (busy) item {
            Box(Modifier.padding(vertical = 4.dp)) {
                GlassProgressBar(if (prefs.source != Prefs.SOURCE_VPNGATE && fs.state == "testing") fs.progress / 100f else null)
            }
        }
        item { ServerRow(null, prefs.selected == "auto") { Core.select("auto"); nav.tab(0) } }
        item {
            Muted(
                when (view) {
                    "top" -> "Ranked by measured ping and download speed. Servers are re-tested every 30 minutes."
                    "fav" -> if (list.isEmpty()) "Tap the star on a server to keep it here." else "Your favourite servers."
                    "recent" -> if (list.isEmpty()) "Servers you connect to show up here." else "Servers you used recently."
                    else -> sourceStatus(st)
                },
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (view == "top") {
            itemsIndexed(shown, key = { _, s -> "top-" + s.id }) { i, s ->
                ServerRow(s, prefs.selected == s.id, rank = i + 1, favorite = s.id in prefs.favorites, onFavorite = { Core.toggleFavorite(s.id) }) { pick(s) }
            }
        } else {
            items(shown, key = { view + it.id }) { s ->
                ServerRow(s, prefs.selected == s.id, favorite = s.id in prefs.favorites, onFavorite = { Core.toggleFavorite(s.id) }) { pick(s) }
            }
            if (list.size > shown.size) item {
                GhostButton("Show all ${list.size} servers", Modifier.fillMaxWidth()) { showAll = true }
            }
        }
        if (all.isEmpty()) item {
            Muted("Finding free servers for you… this takes about 30 seconds the first time.", Modifier.fillMaxWidth().padding(12.dp), center = true)
        }
        item {
            VSpace(6)
            GhostButton("Server sources & your own servers", Modifier.fillMaxWidth()) { nav.open(Routes.SOURCES) }
        }
    }
}

fun sourceStatus(st: AppState): String {
    val fs = st.freeStatus
    val ps = st.proxioStatus
    val free = when {
        fs.state == "loading" -> "Downloading free server lists…"
        fs.state == "testing" -> "Testing free servers… ${fs.progress}% · ${fs.working} working so far"
        fs.state == "speed" -> "Measuring download speed of the best servers…"
        fs.error.isNotEmpty() -> fs.error
        fs.at > 0 -> "${st.free.count { it.ms != null }} fast free servers (checked ${fs.found}) · updated " +
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(fs.at))
        else -> "Free servers are being prepared…"
    }
    val proxio = when {
        st.proxioConfig.key.isBlank() -> "Add your Proxio.io API key in Server sources."
        ps.state == "loading" -> "Downloading your Proxio.io list…"
        ps.state == "testing" -> "Testing ${ps.fetched} Proxio.io servers…"
        ps.error.isNotEmpty() -> ps.error
        else -> "${st.proxio.count { it.ms != null }} of ${ps.fetched} Proxio.io servers working."
    }
    val vg = st.vpngateStatus
    return when (st.prefs.source) {
        Prefs.SOURCE_VPNGATE -> when {
            vg.state == "loading" -> "Downloading the VPN Gate server list…"
            vg.state == "testing" -> "Testing ${vg.fetched} VPN Gate servers from your network…"
            vg.error.isNotEmpty() && st.vpngate.none { it.ms != null } -> vg.error
            vg.at > 0 -> "${st.vpngate.count { it.ms != null }} of ${vg.fetched} VPN Gate servers answering · ping measured from your phone · updated " +
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(vg.at))
            else -> "VPN Gate servers are being prepared…"
        } + (st.own.firstOrNull { it.sourceId == com.tervyn.vpn.core.Warp.SOURCE }?.let { w -> "\nCloudflare WARP: " + (w.ms?.let { "$it ms" } ?: "not answering") } ?: "")
        Prefs.SOURCE_LIST -> if (st.list.isEmpty()) "Import your proxy list in Server sources." else "${st.list.count { it.ms != null }} of ${st.list.size} proxies in your list working."
        Prefs.SOURCE_CUSTOM -> if (st.custom.isEmpty()) "Paste your proxy's code in Server sources." else "${st.custom.count { it.ms != null }} of ${st.custom.size} integration proxies working."
        Prefs.SOURCE_OWN -> if (st.own.isEmpty()) "Add your own servers in Server sources." else "${st.own.count { it.ms != null }} of ${st.own.size} of your servers working."
        Prefs.SOURCE_PROXIO -> proxio
        Prefs.SOURCE_BOTH -> "$proxio\n$free"
        else -> free
    }
}
