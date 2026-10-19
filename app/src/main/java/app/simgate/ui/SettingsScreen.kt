package app.simgate.ui

import app.simgate.data.t
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Https
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.PhoneMissed
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import app.simgate.BuildConfig
import app.simgate.data.ConfigStore
import app.simgate.data.Crypto
import app.simgate.data.Log
import app.simgate.net.TelegramBot
import app.simgate.server.Sessions
import app.simgate.server.Tls
import app.simgate.service.Gateway
import app.simgate.sms.SmsStore

private sealed interface Dlg {
    data class Text(val title: String, val initial: String, val number: Boolean = false, val onSave: (String) -> Unit) : Dlg
    data class Choice(val title: String, val options: List<Pair<String, String>>, val current: String, val onSave: (String) -> Unit) : Dlg
    data object Password : Dlg
    data object Remote : Dlg
    data object Telegram : Dlg
    data object Privacy : Dlg
}

fun firewallModes() = listOf(
    "lan" to t("Только локальная сеть (рекомендуется)", "Local network only (recommended)"),
    "whitelist" to t("Только белый список IP", "IP whitelist only"),
    "all" to t("Все адреса (небезопасно)", "All addresses (unsafe)"),
)
fun simStrategies() = listOf(
    "auto" to t("SIM по умолчанию системы", "System default SIM"),
    "slot1" to t("Всегда SIM 1", "Always SIM 1"),
    "slot2" to t("Всегда SIM 2", "Always SIM 2"),
    "roundrobin" to t("По очереди (round-robin)", "Round-robin"),
    "balanced" to t("Наименее загруженная за сегодня", "Least used today"),
)

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val cfg by ConfigStore.flow.collectAsState()
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var keysOpen by remember { mutableStateOf(false) }
    fun restart() { Gateway.restartServerLater() }
    if (keysOpen) { ApiKeysScreen { keysOpen = false }; return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Text(t("Настройки", "Settings"), style = MaterialTheme.typography.headlineMedium)
        Text(t("Базовые параметры. Полная настройка — в веб-панели.", "Basic settings. Full configuration is in the web panel."), color = C.Muted, fontSize = 13.sp)

        SectionTitle(t("Оформление", "Appearance"))
        Card {
            Text(t("Тема", "Theme"), color = C.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            ThemeSwitcher(Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("Язык", "Language"), fontWeight = FontWeight.Medium)
                    Text(t("Русский", "English"), color = C.Muted, fontSize = 12.sp)
                }
                LanguageSwitcher()
            }
        }

        SectionTitle(t("Устройство", "Device"))
        Card(padding = 8) {
            SettingRow(Icons.Rounded.Badge, t("Имя устройства", "Device name"), cfg.deviceName, onClick = {
                dlg = Dlg.Text(t("Имя устройства", "Device name"), cfg.deviceName) { v -> ConfigStore.update { it.copy(deviceName = v.take(40).ifBlank { it.deviceName }) } }
            })
            ToggleRow(Icons.Rounded.RestartAlt, t("Автозапуск после перезагрузки", "Autostart after reboot"), t("Восстанавливать работу шлюза", "Restore the gateway after reboot"), cfg.autostart, C.Sky) { v -> ConfigStore.update { it.copy(autostart = v) } }
            ToggleRow(Icons.Rounded.BatteryChargingFull, t("Не засыпать", "Keep awake"), t("Удерживать CPU и Wi-Fi активными, пока шлюз работает", "Keep CPU and Wi-Fi awake while the gateway runs"), cfg.keepAwake, C.Amber) { v ->
                ConfigStore.update { it.copy(keepAwake = v) }; Gateway.applyLocks()
            }
            SettingRow(Icons.Rounded.Speed, t("Оптимизация батареи", "Battery optimization"), if (Gateway.isIgnoringBatteryOptimizations()) t("Отключена — отлично", "Disabled — great") else t("Включена — рекомендуем отключить", "Enabled — we recommend disabling it"), C.Amber, onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            })
            @Suppress("UNUSED_EXPRESSION") MainActivity.permissionsRevision.intValue
            if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CALL_PHONE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                SettingRow(Icons.Rounded.Call, t("Разрешение «Телефон»", "Phone permission"), t("Не выдано — USSD и звонки недоступны. Нажмите, чтобы выдать", "Not granted — USSD and calls are unavailable. Tap to grant"), C.Red, onClick = {
                    (ctx as? MainActivity)?.requestOptionalPermissions()
                })
            }
            SettingRow(Icons.Rounded.Sms, t("SMS-приложение по умолчанию", "Default SMS app"), if (SmsStore.isDefaultApp()) t("iSMS GW назначен", "iSMS GW is set") else t("Не назначено — шлюз не работает", "Not set — gateway is not working"), C.Emerald, onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
            })
        }

        SectionTitle(t("Локальный сервер", "Local server"))
        Card(padding = 8) {
            ToggleRow(Icons.Rounded.Dns, t("HTTP-сервер и REST API", "HTTP server and REST API"), t("Доступ к шлюзу из локальной сети", "Gateway access from the local network"), cfg.server.enabled, C.Sky) { v ->
                ConfigStore.update { it.copy(server = it.server.copy(enabled = v)) }; restart()
            }
            SettingRow(Icons.Rounded.Numbers, t("Порт", "Port"), "${cfg.server.port}", C.Sky, onClick = {
                dlg = Dlg.Text(t("Порт (1024–65535)", "Port (1024–65535)"), "${cfg.server.port}", number = true) { v ->
                    val p = v.toIntOrNull()
                    if (p == null || p !in 1024..65535) Toast.makeText(ctx, t("Недопустимый порт", "Invalid port"), Toast.LENGTH_SHORT).show()
                    else { ConfigStore.update { it.copy(server = it.server.copy(port = p)) }; restart() }
                }
            })
            ToggleRow(Icons.Rounded.Https, t("HTTPS (самоподписанный сертификат)", "HTTPS (self-signed certificate)"), Tls.fingerprint(ctx)?.let { "SHA-256: " + it.take(23) + "…" } ?: t("Шифрование трафика в локальной сети", "Encrypt traffic on the local network"), cfg.server.https, C.Emerald) { v ->
                ConfigStore.update { it.copy(server = it.server.copy(https = v)) }; restart()
            }
            ToggleRow(Icons.Rounded.Dashboard, t("Веб-панель управления", "Web admin panel"), t("Отключите, если нужен только API", "Turn off if you only need the API"), cfg.server.webAdmin, C.Indigo) { v ->
                ConfigStore.update { it.copy(server = it.server.copy(webAdmin = v)) }
            }
        }

        SectionTitle(t("Безопасность", "Security"))
        Card(padding = 8) {
            SettingRow(Icons.Rounded.Security, t("Файрвол: режим доступа", "Firewall: access mode"), firewallModes().first { it.first == cfg.firewall.mode }.second, C.Red, onClick = {
                dlg = Dlg.Choice(t("Кто может подключаться", "Who can connect"), firewallModes(), cfg.firewall.mode) { v ->
                    ConfigStore.update { it.copy(firewall = it.firewall.copy(mode = v)) }; Log.sec(t("Режим файрвола изменён в приложении: $v", "Firewall mode changed in the app: $v"))
                }
            })
            ToggleRow(Icons.Rounded.Lock, t("Панель только из LAN", "Panel from LAN only"), t("Веб-панель недоступна из интернета", "The web panel is not reachable from the internet"), cfg.firewall.adminLanOnly, C.Red) { v ->
                ConfigStore.update { it.copy(firewall = it.firewall.copy(adminLanOnly = v)) }
            }
            SettingRow(Icons.Rounded.Lock, t("Пароль веб-панели", "Web panel password"), if (cfg.admin.initialPassword.isNotEmpty()) t("Временный пароль — смените", "Temporary password — change it") else t("Установлен", "Set"), C.Amber, onClick = { dlg = Dlg.Password })
            SettingRow(Icons.Rounded.Key, t("API-ключи", "API keys"), t("Активных: ${cfg.apiKeys.count { it.enabled }} из ${cfg.apiKeys.size} • создать, отключить, удалить", "Active: ${cfg.apiKeys.count { it.enabled }} of ${cfg.apiKeys.size} • create, disable, delete"), C.Emerald, onClick = { keysOpen = true }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = C.Muted)
            }
            SettingRow(Icons.Rounded.Group, t("Пользователи", "Users"), t("Всего: ${cfg.users.size} • права и доступ к SIM настраиваются в веб-панели", "Total: ${cfg.users.size} • permissions and SIM access are managed in the web panel"), C.Indigo)
        }

        SectionTitle(t("SMS-сервер (постоянное подключение)", "SMS server (persistent connection)"))
        Card(padding = 8) {
            SettingRow(Icons.Rounded.CloudSync, if (cfg.remote.enabled) t("Включено", "On") else t("Выключено", "Off"), cfg.remote.url.ifBlank { t("Не настроено", "Not configured") }, C.Indigo, onClick = { dlg = Dlg.Remote })
        }

        SectionTitle(t("Telegram-бот", "Telegram bot"))
        val tgState by TelegramBot.state.collectAsState()
        val tgBot by TelegramBot.botName.collectAsState()
        Card(padding = 8) {
            SettingRow(Icons.AutoMirrored.Rounded.Send, if (cfg.telegram.enabled) t("Включён", "On") + (if (tgBot.isNotEmpty()) " • @$tgBot" else "") else t("Выключен", "Off"),
                when {
                    !cfg.telegram.enabled -> t("Уведомления и управление через Telegram", "Notifications and control via Telegram")
                    tgState == TelegramBot.State.ONLINE -> t("Онлайн • привязано чатов: ${cfg.telegram.chats.size}", "Online • linked chats: ${cfg.telegram.chats.size}")
                    tgState == TelegramBot.State.DISABLED -> t("Запустится вместе со шлюзом", "Starts together with the gateway")
                    else -> t("Нет связи с Telegram", "No connection to Telegram")
                }, C.Sky, onClick = { dlg = Dlg.Telegram })
            if (cfg.telegram.enabled) {
                SettingRow(Icons.Rounded.Key, t("Код привязки чата", "Chat pairing code"), t("Отправьте боту: /start ${cfg.telegram.pairingCode}", "Send to the bot: /start ${cfg.telegram.pairingCode}"), C.Amber) {
                    IconButton(onClick = { copy(ctx, t("Код", "Code"), "/start " + cfg.telegram.pairingCode) }) { Icon(Icons.Rounded.ContentCopy, null, tint = C.Muted) }
                }
            }
        }

        SectionTitle(t("Отправка", "Sending"))
        Card(padding = 8) {
            SettingRow(Icons.Rounded.SimCard, t("Выбор SIM для API", "SIM selection for API"), simStrategies().firstOrNull { it.first == cfg.sending.strategy }?.second ?: cfg.sending.strategy, C.Emerald, onClick = {
                dlg = Dlg.Choice(t("Стратегия выбора SIM", "SIM selection strategy"), simStrategies(), cfg.sending.strategy) { v -> ConfigStore.update { it.copy(sending = it.sending.copy(strategy = v)) } }
            })
            val df = cfg.sending.destinations
            SettingRow(Icons.Rounded.Public, t("Фильтр направлений", "Destination filter"),
                if (!df.enabled) t("Выключен — SMS можно отправлять на любые номера", "Off — SMS can be sent to any number")
                else t("Запрещено: ${df.blocklist.size}", "Blocked: ${df.blocklist.size}") + (if (df.allowlistOnly) t(" • только белый список (${df.allowlist.size})", " • allowlist only (${df.allowlist.size})") else "") +
                    t(" • настройка — в веб-панели", " • configure in the web panel"), C.Red)
            SettingRow(Icons.Rounded.Timer, t("Пауза между SMS", "Delay between SMS"), t("${cfg.sending.delayMs} мс • до ${cfg.sending.maxPerMinute} SMS/мин", "${cfg.sending.delayMs} ms • up to ${cfg.sending.maxPerMinute} SMS/min"), C.Amber, onClick = {
                dlg = Dlg.Text(t("Пауза между SMS, мс", "Delay between SMS, ms"), "${cfg.sending.delayMs}", number = true) { v ->
                    v.toLongOrNull()?.let { d -> ConfigStore.update { it.copy(sending = it.sending.copy(delayMs = d.coerceIn(0, 60_000))) } }
                }
            })
        }

        SectionTitle(t("Звонки", "Calls"))
        Card(padding = 8) {
            ToggleRow(Icons.Rounded.Call, t("Отслеживать звонки", "Track calls"), t("Журнал и события call.* для вебхуков", "Call log and call.* webhook events"), cfg.calls.enabled, C.Sky) { v ->
                ConfigStore.update { it.copy(calls = it.calls.copy(enabled = v)) }; if (Gateway.serviceRunning.value) app.simgate.sms.CallService.start()
            }
            ToggleRow(Icons.Rounded.PhoneMissed, t("Пропущенные → Telegram", "Missed → Telegram"), null, cfg.calls.notifyMissed, C.Red) { v -> ConfigStore.update { it.copy(calls = it.calls.copy(notifyMissed = v)) } }
            ToggleRow(Icons.Rounded.PhoneInTalk, t("Входящие → Telegram", "Incoming → Telegram"), null, cfg.calls.notifyIncoming, C.Emerald) { v -> ConfigStore.update { it.copy(calls = it.calls.copy(notifyIncoming = v)) } }
        }

        SectionTitle(t("Данные и хранение", "Data & storage"))
        Card(padding = 8) {
            SettingRow(Icons.Rounded.History, t("Хранить историю", "Keep history"), if (cfg.storage.historyDays == 0) t("Без ограничения", "Unlimited") else t("${cfg.storage.historyDays} дн.", "${cfg.storage.historyDays} days"), C.Indigo, onClick = {
                dlg = Dlg.Choice(t("Срок хранения истории", "History retention"),
                    listOf("30" to t("30 дней", "30 days"), "90" to t("90 дней", "90 days"), "180" to t("180 дней", "180 days"), "365" to t("1 год", "1 year"), "0" to t("Без ограничения", "Unlimited")),
                    cfg.storage.historyDays.toString()) { v ->
                    ConfigStore.update { it.copy(storage = it.storage.copy(historyDays = v.toInt())) }
                    app.simgate.SimGateApp.scope.launch { app.simgate.data.Db.cleanup(ConfigStore.value.storage) }
                }
            })
            ToggleRow(Icons.Rounded.DeleteSweep, t("Удалять и из переписок телефона", "Also delete from phone conversations"), t("При удалении сообщения в веб-панели", "When a message is deleted in the web panel"), cfg.storage.deleteFromPhone, C.Amber) { v ->
                ConfigStore.update { it.copy(storage = it.storage.copy(deleteFromPhone = v)) }
            }
            SettingRow(Icons.Rounded.Lock, t("Секреты зашифрованы", "Secrets are encrypted"), t("Токены и ключи защищены Android Keystore (AES-256)", "Tokens and keys are protected by Android Keystore (AES-256)"), C.Emerald)
            SettingRow(Icons.Rounded.Backup, t("Резервная копия", "Backup"), t("Экспорт и восстановление — в веб-панели: Настройки → Резервная копия", "Export and restore — in the web panel: Settings → Backup"), C.Sky)
        }

        SectionTitle(t("О приложении", "About"))
        Card(padding = 8) {
            SettingRow(Icons.Rounded.Policy, t("Политика конфиденциальности", "Privacy policy"), null, C.Muted, onClick = { dlg = Dlg.Privacy })
            SettingRow(Icons.Rounded.Info, "iSMS GW ${BuildConfig.VERSION_NAME}", t("ID устройства: ${cfg.deviceId}", "Device ID: ${cfg.deviceId}"), C.Muted)
        }
        BrandFooter()
    }

    when (val d = dlg) {
        null -> {}
        is Dlg.Text -> {
            var v by remember(d) { mutableStateOf(d.initial) }
            SimpleDialog(d.title, onDismiss = { dlg = null }, onOk = { d.onSave(v.trim()); dlg = null }) {
                OutlinedTextField(v, { v = it }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = if (d.number) KeyboardType.Number else KeyboardType.Text))
            }
        }
        is Dlg.Choice -> SimpleDialog(d.title, onDismiss = { dlg = null }, onOk = null) {
            d.options.forEach { (k, label) ->
                Row(Modifier.fillMaxWidth().clickable { d.onSave(k); dlg = null }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = d.current == k, onClick = { d.onSave(k); dlg = null }, colors = RadioButtonDefaults.colors(selectedColor = C.Emerald))
                    Text(label, fontSize = 14.sp)
                }
            }
        }
        Dlg.Password -> {
            var p1 by remember { mutableStateOf("") }; var p2 by remember { mutableStateOf("") }
            SimpleDialog(t("Новый пароль панели", "New panel password"), onDismiss = { dlg = null }, onOk = {
                when {
                    p1.length < 8 -> Toast.makeText(ctx, t("Минимум 8 символов", "At least 8 characters"), Toast.LENGTH_SHORT).show()
                    p1 != p2 -> Toast.makeText(ctx, t("Пароли не совпадают", "Passwords do not match"), Toast.LENGTH_SHORT).show()
                    else -> {
                        app.simgate.data.Users.setPassword(app.simgate.data.User.ADMIN_ID, p1)
                        Sessions.dropAll(); Log.sec(t("Пароль веб-панели изменён в приложении", "Web panel password changed in the app")); dlg = null
                        Toast.makeText(ctx, t("Пароль изменён", "Password changed"), Toast.LENGTH_SHORT).show()
                    }
                }
            }) {
                OutlinedTextField(p1, { p1 = it }, label = { Text(t("Пароль", "Password")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = fieldColors(), shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(p2, { p2 = it }, label = { Text(t("Повторите", "Repeat")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = fieldColors(), shape = RoundedCornerShape(14.dp))
            }
        }
        Dlg.Remote -> {
            var en by remember { mutableStateOf(cfg.remote.enabled) }
            var url by remember { mutableStateOf(cfg.remote.url) }
            var token by remember { mutableStateOf(cfg.remote.token) }
            SimpleDialog(t("Подключение к SMS-серверу", "SMS server connection"), onDismiss = { dlg = null }, onOk = {
                if (en && !Regex("^(wss?|https?)://.+", RegexOption.IGNORE_CASE).matches(url.trim())) {
                    Toast.makeText(ctx, t("URL: wss://ваш-сервер/ws", "URL: wss://your-server/ws"), Toast.LENGTH_SHORT).show(); return@SimpleDialog
                }
                ConfigStore.update { it.copy(remote = it.remote.copy(enabled = en, url = url.trim(), token = token.trim())) }
                Gateway.restartRemote(); dlg = null
            }) {
                ToggleRow(Icons.Rounded.CloudSync, t("Включить", "Enable"), null, en, C.Indigo) { en = it }
                OutlinedTextField(url, { url = it }, label = { Text("URL (wss://…)") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(token, { token = it }, label = { Text(t("Токен устройства", "Device token")) }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(14.dp))
                Text(t("Телефон сам подключается к серверу — работает через мобильный интернет и NAT.", "The phone connects to the server itself — works over mobile data and NAT."), color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Dlg.Telegram -> {
            val t = cfg.telegram
            var en by remember { mutableStateOf(t.enabled) }
            var token by remember { mutableStateOf(t.token) }
            var inc by remember { mutableStateOf(t.notifyIncoming) }
            var fail by remember { mutableStateOf(t.notifyFailed) }
            var del by remember { mutableStateOf(t.notifyDelivered) }
            var send by remember { mutableStateOf(t.allowSend) }
            SimpleDialog(t("Telegram-бот", "Telegram bot"), onDismiss = { dlg = null }, onOk = {
                if (en && !Regex("^\\d{5,}:[A-Za-z0-9_-]{30,}$").matches(token.trim())) {
                    Toast.makeText(ctx, t("Неверный токен. Получите его у @BotFather", "Invalid token. Get one from @BotFather"), Toast.LENGTH_LONG).show(); return@SimpleDialog
                }
                ConfigStore.update { it.copy(telegram = it.telegram.copy(enabled = en, token = token.trim(), notifyIncoming = inc, notifyFailed = fail, notifyDelivered = del, allowSend = send)) }
                TelegramBot.restart(); dlg = null
            }) {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    ToggleRow(Icons.AutoMirrored.Rounded.Send, t("Включить бота", "Enable bot"), null, en, C.Sky) { en = it }
                    OutlinedTextField(token, { token = it }, label = { Text(t("Токен от @BotFather", "Token from @BotFather")) }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(14.dp))
                    Text(t("1. Создайте бота у @BotFather → /newbot\n2. Вставьте токен и сохраните\n3. Отправьте боту /start ${t.pairingCode}", "1. Create a bot with @BotFather → /newbot\n2. Paste the token and save\n3. Send the bot /start ${t.pairingCode}"), color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                    ToggleRow(Icons.Rounded.Sms, t("Входящие SMS", "Incoming SMS"), t("Пересылать в Telegram", "Forward to Telegram"), inc, C.Emerald) { inc = it }
                    ToggleRow(Icons.Rounded.Info, t("Ошибки отправки", "Send errors"), null, fail, C.Red) { fail = it }
                    ToggleRow(Icons.Rounded.Info, t("Доставка", "Delivery"), t("Уведомлять о каждом доставленном SMS", "Notify about every delivered SMS"), del, C.Indigo) { del = it }
                    ToggleRow(Icons.Rounded.Security, t("Отправка SMS из бота", "Sending SMS from the bot"), t("Команды /send и ответы", "/send commands and replies"), send, C.Amber) { send = it }
                    if (t.chats.isNotEmpty()) {
                        Text(t("Привязанные чаты", "Linked chats"), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        t.chats.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { ConfigStore.update { it.copy(telegram = it.telegram.copy(chats = it.telegram.chats.filter { x -> x.id != c.id })) } }) { Text(t("Отвязать", "Unlink"), color = C.Red) }
                            }
                        }
                    }
                }
            }
        }
        Dlg.Privacy -> SimpleDialog(t("Конфиденциальность", "Privacy"), onDismiss = { dlg = null }, onOk = { dlg = null }, okText = t("Понятно", "Got it")) {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text(privacyText(), fontSize = 13.sp, lineHeight = 19.sp, color = C.Text)
            }
        }
    }
}

fun privacyText() = t(
    """iSMS GW работает полностью на вашем устройстве. У приложения нет облачного сервиса, аналитики и рекламы; разработчик не получает ваши сообщения, номера или иные данные.

Какие данные обрабатываются: SMS-сообщения (текст, номер, время), сведения о SIM-картах (оператор, номер, слот) — для отображения диалогов и работы шлюза.

Куда передаются: только туда, куда настроите вы — на адреса вебхуков, на ваш SMS-сервер (WebSocket), в ваш Telegram-бот (через серверы Telegram) и клиентам, которые обращаются к API с выданным вами ключом. Передача по HTTPS/WSS шифруется; при использовании HTTP в локальной сети трафик не шифруется — включите HTTPS.

Хранение: в памяти устройства (база ismsgw.db и системное хранилище SMS). Удаление — очистка истории в веб-панели или удаление приложения.

Безопасность: файрвол (по умолчанию только локальная сеть), хешированные пароли и API-ключи, блокировка IP при переборе, ограничение частоты запросов.""",
    """iSMS GW runs entirely on your device. The app has no cloud service, analytics or ads; the developer never receives your messages, numbers or any other data.

Data processed: SMS messages (text, number, time) and SIM card details (carrier, number, slot) — to show conversations and run the gateway.

Where data goes: only where you configure it — your webhook URLs, your SMS server (WebSocket), your Telegram bot (via Telegram servers) and clients that call the API with a key you issued. HTTPS/WSS traffic is encrypted; plain HTTP on the local network is not — enable HTTPS.

Storage: on the device (ismsgw.db database and the system SMS store). Deletion — clear history in the web panel or uninstall the app.

Security: firewall (local network only by default), hashed passwords and API keys, IP blocking on brute force, request rate limiting.""",
)

@Composable
private fun SimpleDialog(title: String, onDismiss: () -> Unit, onOk: (() -> Unit)?, okText: String = t("Сохранить", "Save"), content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.Surface,
        title = { Text(title) },
        text = { Column { content() } },
        confirmButton = { if (onOk != null) TextButton(onClick = onOk) { Text(okText, color = C.Emerald) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Закрыть", "Close"), color = C.Muted) } },
    )
}
