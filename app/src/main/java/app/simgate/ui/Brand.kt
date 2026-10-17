package app.simgate.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.simgate.data.ConfigStore
import app.simgate.data.I18n
import app.simgate.data.t
import java.util.Calendar

const val DEVELOPER_URL = "https://www.ismarty.pro"

// ---------------------------------------------------------------- flags

private fun DrawScope.usFlag() {
    val w = size.width; val h = size.height
    val stripe = h / 13f
    drawRect(Color.White)
    for (i in 0 until 13 step 2) drawRect(Color(0xFFB22234), Offset(0f, i * stripe), Size(w, stripe))
    val cw = w * 0.46f; val ch = stripe * 7
    drawRect(Color(0xFF3C3B6E), Offset.Zero, Size(cw, ch))
    val r = stripe * 0.32f
    for (row in 0 until 4) for (col in 0 until 5) {
        val x = cw * (col + 0.5f) / 5f + if (row % 2 == 1) cw / 10f else 0f
        if (x > cw - r) continue
        drawCircle(Color.White, r, Offset(x, ch * (row + 0.5f) / 4f))
    }
}

private fun DrawScope.ruFlag() {
    val h = size.height / 3f
    drawRect(Color.White, Offset.Zero, Size(size.width, h))
    drawRect(Color(0xFF0039A6), Offset(0f, h), Size(size.width, h))
    drawRect(Color(0xFFD52B1E), Offset(0f, 2 * h), Size(size.width, h + 1))
}

@Composable
fun Flag(lang: String, size: Int = 26) {
    Canvas(Modifier.size(size.dp).clip(CircleShape)) { if (lang == I18n.EN) usFlag() else ruFlag() }
}

// ---------------------------------------------------------------- language

/** Language switcher: two round flags; the active one has a gradient ring. */
@Composable
fun LanguageSwitcher(modifier: Modifier = Modifier) {
    val current by I18n.lang
    Row(
        modifier.clip(RoundedCornerShape(50)).background(C.Surface).border(1.dp, C.Stroke, RoundedCornerShape(50)).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(I18n.EN to "English", I18n.RU to "Русский").forEach { (code, label) ->
            val active = current == code
            val scale by animateFloatAsState(if (active) 1f else 0.82f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "flag")
            val alpha by animateFloatAsState(if (active) 1f else 0.55f, label = "alpha")
            Box(
                Modifier.size(36.dp).clip(CircleShape)
                    .then(if (active) Modifier.background(C.Accent) else Modifier)
                    .clickable { if (!active) I18n.set(code) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.scale(scale).size(30.dp).clip(CircleShape).background(C.Surface).padding(2.dp)) {
                    Flag(code, 26)
                    if (!active) Box(Modifier.size(26.dp).clip(CircleShape).background(C.Surface.copy(alpha = 1f - alpha)))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- theme

/** Segmented theme switcher with a sliding indicator: light · auto · dark. */
@Composable
fun ThemeSwitcher(modifier: Modifier = Modifier, compact: Boolean = false) {
    val cfg by ConfigStore.flow.collectAsState()
    val options: List<Triple<String, ImageVector, String>> = listOf(
        Triple("light", Icons.Rounded.LightMode, t("Светлая", "Light")),
        Triple("system", Icons.Rounded.BrightnessAuto, t("Авто", "Auto")),
        Triple("dark", Icons.Rounded.DarkMode, t("Тёмная", "Dark")),
    )
    val index = options.indexOfFirst { it.first == cfg.theme }.coerceAtLeast(1)
    val itemW = if (compact) 44 else 90
    val offset by animateDpAsState((index * itemW).dp, spring(dampingRatio = 0.75f, stiffness = 500f), label = "thumb")
    Box(
        modifier.clip(RoundedCornerShape(50)).background(C.Surface2).border(1.dp, C.Stroke, RoundedCornerShape(50)).padding(4.dp),
    ) {
        Box(
            Modifier.offset(x = offset).width(itemW.dp).height(36.dp)
                .shadow(6.dp, RoundedCornerShape(50), clip = false)
                .clip(RoundedCornerShape(50)).background(C.Surface),
        )
        Row {
            options.forEachIndexed { i, (key, icon, label) ->
                val sel = i == index
                val tint by animateColorAsState(
                    if (!sel) C.Muted else when (key) { "light" -> C.Amber; "dark" -> C.Indigo; else -> C.Emerald }, label = "tint",
                )
                Row(
                    Modifier.width(itemW.dp).height(36.dp).clip(RoundedCornerShape(50))
                        .clickable(remember { MutableInteractionSource() }, null) { ConfigStore.update { it.copy(theme = key) } }
                        .semantics { contentDescription = label },
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                    if (!compact) {
                        Box(Modifier.width(6.dp))
                        Text(label, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium, color = if (sel) C.Text else C.Muted)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- footer

/** © 2026 Developed by iSmartyPro (localized), with a link to the developer's website. */
@Composable
fun BrandFooter(modifier: Modifier = Modifier) {
    val year = Calendar.getInstance().get(Calendar.YEAR)
    val text = buildAnnotatedString {
        append("© $year ")
        append(t("Разработано ", "Developed by "))
        withLink(LinkAnnotation.Url(DEVELOPER_URL, TextLinkStyles(SpanStyle(color = C.Emerald, fontWeight = FontWeight.SemiBold)))) {
            append("iSmartyPro")
        }
    }
    androidx.compose.foundation.layout.Column(
        modifier.fillMaxWidth().padding(top = 28.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.width(40.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.Accent))
        Box(Modifier.height(12.dp))
        Text(text, fontSize = 12.sp, color = C.Muted, textAlign = TextAlign.Center)
        Text("iSMS GW ${app.simgate.BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = C.Muted.copy(alpha = 0.7f))
    }
}
