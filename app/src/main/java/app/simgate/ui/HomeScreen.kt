package app.simgate.ui

import app.simgate.data.t
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Webhook
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.simgate.data.ConfigStore
import app.simgate.data.Db
import app.simgate.data.SimConfig
import app.simgate.data.Stats
import app.simgate.net.RemoteClient
import app.simgate.net.TelegramBot
import androidx.compose.material.icons.automirrored.rounded.Send as TgIcon
import app.simgate.service.Gateway
import app.simgate.sms.SimInfo
import app.simgate.sms.SimManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun copy(ctx: Context, label: String, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, t("Скопировано", "Copied"), Toast.LENGTH_SHORT).show()
}

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val cfg by ConfigStore.flow.collectAsState()
    val rev by Db.revision.collectAsState()
    val running by Gateway.serviceRunning.collectAsState()
    val server by Gateway.server.collectAsState()
    val remote by RemoteClient.state.collectAsState()
    val tg by TelegramBot.state.collectAsState()
    val data by produceState<Pair<Stats, List<SimInfo>>?>(null, rev, cfg) {
        value = withContext(Dispatchers.IO) { Db.stats() to SimManager.list() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("iSMS GW", style = MaterialTheme.typography.headlineMedium)
                Text(cfg.deviceName, color = C.Muted, fontSize = 13.sp)
            }
            LanguageSwitcher()
        }
        Spacer(Modifier.height(10.dp))
        Pill(if (running) t("Онлайн", "Online") else t("Офлайн", "Offline"), if (running) C.Emerald else C.Muted)
        Spacer(Modifier.height(16.dp))

        HeroCard(running) { if (running) Gateway.stopService(ctx) else Gateway.startService(ctx) }

        if (running) {
            SectionTitle(t("Подключение к веб-панели", "Web panel access"))
            Card {
                if (server.running) {
                    server.urls.ifEmpty { listOf(t("Нет сети Wi-Fi", "No Wi-Fi network")) }.forEach { url ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Rounded.Lan, C.Sky, 36)
                            Spacer(Modifier.width(12.dp))
                            Text(url, fontFamily = FontFamily.Monospace, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { copy(ctx, "URL", url) }) { Icon(Icons.Rounded.ContentCopy, null, tint = C.Muted) }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.ErrorOutline, C.Red, 36); Spacer(Modifier.width(12.dp))
                        Text(if (server.error.isNotEmpty()) t("Сервер не запущен: ${server.error}", "Server not running: ${server.error}") else t("Локальный сервер отключён в настройках", "Local server is disabled in settings"), color = C.Muted, fontSize = 13.sp)
                    }
                }
                if (cfg.admin.initialPassword.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp)); Divider(); Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.Lock, C.Amber, 36); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t("Пароль панели (временный)", "Panel password (temporary)"), color = C.Muted, fontSize = 12.sp)
                            Text(cfg.admin.initialPassword, fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        IconButton(onClick = { copy(ctx, t("Пароль", "Password"), cfg.admin.initialPassword) }) { Icon(Icons.Rounded.ContentCopy, null, tint = C.Muted) }
                    }
                    Text(t("Смените пароль в настройках или в веб-панели.", "Change the password in Settings or in the web panel."), color = C.Amber, fontSize = 12.sp)
                }
            }
        }

        val st = data?.first
        SectionTitle(t("Сегодня", "Today"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(t("Отправлено", "Sent"), st?.sentToday ?: 0, Icons.Rounded.Send, C.Emerald, Modifier.weight(1f))
            StatTile(t("Получено", "Received"), st?.receivedToday ?: 0, Icons.Rounded.CallReceived, C.Indigo, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(t("В очереди", "Queued"), st?.queued ?: 0, Icons.Rounded.Schedule, C.Amber, Modifier.weight(1f))
            StatTile(t("Ошибки", "Errors"), st?.failedToday ?: 0, Icons.Rounded.ErrorOutline, C.Red, Modifier.weight(1f))
        }

        SectionTitle(t("SIM-карты", "SIM cards"))
        val sims = data?.second.orEmpty()
        if (sims.isEmpty()) Card { Text(t("Активные SIM-карты не найдены", "No active SIM cards found"), color = C.Muted) }
        sims.forEach { s ->
            SimCardView(s, st?.perSimToday?.get(s.simId) ?: 0)
            Spacer(Modifier.height(10.dp))
        }

        SectionTitle(t("Каналы интеграции", "Integrations"))
        Card {
            ChannelRow(Icons.Rounded.Lan, t("Локальный сервер / API", "Local server / API"), C.Sky,
                if (!running) t("Остановлен", "Stopped") else if (server.running) t("Порт", "Port") + " ${cfg.server.port}" + (if (cfg.server.https) " • HTTPS" else "") else t("Отключён", "Disabled"),
                if (running && server.running) C.Emerald else C.Muted)
            Divider()
            ChannelRow(Icons.Rounded.CloudSync, t("SMS-сервер (WebSocket)", "SMS server (WebSocket)"), C.Indigo,
                when (remote) {
                    RemoteClient.State.CONNECTED -> t("Подключено", "Connected"); RemoteClient.State.CONNECTING -> t("Подключение…", "Connecting…")
                    RemoteClient.State.DISCONNECTED -> t("Переподключение", "Reconnecting"); else -> if (cfg.remote.enabled) t("Ожидает запуска", "Waiting to start") else t("Не настроен", "Not configured")
                },
                when (remote) { RemoteClient.State.CONNECTED -> C.Emerald; RemoteClient.State.DISABLED -> C.Muted; else -> C.Amber })
            Divider()
            ChannelRow(Icons.AutoMirrored.Rounded.TgIcon, t("Telegram-бот", "Telegram bot"), C.Sky,
                when { !cfg.telegram.enabled -> t("Выключен", "Off"); tg == TelegramBot.State.ONLINE -> t("Онлайн • ${cfg.telegram.chats.size} чат.", "Online • ${cfg.telegram.chats.size} chat(s)"); tg == TelegramBot.State.DISABLED -> t("Ожидает запуска", "Waiting to start"); else -> t("Нет связи", "No connection") },
                when { tg == TelegramBot.State.ONLINE -> C.Emerald; cfg.telegram.enabled -> C.Amber; else -> C.Muted })
            Divider()
            ChannelRow(Icons.Rounded.Webhook, t("Вебхуки", "Webhooks"), C.Amber, t("${cfg.webhooks.count { it.enabled }} активн.", "${cfg.webhooks.count { it.enabled }} active"), if (cfg.webhooks.any { it.enabled }) C.Emerald else C.Muted)
            Divider()
            ChannelRow(Icons.Rounded.Key, t("API-ключи", "API keys"), C.Emerald, t("${cfg.apiKeys.count { it.enabled }} активн.", "${cfg.apiKeys.count { it.enabled }} active"), if (cfg.apiKeys.any { it.enabled }) C.Emerald else C.Muted)
        }

        if (!Gateway.isIgnoringBatteryOptimizations()) {
            Spacer(Modifier.height(16.dp))
            Card(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.BatteryAlert, C.Amber, 36); Spacer(Modifier.width(12.dp))
                    Column {
                        Text(t("Оптимизация батареи включена", "Battery optimization is on"), fontWeight = FontWeight.SemiBold)
                        Text(t("Система может усыплять шлюз. Отключите оптимизацию для iSMS GW (на Samsung также: Батарея → Спящие приложения).", "The system may put the gateway to sleep. Disable optimization for iSMS GW (on Samsung also: Battery → Sleeping apps)."), color = C.Muted, fontSize = 12.sp)
                    }
                }
            }
        }
        BrandFooter()
    }
}

