package com.tervyn.vpn.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.tervyn.vpn.core.Links
import com.tervyn.vpn.core.ProxyList
import com.tervyn.vpn.core.ProxySnippet
import com.tervyn.vpn.core.Server
import com.tervyn.vpn.core.formatMbps
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.cos
import kotlin.math.sin

// =============================================================================
// Tervyn glass design system
//
// Every surface is "liquid glass": the animated background shows through,
// blurred (Android 12+, when blur is on) or as a soft translucent layer, with a
// light catching rim on the top-left edge. All colours come from the theme (C).
// =============================================================================

val ShapeCard = RoundedCornerShape(26.dp)
val ShapeInner = RoundedCornerShape(18.dp)
val ShapePill = RoundedCornerShape(99.dp)

/** True when real background blur is used (Android 12+, blur setting on). */
@Composable
fun blurActive(): Boolean {
    val env = LocalGlass.current
    return env.haze != null && env.blur && Build.VERSION.SDK_INT >= 31
}

/**
 * The glass material. [selected] tints it with the accent; [strong] makes it
 * more opaque (bars, sheets).
 */
@Composable
fun Modifier.glass(shape: Shape = ShapeCard, selected: Boolean = false, strong: Boolean = false): Modifier {
    val env = LocalGlass.current
    val p = C.palette
    val blur = blurActive()
    val fill = when {
        selected -> C.glassFillSelected
        strong -> Brush.verticalGradient(listOf(p.fillTop.copy(alpha = (p.fillTop.alpha * 1.6f).coerceAtMost(1f)), p.fillBottom.copy(alpha = (p.fillBottom.alpha * 2.2f).coerceAtMost(1f))))
        else -> C.glassFill
    }
    return this
        .drawBehind {
            // Soft drop shadow under the glass, following its shape.
            if (!p.isDark || strong) {
                val outline = shape.createOutline(size, layoutDirection, this)
                translate(0f, 5.dp.toPx()) {
                    drawOutline(outline, Brush.verticalGradient(listOf(Color.Transparent, p.shadow), startY = size.height * 0.5f, endY = size.height))
                }
            }
        }
        .clip(shape)
        .then(
            if (blur && env.haze != null) Modifier.hazeEffect(
                env.haze,
                HazeStyle(
                    backgroundColor = p.bg2,
                    tints = listOf(HazeTint(p.tint)),
                    blurRadius = if (strong) 30.dp else 22.dp,
                    noiseFactor = 0.04f,
                ),
            ) else Modifier,
        )
        .then(if (strong && !blur) Modifier.background(p.bg1.copy(alpha = if (p.isDark) 0.95f else 0.92f)) else Modifier)
        .background(fill)
        .drawBehind {
            // Specular highlight along the top edge.
            drawRect(
                Brush.verticalGradient(listOf(p.rimHi.copy(alpha = p.rimHi.alpha * 0.35f), Color.Transparent), 0f, size.height * 0.45f),
            )
        }
        .border(1.dp, if (selected) C.glassBorderSelected else C.glassBorder, shape)
}

