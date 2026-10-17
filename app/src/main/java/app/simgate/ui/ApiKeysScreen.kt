package app.simgate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.simgate.data.ApiKey
import app.simgate.data.ConfigStore
import app.simgate.data.Crypto
import app.simgate.data.Log
import app.simgate.data.Users
import app.simgate.data.t
import app.simgate.sms.SimInfo
import app.simgate.sms.SimManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun simColor(hex: String, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

private fun ago(ts: Long): String {
    if (ts <= 0) return t("не использовался", "never used")
    val m = (System.currentTimeMillis() - ts) / 60_000
    return when {
        m < 1 -> t("только что", "just now")
        m < 60 -> t("$m мин назад", "$m min ago")
        m < 1440 -> t("${m / 60} ч назад", "${m / 60} h ago")
        else -> SimpleDateFormat("dd.MM.yy HH:mm", Locale.US).format(Date(ts))
    }
}

/** API key management: list, enable/disable, delete, create. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApiKeysScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val cfg by ConfigStore.flow.collectAsState()
    val sims = remember(cfg.sims) { SimManager.list() }
    var create by remember { mutableStateOf(false) }
    var shownToken by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<ApiKey?>(null) }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, t("Назад", "Back")) }
            Column(Modifier.weight(1f)) {
                Text(t("API-ключи", "API keys"), style = MaterialTheme.typography.titleLarge)
                Text(t("Доступ внешних систем к шлюзу", "External system access to the gateway"), color = C.Muted, fontSize = 12.sp)
            }
            Box(
                Modifier.clip(RoundedCornerShape(14.dp)).background(C.Accent).clickable { create = true }.padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Add, null, tint = C.OnAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("Создать", "Create"), color = C.OnAccent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            if (cfg.apiKeys.isEmpty()) {
                Card {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        IconBadge(Icons.Rounded.Key, C.Emerald, 56)
                        Spacer(Modifier.height(12.dp))
                        Text(t("Ключей пока нет", "No keys yet"), fontWeight = FontWeight.SemiBold)
                        Text(t("Создайте ключ, чтобы подключить CRM, бота или свой сервис.", "Create a key to connect a CRM, a bot or your own service."),
                            color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, start = 12.dp, end = 12.dp))
                    }
                }
            }
            cfg.apiKeys.sortedByDescending { it.createdAt }.forEach { k ->
                KeyCard(k, sims,
                    onToggle = { v -> ConfigStore.update { c -> c.copy(apiKeys = c.apiKeys.map { if (it.id == k.id) it.copy(enabled = v) else it }) } },
                    onDelete = { toDelete = k })
                Spacer(Modifier.height(10.dp))
            }
            Text(
                t("Ключ передаётся в заголовке Authorization: Bearer sg_… Права ключа не превышают прав владельца. Ограничение по IP — в веб-панели.",
                    "Send the key in the Authorization: Bearer sg_… header. A key never has more rights than its owner. IP restrictions — in the web panel."),
                color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(4.dp),
            )
            BrandFooter()
        }
    }

    if (create) CreateKeyDialog(sims, onDismiss = { create = false }) { name, scopes, keySims ->
        val (token, key) = Crypto.newApiKey(name, scopes, emptyList(), Users.admin().id, keySims)
        ConfigStore.update { it.copy(apiKeys = it.apiKeys + key) }
        Log.sec(t("Создан API-ключ «${key.name}» в приложении", "API key \"${key.name}\" created in the app"))
        create = false; shownToken = token
    }

    shownToken?.let { token ->
        AlertDialog(
            onDismissRequest = { shownToken = null }, containerColor = C.Surface,
            title = { Text(t("Ключ создан", "Key created")) },
            text = {
                Column {
                    Text(t("Скопируйте ключ сейчас — повторно он не показывается.", "Copy the key now — it will not be shown again."), color = C.Amber, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(token, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Surface2).padding(12.dp))
                }
            },
            confirmButton = { TextButton(onClick = { copy(ctx, "API key", token); shownToken = null }) { Text(t("Скопировать", "Copy"), color = C.Emerald) } },
            dismissButton = { TextButton(onClick = { shownToken = null }) { Text(t("Закрыть", "Close"), color = C.Muted) } },
        )
    }

    toDelete?.let { k ->
        AlertDialog(
            onDismissRequest = { toDelete = null }, containerColor = C.Surface,
            title = { Text(t("Удалить ключ «${k.name}»?", "Delete key \"${k.name}\"?")) },
            text = { Text(t("Клиенты, использующие этот ключ, сразу потеряют доступ. Действие необратимо.", "Clients using this key will lose access immediately. This cannot be undone."), color = C.Muted) },
            confirmButton = {
                TextButton(onClick = {
                    ConfigStore.update { c -> c.copy(apiKeys = c.apiKeys.filter { it.id != k.id }) }
                    Log.sec(t("Удалён API-ключ «${k.name}» в приложении", "API key \"${k.name}\" deleted in the app"))
                    toDelete = null
                }) { Text(t("Удалить", "Delete"), color = C.Red, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(t("Отмена", "Cancel"), color = C.Muted) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyCard(k: ApiKey, sims: List<SimInfo>, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    val owner = Users.find(k.ownerId)
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Key, if (k.enabled) C.Emerald else C.Muted, 40)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(k.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("${k.prefix}…", fontFamily = FontFamily.Monospace, color = C.Muted, fontSize = 12.sp)
            }
            Switch(k.enabled, onToggle, colors = SwitchDefaults.colors(checkedTrackColor = C.Emerald, checkedThumbColor = Color.White, uncheckedTrackColor = C.Surface2, uncheckedBorderColor = C.Stroke))
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, t("Удалить", "Delete"), tint = C.Red) }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if ("send" in k.scopes) Chip(t("отправка", "send"), C.Emerald)
            if ("read" in k.scopes) Chip(t("чтение", "read"), C.Indigo)
            if (k.sims.isEmpty()) Chip(t("все SIM владельца", "all owner SIMs"), C.Sky)
            else k.sims.forEach { id -> sims.find { it.simId == id }?.let { s -> Chip(s.label, simColor(s.color, C.Sky)) } }
            if (k.allowedIps.isNotEmpty()) Chip("IP: " + k.allowedIps.joinToString(", "), C.Amber)
        }
        Spacer(Modifier.height(10.dp))
        Divider()
        Spacer(Modifier.height(8.dp))
        Row {
            Text(t("Владелец: ", "Owner: ") + (owner?.username ?: "—"), color = C.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(ago(k.lastUsedAt) + if (k.lastIp.isNotEmpty()) " • ${k.lastIp}" else "", color = C.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.13f)).padding(horizontal = 10.dp, vertical = 4.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateKeyDialog(sims: List<SimInfo>, onDismiss: () -> Unit, onCreate: (String, List<String>, List<Int>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var send by remember { mutableStateOf(true) }
    var read by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.Surface,
        title = { Text(t("Новый API-ключ", "New API key")) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text(t("Название", "Name")) }, placeholder = { Text("CRM, 1C, bot…") },
                    singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                Text(t("Права", "Permissions"), color = C.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectChip(t("Отправка SMS", "Send SMS"), send, C.Emerald) { send = !send }
                    SelectChip(t("Чтение", "Read"), read, C.Indigo) { read = !read }
                }
                Spacer(Modifier.height(14.dp))
                Text(t("SIM-карты", "SIM cards"), color = C.Muted, fontSize = 12.sp)
                Text(t("Не выбрано — доступ ко всем SIM владельца", "None selected — access to all owner SIMs"), color = C.Muted, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sims.forEach { s ->
                        val on = s.simId in selected
                        SelectChip(s.label + if (!s.active) " • " + t("неактивна", "inactive") else "", on, simColor(s.color, C.Sky)) {
                            selected = if (on) selected - s.simId else selected + s.simId
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = send || read, onClick = {
                onCreate(name.trim().ifBlank { t("Ключ", "Key") }.take(40), listOfNotNull("send".takeIf { send }, "read".takeIf { read }), selected.toList())
            }) { Text(t("Создать", "Create"), color = C.Emerald, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Отмена", "Cancel"), color = C.Muted) } },
    )
}

@Composable
fun SelectChip(text: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) color.copy(alpha = 0.16f) else C.Surface2)
            .border(1.dp, if (selected) color else C.Stroke, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 13.sp, color = if (selected) C.Text else C.Muted, fontWeight = FontWeight.Medium)
    }
}
