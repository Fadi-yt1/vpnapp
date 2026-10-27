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
}