/** Light haptic tick, when haptics are on. */
@Composable
fun rememberTick(): () -> Unit {
    val h = LocalHapticFeedback.current
    val on = LocalGlass.current.haptics
    return remember(h, on) { { if (on) h.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
}

/** Gently shrinks while pressed. */
@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val reduced = LocalGlass.current.reducedMotion
    val s by animateFloatAsState(if (pressed && !reduced) 0.97f else 1f, spring(stiffness = 600f), label = "press")
    return s
}

// ----------------------------------------------------------------------------- containers

@Composable
fun GlassContainer(
    modifier: Modifier = Modifier,
    shape: Shape = ShapeCard,
    selected: Boolean = false,
    strong: Boolean = false,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    padding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val scale = if (onClick != null) pressScale(src) else 1f
    Column(
        modifier
            .scale(scale)
            .glass(shape, selected, strong)
            .then(if (onClick != null) Modifier.clickable(src, null, onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    padding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    content: @Composable ColumnScope.() -> Unit,
) = GlassContainer(modifier.fillMaxWidth(), ShapeCard, selected, false, onClick, null, padding, content)

/** Kept names used throughout the screens. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    padding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    content: @Composable ColumnScope.() -> Unit,
) = GlassCard(modifier, onClick, selected, padding, content)

@Composable
fun CardRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    GlassCard(modifier, onClick, selected, PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

// ----------------------------------------------------------------------------- buttons

enum class ButtonStyle { Primary, Secondary, Danger }

@Composable
fun GlassButton(
    text: String,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val scale = pressScale(src)
    val tick = rememberTick()
    val primary = style == ButtonStyle.Primary && enabled
    val fg = when {
        !enabled -> C.muted
        style == ButtonStyle.Primary -> C.onAccent
        style == ButtonStyle.Danger -> C.red
        else -> C.text
    }
    Row(
        modifier
            .scale(scale)
            .heightIn(min = 48.dp)
            .then(
                if (primary) Modifier.clip(ShapePill).background(C.buttonGradient)
                    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.05f))), ShapePill)
                else Modifier.glass(ShapePill),
            )
            .clickable(src, null, enabled = enabled && !loading, role = Role.Button) { tick(); onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            Spinner(16.dp, fg)
            HSpace(8)
        } else if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            HSpace(8)
        }
        Text(text, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    GlassButton(text, modifier, ButtonStyle.Primary, enabled = enabled, onClick = onClick)

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color = C.text, onClick: () -> Unit) =
    GlassButton(text, modifier, if (color == C.red) ButtonStyle.Danger else ButtonStyle.Secondary, onClick = onClick)

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = C.text,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val scale = pressScale(src)
    Box(
        modifier.scale(scale).size(size).glass(CircleShape, selected)
            .clickable(src, null, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.45f))
    }
}

/** Small circular loading indicator. */
@Composable
fun Spinner(size: Dp = 18.dp, color: Color = C.accent) {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    Canvas(Modifier.size(size)) {
        drawArc(color.copy(alpha = 0.2f), 0f, 360f, false, style = Stroke(2.dp.toPx()))
        drawArc(color, a, 100f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ----------------------------------------------------------------------------- navigation

class NavItem(val label: String, val icon: ImageVector)

/** Floating glass tab bar with a sliding highlight. */
@Composable
fun GlassNavigationBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val tick = rememberTick()
    BoxWithConstraints(
        modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp)
            .glass(RoundedCornerShape(30.dp), strong = true).padding(6.dp),
    ) {
        val w = maxWidth / items.size
        val x by animateDpAsState(w * selected, if (LocalGlass.current.reducedMotion) tween(0) else spring(dampingRatio = 0.8f, stiffness = 500f), label = "tab")
        Box(
            Modifier.offset(x = x).width(w).height(56.dp).clip(RoundedCornerShape(24.dp))
                .background(C.glassFillSelected).border(1.dp, C.glassBorderSelected, RoundedCornerShape(24.dp)),
        )
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, t ->
                val active = selected == i
                val color by animateColorAsState(if (active) C.text else C.muted, label = "c")
                Column(
                    Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(24.dp))
                        .selectable(active, role = Role.Tab) { tick(); onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, null, tint = if (active) C.accentLight else color, modifier = Modifier.size(22.dp))
                    VSpace(3)
                    Text(t.label, color = color, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

/** Segmented control with a sliding glass thumb. */
@Composable
fun GlassTabBar(options: List<Pair<String, String>>, value: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val tick = rememberTick()
    val idx = options.indexOfFirst { it.first == value }.coerceAtLeast(0)
    BoxWithConstraints(modifier.fillMaxWidth().glass(ShapePill).padding(4.dp)) {
        val w = maxWidth / options.size.coerceAtLeast(1)
        val x by animateDpAsState(w * idx, if (LocalGlass.current.reducedMotion) tween(0) else spring(dampingRatio = 0.8f, stiffness = 500f), label = "seg")
        if (options.any { it.first == value }) {
            Box(Modifier.offset(x = x).width(w).height(40.dp).clip(ShapePill).background(C.buttonGradient))
        }
        Row(Modifier.fillMaxWidth()) {
            for ((key, label) in options) {
                val active = key == value
                Box(
                    Modifier.weight(1f).height(40.dp).clip(ShapePill)
                        .selectable(active, role = Role.Tab) { tick(); onSelect(key) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (active) C.onAccent else C.muted, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
        }
    }
}

@Composable
fun Segmented(options: List<Pair<String, String>>, value: String, onSelect: (String) -> Unit) = GlassTabBar(options, value, onSelect)

/** Screen title row with an optional back button and trailing content. */
@Composable
fun PageHeader(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            GlassIconButton(TIcons.back, "Back", size = 42.dp, onClick = onBack)
            HSpace(12)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = C.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = Fonts.display, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = C.muted, fontSize = 13.sp)
        }
        trailing?.invoke()
    }
}

// ----------------------------------------------------------------------------- dialogs & sheets

@Composable
fun GlassDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String? = "Cancel",
    danger: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(ShapeCard)
                .background(Brush.verticalGradient(listOf(lerpSolid(C.bg1, C.accent, 0.08f), C.bg2)))
                .border(1.dp, C.glassBorder, ShapeCard)
                .padding(22.dp),
        ) {
            Text(title, color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Fonts.display)
            if (text.isNotEmpty()) { VSpace(8); Text(text, color = C.muted, fontSize = 14.sp) }
            if (content != null) { VSpace(14); content() }
            VSpace(20)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (dismiss != null) GlassButton(dismiss, Modifier.weight(1f), ButtonStyle.Secondary, onClick = onDismiss)
                GlassButton(confirm, Modifier.weight(1f), if (danger) ButtonStyle.Danger else ButtonStyle.Primary, onClick = onConfirm)
            }
        }
    }
}

/** Bottom sheet: a glass panel sliding up from the bottom over a dimmed screen. */
@Composable
fun GlassBottomSheet(visible: Boolean, onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    if (!visible) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var shown by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }
        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(shown, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                        .background(Brush.verticalGradient(listOf(lerpSolid(C.bg1, C.accent, 0.1f), C.bg2)))
                        .border(1.dp, C.glassBorder, RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                        .clickable(remember { MutableInteractionSource() }, null) { }
                        .navigationBarsPadding()
                        .heightIn(max = 640.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 4.dp).clip(ShapePill).background(C.track))
                    VSpace(14)
                    if (title != null) {
                        Text(title, color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Fonts.display)
                        VSpace(12)
                    }
                    content()
                    VSpace(12)
                }
            }
        }
    }
}

