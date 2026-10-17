package app.simgate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.simgate.data.ConfigStore
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Theme palette. */
data class Palette(
    val dark: Boolean, val bg: Color, val surface: Color, val surface2: Color, val stroke: Color,
    val text: Color, val muted: Color, val emerald: Color, val indigo: Color, val amber: Color, val red: Color, val sky: Color,
    val heroFrom: Color, val heroTo: Color,
)

val DarkPalette = Palette(
    dark = true, bg = Color(0xFF0B0F1A), surface = Color(0xFF121829), surface2 = Color(0xFF1A2236), stroke = Color(0xFF242E47),
    text = Color(0xFFE8EBF4), muted = Color(0xFF8A93A8), emerald = Color(0xFF34D399), indigo = Color(0xFF818CF8),
    amber = Color(0xFFFBBF24), red = Color(0xFFF87171), sky = Color(0xFF38BDF8),
    heroFrom = Color(0xFF0F3B30), heroTo = Color(0xFF1E1B4B),
)

val LightPalette = Palette(
    dark = false, bg = Color(0xFFF4F6FB), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEEF1F7), stroke = Color(0xFFE2E6EF),
    text = Color(0xFF0F172A), muted = Color(0xFF64748B), emerald = Color(0xFF059669), indigo = Color(0xFF4F46E5),
    amber = Color(0xFFD97706), red = Color(0xFFDC2626), sky = Color(0xFF0284C7),
    heroFrom = Color(0xFFDDF7EC), heroTo = Color(0xFFE3E5FF),
)

/** Current theme colors. Values are observable state: a theme change recomposes the UI. */
object C {
    var p by mutableStateOf(DarkPalette)
    val Bg get() = p.bg
    val Surface get() = p.surface
    val Surface2 get() = p.surface2
    val Stroke get() = p.stroke
    val Text get() = p.text
    val Muted get() = p.muted
    val Emerald get() = p.emerald
    val Indigo get() = p.indigo
    val Amber get() = p.amber
    val Red get() = p.red
    val Sky get() = p.sky
    /** Text/icon color on top of the accent gradient. */
    val OnAccent get() = if (p.dark) Color(0xFF0B0F1A) else Color.White
    val Accent get() = Brush.linearGradient(listOf(p.emerald, p.indigo))
    val Hero get() = Brush.linearGradient(listOf(p.heroFrom, p.heroTo))
}

/** Whether the theme is currently dark, taking the setting into account ("system" | "light" | "dark"). */
@Composable
fun isDarkTheme(): Boolean {
    val cfg by ConfigStore.flow.collectAsState()
    return when (cfg.theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
}

@Composable
fun SimGateTheme(content: @Composable () -> Unit) {
    val dark = isDarkTheme()
    val target = if (dark) DarkPalette else LightPalette
    if (C.p !== target) C.p = target
    val scheme = if (dark) darkColorScheme(
        primary = C.Emerald, onPrimary = C.OnAccent, secondary = C.Indigo,
        background = C.Bg, surface = C.Surface, surfaceVariant = C.Surface2,
        onBackground = C.Text, onSurface = C.Text, onSurfaceVariant = C.Muted,
        outline = C.Stroke, error = C.Red, surfaceContainer = C.Surface, surfaceContainerHigh = C.Surface2,
    ) else lightColorScheme(
        primary = C.Emerald, onPrimary = C.OnAccent, secondary = C.Indigo,
        background = C.Bg, surface = C.Surface, surfaceVariant = C.Surface2,
        onBackground = C.Text, onSurface = C.Text, onSurfaceVariant = C.Muted,
        outline = C.Stroke, error = C.Red, surfaceContainer = C.Surface, surfaceContainerHigh = C.Surface2,
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography.copy(
            headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
            titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
        ),
    ) {
        // Sets the default text color for all screens (otherwise text outside Scaffold is black).
        androidx.compose.material3.Surface(color = C.Bg, contentColor = C.Text, content = content)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(C.Surface)
            .border(BorderStroke(1.dp, C.Stroke), RoundedCornerShape(20.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(padding.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = C.Muted, modifier = modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Int = 40) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.32).dp)).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.5).dp)) }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SettingRow(
    icon: ImageVector, title: String, subtitle: String? = null, tint: Color = C.Indigo,
    onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, tint, 36)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = C.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, content = trailing)
    }
}

@Composable
fun ToggleRow(icon: ImageVector, title: String, subtitle: String?, checked: Boolean, tint: Color = C.Indigo, onChange: (Boolean) -> Unit) {
    SettingRow(icon, title, subtitle, tint, onClick = { onChange(!checked) }) {
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = C.Emerald, checkedThumbColor = Color.White, uncheckedTrackColor = C.Surface2, uncheckedBorderColor = C.Stroke),
        )
    }
}

@Composable
fun Divider() { Spacer(Modifier.fillMaxWidth().height(1.dp).background(C.Stroke)) }

/** Avatar with a gradient derived from a string. */
@Composable
fun Avatar(seed: String, size: Int = 46) {
    val palette = listOf(C.Emerald, C.Indigo, C.Sky, C.Amber, Color(0xFFF472B6), Color(0xFFA78BFA))
    val h = kotlin.math.abs(seed.hashCode())
    val a = palette[h % palette.size]; val b = palette[(h / 7) % palette.size]
    val label = seed.filter { it.isLetterOrDigit() }.takeLast(2).ifEmpty { "#" }
    Box(Modifier.size(size.dp).clip(CircleShape).background(Brush.linearGradient(listOf(a, b))), contentAlignment = Alignment.Center) {
        Text(label, color = Color(0xFF0B0F1A).copy(alpha = 0.85f), fontWeight = FontWeight.Bold, fontSize = (size * 0.32).sp)
    }
}
