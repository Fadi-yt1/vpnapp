package com.tervyn.vpn.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tervyn.vpn.core.Links
import com.tervyn.vpn.core.ProxyList
import com.tervyn.vpn.core.ProxySnippet
import com.tervyn.vpn.core.Server
import com.tervyn.vpn.core.formatMbps
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Glass basics
// ---------------------------------------------------------------------------

/** Frosted glass: a see-through light fill with a bright rim. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(22.dp), selected: Boolean = false): Modifier =
    this.clip(shape)
        .background(if (selected) C.glassFillSelected else C.glassFill)
        .border(1.dp, if (selected) C.glassBorderSelected else C.glassBorder, shape)

/**
 * The app background: deep navy with slowly drifting cyan, blue and violet
 * glows, which is what makes the glass panels look like glass.
 */
@Composable
fun GlassBackground(on: Boolean) {
    val t = rememberInfiniteTransition(label = "bg")
    val a by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(22_000, easing = LinearEasing)), label = "a")
    val glow by t.animateFloat(0.85f, 1f, infiniteRepeatable(tween(3_000), RepeatMode.Reverse), label = "g")
    Canvas(Modifier.fillMaxSize()) {
        drawRect(C.background)
        val w = size.width
        val h = size.height
        fun orb(cx: Float, cy: Float, r: Float, color: Color, alpha: Float) =
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
        val boost = if (on) 1.25f else 1f
        orb(w * (0.15f + 0.08f * cos(a)), h * (0.12f + 0.05f * sin(a)), w * 0.85f, C.cyan, 0.30f * glow * boost)
        orb(w * (0.95f + 0.06f * sin(a * 2)), h * (0.38f + 0.06f * cos(a)), w * 0.80f, C.violet, 0.34f * glow)
        orb(w * (0.30f + 0.10f * sin(a)), h * (0.88f + 0.04f * cos(a * 2)), w * 0.95f, C.accent, 0.36f * glow * boost)
        // Fine glass sheen across the top.
        drawRect(Brush.verticalGradient(listOf(Color(0x14FFFFFF), Color.Transparent), 0f, h * 0.35f))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(), color = C.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp, modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp), selected)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun CardRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Card(modifier, onClick, selected, PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (enabled) C.buttonGradient else C.glassFill)
            .border(1.dp, if (enabled) Brush.verticalGradient(listOf(Color(0x80FFFFFF), Color(0x10FFFFFF))) else C.glassBorder, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color.White else C.muted, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color = C.text, onClick: () -> Unit) {
    Box(
        modifier
            .glass(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    leading: ImageVector? = null,
    password: Boolean = false,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier,
        placeholder = { Text(placeholder, color = Color(0xFF7783B3)) },
        singleLine = singleLine, minLines = minLines,
        leadingIcon = if (leading != null) { { Icon(leading, null, tint = C.muted, modifier = Modifier.size(18.dp)) } } else null,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color(0x1FFFFFFF), unfocusedContainerColor = C.inputBg,
            focusedBorderColor = C.cyan, unfocusedBorderColor = C.border,
            focusedTextColor = C.text, unfocusedTextColor = C.text, cursorColor = C.cyan,
        ),
    )
}

@Composable
fun Alert(text: String, warn: Boolean = false, onClose: (() -> Unit)? = null) {
    val color = if (warn) C.amber else C.red
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(shape).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.4f), shape).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = if (warn) Color(0xFFFFE6C2) else Color(0xFFFFD6DD), fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (onClose != null) {
            Icon(TIcons.close, "Dismiss", tint = C.muted, modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClose).padding(8.dp))
        }
    }
}

/** Round glass badge with a country flag, or a lightning bolt for "fastest". */
@Composable
fun FlagBadge(flag: String?, size: Int = 36) {
    Box(
        Modifier.size(size.dp).glass(CircleShape, selected = flag == null),
        contentAlignment = Alignment.Center,
    ) {
        if (flag == null) Icon(TIcons.bolt, null, tint = C.cyan, modifier = Modifier.size((size / 2).dp))
        else Text(flag, fontSize = (size * 0.55f).sp)
    }
}

/** 1–4 bars: how good a server's delay is. */
@Composable
fun SignalBars(ms: Int?, modifier: Modifier = Modifier) {
    val level = when {
        ms == null -> 0
        ms < 700 -> 4
        ms < 1400 -> 3
        ms < 2500 -> 2
        else -> 1
    }
    val color = when (level) { 4, 3 -> C.green; 2 -> C.amber; 1 -> C.red; else -> C.muted }
    Row(modifier.height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) {
            Box(
                Modifier.width(3.dp).height((3 + i * 3).dp).clip(RoundedCornerShape(2.dp))
                    .background(if (i <= level) color else Color(0x33FFFFFF)),
            )
        }
    }
}