/** A small glass bubble anchored to its parent (tips, details). */
@Composable
fun GlassPopup(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (!visible) return
    Popup(alignment = Alignment.TopCenter, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.widthIn(max = 300.dp).padding(8.dp).clip(ShapeInner)
                .background(lerpSolid(C.bg1, C.accent, 0.12f)).border(1.dp, C.glassBorder, ShapeInner).padding(14.dp),
            content = content,
        )
    }
}

fun lerpSolid(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t).copy(alpha = 1f)

// ----------------------------------------------------------------------------- inputs

@Composable
fun GlassInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    leading: ImageVector? = null,
    password: Boolean = false,
    error: Boolean = false,
    label: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = if (singleLine) ShapePill else ShapeInner
    Column(modifier) {
        if (label != null) {
            Text(label, color = C.label, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
        }
        BasicTextField(
            value = value, onValueChange = onValueChange,
            singleLine = singleLine, minLines = minLines,
            textStyle = TextStyle(color = C.text, fontSize = 15.sp, fontFamily = Fonts.body),
            cursorBrush = SolidColor(C.accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label ?: placeholder.lineSequence().first() }
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(shape).background(C.inputBg)
                        .border(if (focused || error) 1.5.dp else 1.dp, if (error) C.red else if (focused) C.accent else C.border, shape)
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                ) {
                    if (leading != null) {
                        Icon(leading, null, tint = C.muted, modifier = Modifier.size(18.dp))
                        HSpace(10)
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(placeholder, color = C.muted.copy(alpha = 0.8f), fontSize = 15.sp)
                        inner()
                    }
                }
            },
        )
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
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) = GlassInput(value, onValueChange, placeholder, modifier, singleLine, minLines, leading, password,
    keyboardOptions = keyboardOptions, keyboardActions = keyboardActions)

@Composable
fun GlassSearchBar(query: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GlassInput(query, onChange, placeholder, Modifier.weight(1f), leading = TIcons.search)
        if (query.isNotEmpty()) {
            HSpace(8)
            GlassIconButton(TIcons.close, "Clear search", size = 44.dp) { onChange("") }
        }
    }
}

/** Glass switch: a frosted track with a sliding bright thumb. */
@Composable
fun GlassSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, if (LocalGlass.current.reducedMotion) tween(0) else spring(dampingRatio = 0.7f, stiffness = 700f), label = "sw")
    val track by animateColorAsState(if (checked) C.accent else C.track, label = "tr")
    Box(
        modifier.size(50.dp, 30.dp).clip(ShapePill)
            .background(if (checked) Brush.linearGradient(listOf(track, C.accent2)) else SolidColor(track))
            .border(1.dp, if (checked) Color.White.copy(alpha = 0.35f) else C.border, ShapePill)
            .then(if (onChange != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange) else Modifier),
    ) {
        Box(
            Modifier.offset(x = x, y = 2.dp).size(26.dp).clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFEDEAF7))))
                .border(0.5.dp, Color.Black.copy(alpha = 0.06f), CircleShape),
        )
    }
}

