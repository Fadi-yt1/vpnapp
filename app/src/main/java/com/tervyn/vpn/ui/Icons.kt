package com.tervyn.vpn.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Icons from the extension's popup.html, as Compose vectors. */
object TIcons {
    private class P(val d: String, val stroke: Float = 0f, val color: Color = Color.Black, val brush: Brush? = null)

    private fun icon(name: String, size: Float, vararg paths: P): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, size, size)
        for (p in paths) {
            if (p.stroke > 0f) {
                b.addPath(
                    addPathNodes(p.d), stroke = SolidColor(p.color), strokeLineWidth = p.stroke,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            } else {
                b.addPath(addPathNodes(p.d), fill = p.brush ?: SolidColor(p.color))
            }
        }
        return b.build()
    }

    private val logoBrush = Brush.verticalGradient(listOf(Color(0xFF9A7BFF), Color(0xFF5636E8)), startY = 3f, endY = 30f)

    val logo = icon(
        "logo", 32f,
        P("M2.6 3.2h26.8v6.4H2.6z", brush = logoBrush),
        P("M2.6 12.8h11.2v12.8l2.2 2.2 2.2-2.2V12.8h11.2v3.8L16 30.1 2.6 16.6z", brush = logoBrush),
        P("M2.6 3.2H16v6.4H2.6z", color = Color(0x38FFFFFF)),
    )
    val home = icon("home", 24f, P("M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z"))
    val servers = icon(
        "servers", 24f,
        P("M5.1 4h13.8a1.6 1.6 0 0 1 1.6 1.6v3.3a1.6 1.6 0 0 1-1.6 1.6H5.1a1.6 1.6 0 0 1-1.6-1.6V5.6A1.6 1.6 0 0 1 5.1 4z", 1.8f),
        P("M5.1 13.5h13.8a1.6 1.6 0 0 1 1.6 1.6v3.3a1.6 1.6 0 0 1-1.6 1.6H5.1a1.6 1.6 0 0 1-1.6-1.6v-3.3a1.6 1.6 0 0 1 1.6-1.6z", 1.8f),
        P("M7 7.25h.01M7 16.75h.01", 2.6f),
    )
    val globe = icon(
        "globe", 24f,
        P("M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17z", 1.8f),
        P("M3.5 12h17M12 3.5c2.4 2.4 3.5 5.3 3.5 8.5s-1.1 6.1-3.5 8.5c-2.4-2.4-3.5-5.3-3.5-8.5s1.1-6.1 3.5-8.5z", 1.8f),
    )
    val gear = icon(
        "gear", 24f,
        P("M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z", 1.8f),
        P("M12 2.8l1.6 2.3 2.7-.7.7 2.7 2.6 1-.9 2.6 1.6 2.3-2.3 1.6.1 2.8-2.8.2-1 2.6-2.6-.9L12 21.2l-1.6-2.3-2.6.9-1-2.6-2.8-.2.1-2.8-2.3-1.6 1.6-2.3-.9-2.6 2.6-1 .7-2.7 2.7.7z", 1.8f),
    )
    val power = icon("power", 24f, P("M12 3.5v8", 2.2f), P("M7 6.6a7.5 7.5 0 1 0 10 0", 2.2f))
    val shield = icon(
        "shield", 24f,
        P("M12 2.5 4 5.5v6c0 4.9 3.4 9 8 10 4.6-1 8-5.1 8-10v-6z"),
    )
    val pin = icon("pin", 24f, P("M12 2.5a7 7 0 0 0-7 7c0 5 7 12 7 12s7-7 7-12a7 7 0 0 0-7-7zm0 9.5a2.5 2.5 0 1 1 0-5 2.5 2.5 0 0 1 0 5z"))
    val bolt = icon("bolt", 24f, P("M13.5 2 4.5 13.5h6L9.5 22l9-11.5h-6z"))
    val lock = icon(
        "lock", 24f,
        P("M6.7 10.5h10.6a2.2 2.2 0 0 1 2.2 2.2v6.1a2.2 2.2 0 0 1-2.2 2.2H6.7a2.2 2.2 0 0 1-2.2-2.2v-6.1a2.2 2.2 0 0 1 2.2-2.2z"),
        P("M8 10.5V8a4 4 0 0 1 8 0v2.5", 2.2f),
    )
    val chevron = icon("chev", 24f, P("m9 5 7 7-7 7", 2f))
    val down = icon("down", 24f, P("m6 9 6 6 6-6", 2f))
    val plus = icon("plus", 24f, P("M12 5v14M5 12h14", 2f))
    val close = icon("close", 24f, P("M6.5 6.5l11 11M17.5 6.5l-11 11", 2f))
    val search = icon("search", 24f, P("M11 4.5a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13z", 2f), P("m16 16 4.5 4.5", 2f))
    val check = icon("check", 24f, P("m5 12.5 4.5 4.5L19 7.5", 2.2f))

    // Line icons (24 × 24, 1.8 stroke)
    val dns = icon("dns", 24f,
        P("M4 5.5h16v5H4zM4 13.5h16v5H4z", 1.8f),
        P("M7.5 8h.01M7.5 16h.01M11 8h5.5M11 16h5.5", 2f),
    )
    val chart = icon("chart", 24f, P("M3.5 19.5h17", 1.8f), P("M5.5 15.5l4-5 3.5 3 5.5-7", 2f))
    val apps = icon("apps", 24f,
        P("M5 4h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM15 4h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM5 14h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1zM15 14h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1z", 1.8f),
    )
    val palette = icon("palette", 24f,
        P("M12 3.5a8.5 8.5 0 0 0 0 17c1.2 0 1.8-.8 1.8-1.7 0-1.3-1.2-1.6-1.2-2.7 0-.9.7-1.6 1.6-1.6h2.1a4.2 4.2 0 0 0 4.2-4.2C20.5 6.6 16.7 3.5 12 3.5z", 1.8f),
        P("M7.5 11.5h.01M9.5 7.5h.01M14.5 7.5h.01", 2.6f),
    )
    val info = icon("info", 24f, P("M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17z", 1.8f), P("M12 11v5.5M12 7.8h.01", 2.2f))
    val help = icon("help", 24f, P("M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17z", 1.8f), P("M9.6 9.4a2.5 2.5 0 0 1 4.8 1c0 1.7-2.4 2-2.4 3.6M12 17h.01", 2f))
    val star = icon("star", 24f, P("m12 3.8 2.5 5.1 5.6.8-4 3.9.9 5.6-5-2.6-5 2.6.9-5.6-4-3.9 5.6-.8z", 1.8f))
    val starFilled = icon("starf", 24f, P("m12 3.8 2.5 5.1 5.6.8-4 3.9.9 5.6-5-2.6-5 2.6.9-5.6-4-3.9 5.6-.8z"))
    val clock = icon("clock", 24f, P("M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17z", 1.8f), P("M12 7.5V12l3 2", 2f))
    val wifi = icon("wifi", 24f, P("M3.5 9a12.5 12.5 0 0 1 17 0M6.5 12.3a8 8 0 0 1 11 0M9.4 15.4a3.6 3.6 0 0 1 5.2 0", 2f), P("M12 18.6h.01", 2.8f))
    val layers = icon("layers", 24f, P("m12 3.5 8.5 4.5L12 12.5 3.5 8z", 1.8f), P("m3.5 12 8.5 4.5 8.5-4.5M3.5 16l8.5 4.5 8.5-4.5", 1.8f))
    val refresh = icon("refresh", 24f, P("M19.5 12a7.5 7.5 0 1 1-2.2-5.3", 2f), P("M19.5 4.5v4h-4", 2f))
    val back = icon("back", 24f, P("M15 5l-7 7 7 7", 2.2f))
    val trash = icon("trash", 24f, P("M4.5 7h15M9.5 7V5h5v2M6.5 7l1 13h9l1-13", 1.8f))
    val edit = icon("edit", 24f, P("M4.5 19.5h4l10-10a2.8 2.8 0 0 0-4-4l-10 10z", 1.8f))
    val arrowDown = icon("adown", 24f, P("M12 4.5v15M6 13.5l6 6 6-6", 2f))
    val arrowUp = icon("aup", 24f, P("M12 19.5v-15M6 10.5l6-6 6 6", 2f))
    val pulse = icon("pulse", 24f, P("M3 12h4l2.5-6 5 12 2.5-6h4", 2f))
    val user = icon("user", 24f, P("M12 4a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", 1.8f), P("M4.5 20.5c1-3.7 4-5.5 7.5-5.5s6.5 1.8 7.5 5.5", 1.8f))
    val eyeOff = icon("eyeoff", 24f, P("M3.5 12s3-6 8.5-6 8.5 6 8.5 6-3 6-8.5 6-8.5-6-8.5-6z", 1.8f), P("M4 4l16 16", 1.8f))
    val bug = icon("bug", 24f, P("M8 9.5a4 4 0 0 1 8 0V15a4 4 0 0 1-8 0z", 1.8f), P("M12 11v8M4 13h4M16 13h4M5 8l3 2M19 8l-3 2M5 19l3-2M19 19l-3-2", 1.8f))
    val route = icon("route", 24f, P("M6 4.5a2 2 0 1 0 0 4a2 2 0 1 0 0-4zM18 15.5a2 2 0 1 0 0 4a2 2 0 1 0 0-4z", 1.8f), P("M8 6.5h7.5a3 3 0 0 1 0 6h-7a3 3 0 0 0 0 6H16", 1.8f))
    val sparkle = icon("sparkle", 24f, P("M12 3.5c.6 4.4 2.1 5.9 6.5 6.5-4.4.6-5.9 2.1-6.5 6.5-.6-4.4-2.1-5.9-6.5-6.5 4.4-.6 5.9-2.1 6.5-6.5z"), P("M18.5 15.5c.3 2 1 2.7 3 3-2 .3-2.7 1-3 3-.3-2-1-2.7-3-3 2-.3 2.7-1 3-3z"))
    val ipv6 = icon("network", 24f, P("M12 3.5v5M12 15.5v5M5 12h-1.5M20.5 12H19", 1.8f), P("M12 8.5a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7z", 1.8f))
    val filter = icon("filter", 24f, P("M4 5.5h16l-6 7.5v5.5l-4 1.5v-7z", 1.8f))
    val list = icon("list", 24f, P("M8.5 6.5h11M8.5 12h11M8.5 17.5h11", 2f), P("M4.5 6.5h.01M4.5 12h.01M4.5 17.5h.01", 2.6f))
    val upload = icon("upload", 24f, P("M12 15V4.5M7.5 9 12 4.5 16.5 9M4.5 15.5v3a1.5 1.5 0 0 0 1.5 1.5h12a1.5 1.5 0 0 0 1.5-1.5v-3", 2f))
    val alert = icon("alert", 24f, P("M12 4 2.8 19.5h18.4z", 1.8f), P("M12 10v4.5M12 17h.01", 2.2f))
}
