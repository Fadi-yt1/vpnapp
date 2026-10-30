package com.tervyn.vpn.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tervyn.vpn.core.Server

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(), color = C.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp, modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Color(0x1A7B5CFF) else C.card)
            .border(1.dp, if (selected) Color(0xCC8C6EFF) else C.border, shape)
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
    Card(modifier, onClick, selected, PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (enabled) C.buttonGradient else androidx.compose.ui.graphics.SolidColor(Color(0x33FFFFFF)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color.White else C.muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color = C.text, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .clip(shape)
            .background(Color(0x0DFFFFFF))
            .border(1.dp, C.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
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
        placeholder = { Text(placeholder, color = Color(0xFF666D99)) },
        singleLine = singleLine, minLines = minLines,
        leadingIcon = if (leading != null) { { Icon(leading, null, tint = C.muted, modifier = Modifier.size(18.dp)) } } else null,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = C.inputBg, unfocusedContainerColor = C.inputBg,
            focusedBorderColor = C.accent, unfocusedBorderColor = C.border,
            focusedTextColor = C.text, unfocusedTextColor = C.text, cursorColor = C.accent,
        ),
    )
}

@Composable
fun Alert(text: String, warn: Boolean = false, onClose: (() -> Unit)? = null) {
    val color = if (warn) C.amber else C.red
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(shape).background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.35f), shape).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = if (warn) Color(0xFFFFE5BD) else Color(0xFFFFD3D9), fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (onClose != null) {
            Icon(TIcons.close, "Dismiss", tint = C.muted, modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClose).padding(8.dp))
        }
    }
}

@Composable
fun FlagBadge(flag: String?, size: Int = 36) {
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(if (flag == null) C.accentSoft else Color(0x0FFFFFFF))
            .border(1.dp, if (flag == null) Color(0x598C6EFF) else C.border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (flag == null) Icon(TIcons.bolt, null, tint = Color(0xFFA58BFF), modifier = Modifier.size((size / 2).dp))
        else Text(flag, fontSize = (size * 0.52f).sp)
    }
}

@Composable
fun PingBadge(ms: Int?, tested: Boolean) {
    val (text, color) = when {
        !tested -> "—" to C.muted
        ms == null -> "offline" to C.red
        ms < 1500 -> "$ms ms" to C.green
        ms < 3500 -> "$ms ms" to C.amber
        else -> "$ms ms" to C.red
    }
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(color.copy(alpha = if (color == C.muted) 0.08f else 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun ServerRow(s: Server?, selected: Boolean, onClick: () -> Unit) {
    CardRow(onClick = onClick, selected = selected) {
        FlagBadge(s?.flag, 34)
        Column(Modifier.weight(1f)) {
            Text(
                if (s == null) "Fastest available" else if (s.country != "Unknown") s.country else s.name,
                color = C.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (s == null) "Recommended — switches automatically" else serverSub(s),
                color = C.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (s != null) PingBadge(s.ms, s.testedAt > 0)
    }
}

fun serverSub(s: Server): String {
    val kind = when {
        s.free -> "Free"
        s.isProxio -> "Proxio.io"
        s.sourceId == com.tervyn.vpn.core.ProxySnippet.SOURCE -> "Integration"
        s.sourceId == com.tervyn.vpn.core.ProxyList.SOURCE -> "Your list"
        else -> "Yours"
    }
    val proto = if (s.isCore) {
        val name = if (s.proto == "shadowsocks") "SS" else s.proto.uppercase()
        val t = com.tervyn.vpn.core.Links.spec(s.link)?.transport
        if (t != null) "$name · $t" else name
    } else s.proto.uppercase()
    return "$kind · $proto · ${s.hostPort}"
}

/** A card that opens a menu of choices. */
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
            if (value.isEmpty()) Icon(TIcons.bolt, null, tint = C.green, modifier = Modifier.size(20.dp))
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

@Composable
fun Segmented(options: List<Pair<String, String>>, value: String, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(99.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color(0x38000000)).border(1.dp, C.border, shape).padding(4.dp),
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
                Text(label, color = if (active) Color.White else C.muted, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(99.dp)
    Text(
        (if (active) "✓ " else "+ ") + text,
        color = if (active) Color(0xFFC4B5FF) else C.text, fontSize = 13.sp,
        modifier = Modifier.clip(shape)
            .background(if (active) Color(0x1F7B5CFF) else C.card)
            .border(BorderStroke(1.dp, if (active) Color(0xB38C6EFF) else C.border), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
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

/** A card with a title, an explanation and an on/off switch. */
@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    CardRow(onClick = { onChange(!checked) }) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = C.muted, fontSize = 11.sp)
        }
        androidx.compose.material3.Switch(
            checked = checked, onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = C.accent,
                uncheckedThumbColor = C.muted, uncheckedTrackColor = Color(0x22FFFFFF), uncheckedBorderColor = C.border,
            ),
        )
    }
}