@Composable
fun GlassSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, steps: Int = 0, modifier: Modifier = Modifier) {
    Slider(
        value, onChange, modifier, valueRange = range, steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = Color.White, activeTrackColor = C.accent, inactiveTrackColor = C.track,
            activeTickColor = C.onAccent.copy(alpha = 0.5f), inactiveTickColor = C.muted.copy(alpha = 0.5f),
        ),
    )
}

/** A glass row that opens a menu of choices. */
@Composable
fun GlassDropdown(value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        CardRow(onClick = { open = true }) {
            if (leading != null) Icon(leading, null, tint = C.accentLight, modifier = Modifier.size(20.dp))
            Text(options.firstOrNull { it.first == value }?.second ?: value, color = C.text, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(TIcons.down, null, tint = C.muted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            shape = ShapeInner, containerColor = lerpSolid(C.bg1, C.accent, 0.08f),
        ) {
            for ((key, label) in options) {
                DropdownMenuItem(
                    text = { Text(label, color = if (key == value) C.accentLight else C.text, fontWeight = if (key == value) FontWeight.SemiBold else FontWeight.Normal) },
                    trailingIcon = if (key == value) { { Icon(TIcons.check, null, tint = C.accentLight, modifier = Modifier.size(18.dp)) } } else null,
                    onClick = { open = false; onSelect(key) },
                )
            }
        }
    }
}

@Composable
fun OptionPicker(value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) = GlassDropdown(value, options, onSelect)

/** A card that opens a menu of countries. value = "" means any / fastest. */
@Composable
fun CountryPicker(value: String, countries: List<Triple<String, String, Int>>, anyLabel: String, onSelect: (String) -> Unit) {
    GlassDropdown(value, listOf("" to "⚡  $anyLabel") + countries.map { (code, name, n) -> code to "$name ($n)" }, onSelect)
}

// ----------------------------------------------------------------------------- status & data

/** Determinate (0..1) or, with null, indeterminate progress bar. */
@Composable
fun GlassProgressBar(progress: Float?, modifier: Modifier = Modifier, color: Color = C.accent) {
    val t = rememberInfiniteTransition(label = "pb")
    val shift by t.animateFloat(-0.4f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "s")
    val p by animateFloatAsState(progress ?: 0f, label = "p")
    Box(modifier.fillMaxWidth().height(8.dp).clip(ShapePill).background(C.track)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val left = if (progress == null) (shift * w).coerceAtLeast(0f) else 0f
            val right = if (progress == null) (shift * w + w * 0.4f).coerceAtMost(w) else w * p.coerceIn(0f, 1f)
            if (right > left) drawRoundRect(
                Brush.horizontalGradient(listOf(color, C.accent2), left, right),
                topLeft = Offset(left, 0f), size = androidx.compose.ui.geometry.Size(right - left, size.height),
                cornerRadius = CornerRadius(size.height / 2, size.height / 2),
            )
        }
    }
}

/** A pill with a coloured dot (pulsing when [live]) and a label. */
@Composable
fun GlassStatusBadge(text: String, color: Color, modifier: Modifier = Modifier, live: Boolean = false) {
    val t = rememberInfiniteTransition(label = "badge")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    val reduced = LocalGlass.current.reducedMotion
    Row(modifier.glass(ShapePill).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = if (live && !reduced) a else 1f)))
        HSpace(7)
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, maxLines = 1)
    }
}

/** A small sparkline of recent values. */
@Composable
fun Sparkline(values: List<Long>, color: Color, modifier: Modifier = Modifier, fill: Boolean = true) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height - (v / max) * size.height * 0.9f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (fill) {
            val area = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), Color.Transparent)))
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Speed card: label, live value and a sparkline of the last minute. */
@Composable
fun GlassSpeedCard(label: String, value: String, icon: ImageVector, color: Color, history: List<Long>, modifier: Modifier = Modifier) {
    GlassContainer(modifier, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            }
            HSpace(8)
            Text(label, color = C.muted, fontSize = 12.sp)
        }
        VSpace(8)
        Text(value, color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Fonts.display, maxLines = 1)
        VSpace(6)
        Sparkline(history.takeLast(30), color, Modifier.fillMaxWidth().height(28.dp))
    }
}