@Composable
fun PingBadge(ms: Int?, tested: Boolean) {
    val (text, color) = when {
        !tested -> "—" to C.muted
        ms == null -> "offline" to C.red
        ms < 1400 -> "$ms ms" to C.green
        ms < 2500 -> "$ms ms" to C.amber
        else -> "$ms ms" to C.red
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (tested && ms != null) SignalBars(ms)
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ServerRow(s: Server?, selected: Boolean, rank: Int? = null, onClick: () -> Unit) {
    CardRow(onClick = onClick, selected = selected) {
        if (rank != null) {
            Text("$rank", color = if (rank <= 3) C.cyan else C.muted, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.width(20.dp), textAlign = TextAlign.Center)
        }
        FlagBadge(s?.flag, 38)
        Column(Modifier.weight(1f)) {
            Text(
                if (s == null) "Fastest available" else if (s.country != "Unknown") s.country else s.name,
                color = C.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (s == null) "Smart pick: lowest ping and highest speed" else serverSub(s),
                color = C.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (s != null) {
            Column(horizontalAlignment = Alignment.End) {
                PingBadge(s.ms, s.testedAt > 0)
                s.mbps?.let { Text(formatMbps(it), color = C.accentLight, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

fun serverSub(s: Server): String {
    val kind = when {
        s.free -> "Free"
        s.isProxio -> "Proxio.io"
        s.sourceId == ProxySnippet.SOURCE -> "Integration"
        s.sourceId == ProxyList.SOURCE -> "Your list"
        else -> "Yours"
    }
    val proto = if (s.isCore) {
        val name = if (s.proto == "shadowsocks") "SS" else s.proto.uppercase()
        val t = Links.spec(s.link)?.transport
        if (t != null) "$name · $t" else name
    } else s.proto.uppercase()
    return "$kind · $proto · ${s.hostPort}"
}

/** A glass card that opens a menu of choices. */
@Composable
fun OptionPicker(value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        CardRow(onClick = { open = true }) {
            Text(options.firstOrNull { it.first == value }?.second ?: value, color = C.text, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(TIcons.down, null, tint = C.muted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((key, label) in options) {
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSelect(key) })
            }
        }
    }
}

/** A card that opens a menu of countries. value = "" means any / fastest. */
@Composable
fun CountryPicker(
    value: String,
    countries: List<Triple<String, String, Int>>,   // code, flag + name, count
    anyLabel: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        CardRow(onClick = { open = true }) {
            if (value.isEmpty()) Icon(TIcons.bolt, null, tint = C.cyan, modifier = Modifier.size(20.dp))
            Text(
                if (value.isEmpty()) anyLabel else countries.firstOrNull { it.first == value }?.second ?: value,
                color = C.text, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
            )
            Icon(TIcons.down, null, tint = C.muted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("⚡  $anyLabel") }, onClick = { open = false; onSelect("") })
            for ((code, name, count) in countries) {
                DropdownMenuItem(text = { Text("$name ($count)") }, onClick = { open = false; onSelect(code) })
            }
        }
    }
}

/** Glass pill with a gradient "thumb" on the chosen option. */
@Composable
fun Segmented(options: List<Pair<String, String>>, value: String, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(99.dp)
    Row(
        Modifier.fillMaxWidth().glass(shape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((key, label) in options) {
            val active = key == value
            Box(
                Modifier.weight(1f).clip(shape)
                    .then(if (active) Modifier.background(C.buttonGradient) else Modifier)
                    .clickable { onSelect(key) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (active) Color.White else C.muted, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

@Composable
fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        (if (active) "✓ " else "+ ") + text,
        color = if (active) Color.White else C.text, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.glass(RoundedCornerShape(99.dp), selected = active)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
    )
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, center: Boolean = false) {
    Text(text, color = C.muted, fontSize = 12.sp, modifier = modifier, textAlign = if (center) TextAlign.Center else TextAlign.Start)
}

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun HSpace(w: Int) = Spacer(Modifier.width(w.dp))

/** A glass card with a title, an explanation and an on/off switch. */
@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    CardRow(onClick = { onChange(!checked) }) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = C.muted, fontSize = 11.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = C.accent, checkedBorderColor = C.cyan,
                uncheckedThumbColor = C.muted, uncheckedTrackColor = Color(0x22FFFFFF), uncheckedBorderColor = C.border,
            ),
        )
    }
}

/** A round radio mark for single-choice lists (e.g. DNS provider). */
@Composable
fun RadioDot(selected: Boolean) {
    Box(
        Modifier.size(22.dp).clip(CircleShape)
            .border(2.dp, if (selected) C.cyan else C.border, CircleShape)
            .padding(5.dp),
    ) {
        if (selected) Box(Modifier.fillMaxSize().clip(CircleShape).background(C.buttonGradient))
    }
}