@Composable
private fun HeroCard(running: Boolean, onToggle: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val s by pulse.animateFloat(1f, if (running) 1.08f else 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "s")
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
            .background(if (running) C.Hero else Brush.linearGradient(listOf(C.Surface, C.Surface2)))
            .border(1.dp, if (running) C.Emerald.copy(alpha = 0.35f) else C.Stroke, RoundedCornerShape(26.dp))
            .padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (running) t("Шлюз активен", "Gateway is active") else t("Шлюз остановлен", "Gateway is stopped"), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (running) t("Принимает запросы API и пересылает события", "Accepting API requests and forwarding events") else t("Нажмите, чтобы запустить сервер, API и подключения", "Tap to start the server, API and connections"),
                    color = C.Muted, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier.size(76.dp).scale(s).clip(CircleShape)
                    .background(if (running) C.Accent else Brush.linearGradient(listOf(C.Surface2, C.Surface2)))
                    .border(2.dp, if (running) Color.Transparent else C.Stroke, CircleShape)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PowerSettingsNew, t("Вкл/выкл", "On/off"), tint = if (running) C.OnAccent else C.Emerald, modifier = Modifier.size(36.dp)) }
        }
    }
}

@Composable
private fun StatTile(label: String, value: Int, icon: ImageVector, tint: Color, modifier: Modifier) {
    Card(modifier) {
        IconBadge(icon, tint, 32)
        Spacer(Modifier.height(10.dp))
        Text("$value", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(label, color = C.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun SimCardView(s: SimInfo, sentToday: Int) {
    fun save(block: (SimConfig) -> SimConfig) = ConfigStore.update { c ->
        val cur = c.sims.find { it.simId == s.simId } ?: SimConfig(simId = s.simId, slot = s.slot)
        c.copy(sims = c.sims.map { if (it.simId == s.simId) block(cur) else it }.let { l -> if (l.any { it.simId == s.simId }) l else l + block(cur) })
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(if (s.slot == 1) C.Emerald.copy(.15f) else C.Indigo.copy(.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.SimCard, null, tint = if (s.slot == 1) C.Emerald else C.Indigo)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.label, fontWeight = FontWeight.SemiBold)
                    if (s.isDefault) { Spacer(Modifier.width(8.dp)); Pill(t("по умолч.", "default"), C.Sky) }
                }
                Text(listOf(s.carrier.ifBlank { s.displayName }, s.number).filter { it.isNotBlank() }.joinToString(" • "), color = C.Muted, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("$sentToday${if (s.dailyLimit > 0) " / ${s.dailyLimit}" else ""}", fontWeight = FontWeight.SemiBold)
                Text(t("сегодня", "today"), color = C.Muted, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniToggle(t("Отправка", "Sending"), s.sendEnabled, Modifier.weight(1f)) { v -> save { it.copy(sendEnabled = v) } }
            MiniToggle(t("Приём", "Receiving"), s.receiveEnabled, Modifier.weight(1f)) { v -> save { it.copy(receiveEnabled = v) } }
        }
        if (s.active) {
            Spacer(Modifier.height(8.dp))
            SimActions(s)
        }
    }
}

@Composable
private fun MiniToggle(label: String, checked: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(C.Surface2).clickable { onChange(!checked) }.padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked, onChange, modifier = Modifier.scale(0.8f),
            colors = SwitchDefaults.colors(checkedTrackColor = C.Emerald, checkedThumbColor = Color.White, uncheckedTrackColor = C.Bg, uncheckedBorderColor = C.Stroke))
    }
}

@Composable
private fun ChannelRow(icon: ImageVector, title: String, tint: Color, status: String, statusColor: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, tint, 34); Spacer(Modifier.width(12.dp))
        Text(title, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Pill(status, statusColor)
    }
}