/** A compact stat tile: label and value (value "—" when unknown). */
@Composable
fun StatTile(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier, color: Color = C.accentLight, onClick: (() -> Unit)? = null) {
    GlassContainer(modifier, ShapeInner, onClick = onClick, padding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
            HSpace(6)
            Text(label, color = C.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        VSpace(6)
        Text(value, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ----------------------------------------------------------------------------- lists & rows

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 6.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = C.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Icon in a soft tinted circle, used at the start of rows. */
@Composable
fun IconBubble(icon: ImageVector, color: Color = C.accentLight, size: Dp = 38.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.5f))
    }
}

/** A settings row: icon, title, subtitle and a trailing switch, value or chevron. */
@Composable
fun SettingRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    iconColor: Color = C.accentLight,
    checked: Boolean? = null,
    enabled: Boolean = true,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val tick = rememberTick()
    val rowMod = when {
        checked != null && onCheckedChange != null ->
            Modifier.toggleable(checked, enabled = enabled, role = Role.Switch) { tick(); onCheckedChange(it) }
        onClick != null -> Modifier.clickable(enabled = enabled) { tick(); onClick() }
        else -> Modifier
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).then(rowMod).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { IconBubble(icon, iconColor, 36.dp); HSpace(12) }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) C.text else C.muted, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, color = C.muted, fontSize = 12.sp)
        }
        if (value != null) {
            HSpace(8)
            Text(value, color = C.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
        }
        if (checked != null) { HSpace(10); GlassSwitch(checked, null, enabled = enabled) }
        else if (onClick != null) { HSpace(6); Icon(TIcons.chevron, null, tint = C.muted, modifier = Modifier.size(16.dp)) }
    }
}

/** A glass card holding rows; put [RowDivider] between them. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    GlassCard(modifier, padding = PaddingValues(vertical = 4.dp), content = content)

/** Thin line between rows of a [GroupCard]. */
@Composable
fun RowDivider(inset: Dp = 64.dp) {
    Box(Modifier.fillMaxWidth().padding(start = inset, end = 16.dp).height(1.dp).background(C.divider))
}

@Composable
fun Alert(text: String, warn: Boolean = false, onClose: (() -> Unit)? = null) {
    val color = if (warn) C.amber else C.red
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(ShapeInner).background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), ShapeInner).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TIcons.alert, null, tint = color, modifier = Modifier.size(18.dp))
        HSpace(10)
        Text(text, color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (onClose != null) {
            Icon(TIcons.close, "Dismiss", tint = C.muted, modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose).padding(13.dp))
        }
    }
}

/** Round glass badge with a country flag, or a lightning bolt for "fastest". */
@Composable
fun FlagBadge(flag: String?, size: Int = 36) {
    Box(Modifier.size(size.dp).glass(CircleShape, selected = flag == null), contentAlignment = Alignment.Center) {
        if (flag == null) Icon(TIcons.bolt, null, tint = C.accentLight, modifier = Modifier.size((size / 2).dp))
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
            Box(Modifier.width(3.dp).height((3 + i * 3).dp).clip(RoundedCornerShape(2.dp)).background(if (i <= level) color else C.track))
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
fun ServerRow(
    s: Server?,
    selected: Boolean,
    rank: Int? = null,
    favorite: Boolean? = null,
    onFavorite: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    CardRow(onClick = onClick, selected = selected) {
        if (rank != null) {
            Text("$rank", color = if (rank <= 3) C.accentLight else C.muted, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                modifier = Modifier.width(16.dp), textAlign = TextAlign.Center)
        }
        FlagBadge(s?.flag, 36)
        Column(Modifier.weight(1f)) {
            Text(
                if (s == null) "Fastest available" else if (s.country != "Unknown") s.country else s.name,
                color = C.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (s == null) "Smart Connect: lowest ping, highest speed" else serverSub(s),
                color = C.muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (s != null) {
            Column(horizontalAlignment = Alignment.End) {
                PingBadge(s.ms, s.testedAt > 0)
                s.mbps?.let { Text(formatMbps(it), color = C.accentLight, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        if (favorite != null && onFavorite != null) {
            Icon(
                if (favorite) TIcons.starFilled else TIcons.star,
                if (favorite) "Remove from favorites" else "Add to favorites",
                tint = if (favorite) C.amber else C.muted,
                modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onFavorite).padding(8.dp),
            )
        }
    }
}

fun protoName(proto: String): String = when (proto) {
    "shadowsocks" -> "Shadowsocks"
    "wireguard" -> "WireGuard"
    "socks5" -> "SOCKS5"
    "vless" -> "VLESS"
    "vmess" -> "VMess"
    "trojan" -> "Trojan"
    "http", "https" -> "HTTP"
    "" -> "—"
    else -> proto.uppercase()
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
        val t = Links.spec(s.link)?.transport
        if (t != null && s.proto != "wireguard") "${protoName(s.proto)} · $t" else protoName(s.proto)
    } else protoName(s.proto)
    return "$kind · $proto · ${s.hostPort}"
}

@Composable
fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    val tick = rememberTick()
    Text(
        (if (active) "✓ " else "+ ") + text,
        color = if (active) C.text else C.text, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.glass(ShapePill, selected = active).clickable { tick(); onClick() }.padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, center: Boolean = false) {
    Text(text, color = C.muted, fontSize = 12.5.sp, modifier = modifier, textAlign = if (center) TextAlign.Center else TextAlign.Start)
}

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun HSpace(w: Int) = Spacer(Modifier.width(w.dp))

/** A glass card with a title, an explanation and a switch. */
@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, icon: ImageVector? = null) {
    GlassCard(padding = PaddingValues(0.dp)) {
        SettingRow(icon, title, subtitle, checked = checked, onCheckedChange = onChange)
    }
}

/** A round radio mark for single-choice lists. */
@Composable
fun RadioDot(selected: Boolean) {
    Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) C.accent else C.muted.copy(alpha = 0.5f), CircleShape).padding(5.dp)) {
        if (selected) Box(Modifier.fillMaxSize().clip(CircleShape).background(C.buttonGradient))
    }
}

/** A single-choice row (radio). */
@Composable
fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val tick = rememberTick()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton) { tick(); onClick() }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioDot(selected)
        HSpace(14)
        Column(Modifier.weight(1f)) {
            Text(title, color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, color = C.muted, fontSize = 12.sp)
        }
        trailing?.invoke()
    }
}

/** Check or cross with a label: a genuine pass / fail line from a test. */
@Composable
fun ResultRow(name: String, ok: Boolean, detail: String, ms: Int? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).clip(CircleShape).background((if (ok) C.green else C.red).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(if (ok) TIcons.check else TIcons.close, if (ok) "Passed" else "Failed", tint = if (ok) C.green else C.red, modifier = Modifier.size(14.dp))
        }
        HSpace(12)
        Column(Modifier.weight(1f)) {
            Text(name, color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(detail, color = C.muted, fontSize = 12.sp)
        }
        if (ms != null) Text("$ms ms", color = C.muted, fontSize = 12.sp)
    }
}

// ----------------------------------------------------------------------------- background

/**
 * The app background: the theme's gradient with slowly drifting soft lights,
 * which is what the glass surfaces blur and refract.
 */
@Composable
fun GlassBackground(on: Boolean, modifier: Modifier = Modifier) {
    val reduced = LocalGlass.current.reducedMotion
    val t = rememberInfiniteTransition(label = "bg")
    val a by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(26_000, easing = LinearEasing)), label = "a")
    val angle = if (reduced) 0.6f else a
    val p = C.palette
    val boost by animateFloatAsState(if (on) 1.25f else 1f, tween(900), label = "boost")
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(p.bg1, p.bg2)))
        val w = size.width
        val h = size.height
        fun orb(cx: Float, cy: Float, r: Float, color: Color, alpha: Float) =
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha.coerceAtMost(1f)), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
        orb(w * (0.12f + 0.08f * cos(angle)), h * (0.14f + 0.05f * sin(angle)), w * 0.9f, p.glow1, p.glowAlpha * boost)
        orb(w * (0.95f + 0.06f * sin(angle * 2)), h * (0.42f + 0.06f * cos(angle)), w * 0.8f, p.glow2, p.glowAlpha * 0.85f)
        orb(w * (0.28f + 0.10f * sin(angle)), h * (0.9f + 0.04f * cos(angle * 2)), w * 1.0f, p.glow3, p.glowAlpha * boost)
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (p.isDark) 0.05f else 0.25f), Color.Transparent), 0f, h * 0.3f))
    }
}

/** Fills a lazy list's bottom so content can scroll above the floating tab bar. */
@Composable
fun BottomBarSpace() = Spacer(Modifier.height(110.dp).fillMaxWidth())
