package app.simgate.net

import android.os.Build
import app.simgate.data.t
import app.simgate.BuildConfig
import app.simgate.SimGateApp
import app.simgate.data.ConfigStore
import app.simgate.data.Crypto
import app.simgate.data.Db
import app.simgate.data.Log
import app.simgate.data.Message
import app.simgate.data.TelegramChat
import app.simgate.service.Gateway
import app.simgate.sms.Events
import app.simgate.sms.SendException
import app.simgate.sms.SimManager
import app.simgate.sms.SmsSender
import app.simgate.sms.SmsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Telegram bot: SMS notifications and gateway control.
 *
 * Transport is long polling (getUpdates), so the phone does not need a public IP.
 * Security: the bot only responds to paired chats. Pairing is `/start <code>`;
 * the code is single-use (8 characters, shown in the app/panel). After 5 wrong
 * codes the chat is ignored until the bot restarts.
 */
object TelegramBot {
    enum class State { DISABLED, CONNECTING, ONLINE, ERROR }

    val state = MutableStateFlow(State.DISABLED)
    val lastError = MutableStateFlow("")
    val botName = MutableStateFlow("")

    private var job: Job? = null
    private var offset = 0L
    private var startedAt = 0L
    private val badAttempts = ConcurrentHashMap<Long, Int>()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(65, TimeUnit.SECONDS).build()

    private val cfg get() = ConfigStore.value.telegram

    fun start() {
        stop()
        if (!cfg.enabled || cfg.token.isBlank()) { state.value = State.DISABLED; return }
        startedAt = System.currentTimeMillis() / 1000
        job = SimGateApp.scope.launch {
            state.value = State.CONNECTING
            var backoff = 2_000L
            val me = call("getMe", buildJsonObject {})
            if (me == null) {
                state.value = State.ERROR
            } else {
                botName.value = me.jsonObject["username"]?.jsonPrimitive?.contentOrNull.orEmpty()
                registerCommands()
                Log.i("telegram", t("Бот @${botName.value} запущен", "Bot @${botName.value} started"))
            }
            while (isActive) {
                val updates = call("getUpdates", buildJsonObject {
                    put("offset", offset); put("timeout", 50)
                    putJsonArray("allowed_updates") { add("message"); add("callback_query") }
                })
                if (updates == null) {
                    state.value = State.ERROR
                    delay(backoff); backoff = (backoff * 2).coerceAtMost(60_000); continue
                }
                backoff = 2_000L
                state.value = State.ONLINE
                for (u in updates.jsonArray) {
                    val o = u.jsonObject
                    offset = (o["update_id"]?.jsonPrimitive?.long ?: 0) + 1
                    runCatching { handle(o) }.onFailure { Log.e("telegram", t("Ошибка обработки: ${it.message}", "Update handling error: ${it.message}")) }
                }
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        state.value = State.DISABLED
    }

    fun restart() { if (Gateway.serviceRunning.value) start() else stop() }

    /** Token check without starting the bot (for the "Test" button). */
    fun checkToken(token: String): String? {
        val req = Request.Builder().url("https://api.telegram.org/bot$token/getMe").build()
        return runCatching {
            http.newCall(req).execute().use { r ->
                val o = ConfigStore.json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                if (o["ok"]?.jsonPrimitive?.contentOrNull == "true") "@" + o["result"]!!.jsonObject["username"]!!.jsonPrimitive.content else null
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ Bot API

    private fun call(method: String, body: JsonObject): JsonElement? {
        val token = cfg.token
        if (token.isBlank()) return null
        val req = Request.Builder().url("https://api.telegram.org/bot$token/$method")
            .post(body.toString().toRequestBody(JSON)).build()
        return try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val o = runCatching { ConfigStore.json.parseToJsonElement(text).jsonObject }.getOrNull()
                if (o?.get("ok")?.jsonPrimitive?.contentOrNull == "true") { lastError.value = ""; o["result"] }
                else {
                    val err = o?.get("description")?.jsonPrimitive?.contentOrNull ?: "HTTP ${r.code}"
                    if (lastError.value != err) Log.w("telegram", "$method: $err")
                    lastError.value = err
                    if (r.code == 401 || r.code == 404) lastError.value = t("Неверный токен бота", "Invalid bot token")
                    null
                }
            }
        } catch (e: Exception) {
            lastError.value = e.message ?: e.javaClass.simpleName
            null
        }
    }

    private fun sendText(chatId: Long, html: String, keyboard: JsonArray? = null, forceReply: Boolean = false) {
        call("sendMessage", buildJsonObject {
            put("chat_id", chatId); put("text", html.take(4000)); put("parse_mode", "HTML")
            put("disable_web_page_preview", true)
            when {
                keyboard != null -> put("reply_markup", buildJsonObject { put("inline_keyboard", keyboard) })
                forceReply -> put("reply_markup", buildJsonObject { put("force_reply", true); put("input_field_placeholder", t("Текст SMS", "SMS text")) })
            }
        })
    }

    private fun broadcast(html: String, keyboard: JsonArray? = null) {
        cfg.chats.forEach { c -> SimGateApp.scope.launch { sendText(c.id, html, keyboard) } }
    }

    private fun registerCommands() {
        val en = listOf(
            "status" to "Gateway and SIM status", "send" to "Send SMS: /send number text",
            "last" to "Recent messages", "queue" to "Outgoing queue", "sims" to "SIM cards",
            "server" to "Local server: /server on|off", "ussd" to "USSD request: /ussd *100# [sim]",
            "balance" to "Balance of all SIMs", "calls" to "Recent calls", "menu" to "Menu", "help" to "Help",
        )
        val ru = listOf(
            "status" to "Состояние шлюза и SIM", "send" to "Отправить SMS: /send номер текст",
            "last" to "Последние сообщения", "queue" to "Очередь отправки", "sims" to "SIM-карты",
            "server" to "Локальный сервер: /server on|off", "ussd" to "USSD-запрос: /ussd *100# [sim]",
            "balance" to "Баланс всех SIM", "calls" to "Последние звонки", "menu" to "Меню", "help" to "Справка",
        )
        for ((langCode, list) in listOf(null to en, "ru" to ru)) {
            call("setMyCommands", buildJsonObject {
                putJsonArray("commands") {
                    list.forEach { (c, d) -> addJsonObject { put("command", c); put("description", d) } }
                }
                if (langCode != null) put("language_code", langCode)
            })
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ------------------------------------------------------------------ gateway events

    fun onEvent(event: String, m: Message) {
        if (state.value == State.DISABLED || cfg.chats.isEmpty()) return
        val sim = if (m.sim > 0) " • SIM ${m.sim}" else ""
        when (event) {
            Events.RECEIVED -> if (cfg.notifyIncoming) broadcast(
                t("📩 <b>SMS от ${esc(m.phone)}</b>$sim\n\n${esc(m.text)}", "📩 <b>SMS from ${esc(m.phone)}</b>$sim\n\n${esc(m.text)}"),
                buildJsonArray { addJsonArray { addJsonObject { put("text", t("↩️ Ответить", "↩️ Reply")); put("callback_data", "reply:${m.phone}") } } },
            )
            Events.FAILED -> if (cfg.notifyFailed) broadcast(t("⚠️ <b>Не отправлено</b>", "⚠️ <b>Not sent</b>") + " → ${esc(m.phone)}$sim\n${esc(m.error ?: "")}\n<i>${esc(m.text.take(200))}</i>")
            Events.DELIVERED -> if (cfg.notifyDelivered) broadcast(t("✅ Доставлено → ${esc(m.phone)}$sim", "✅ Delivered → ${esc(m.phone)}$sim"))
        }
    }

    /** Call notifications (incoming / missed). */
    fun onCall(event: String, c: Db.Call, sim: app.simgate.sms.SimInfo) {
        if (state.value == State.DISABLED || cfg.chats.isEmpty()) return
        val num = c.number.ifEmpty { t("скрытый номер", "hidden number") }
        val text = when (event) {
            Events.CALL_RINGING -> t("📞 <b>Входящий звонок</b>", "📞 <b>Incoming call</b>") + "\n${esc(num)} • ${esc(sim.label)}"
            Events.CALL_MISSED -> t("❗ <b>Пропущенный звонок</b>", "❗ <b>Missed call</b>") + "\n${esc(num)} • ${esc(sim.label)}"
            else -> return
        }
        broadcast(text)
    }

    // ------------------------------------------------------------------ incoming updates

    private fun handle(u: JsonObject) {
        u["callback_query"]?.jsonObject?.let { cb -> onCallback(cb); return }
        val msg = u["message"]?.jsonObject ?: return
        val chat = msg["chat"]?.jsonObject ?: return
        val chatId = chat["id"]?.jsonPrimitive?.long ?: return
        val date = msg["date"]?.jsonPrimitive?.longOrNull ?: 0
        if (date < startedAt - 120) return // stale messages accumulated while the bot was off
        val text = msg["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: return
        val name = listOfNotNull(
            chat["first_name"]?.jsonPrimitive?.contentOrNull, chat["last_name"]?.jsonPrimitive?.contentOrNull,
            chat["username"]?.jsonPrimitive?.contentOrNull?.let { "@$it" }, chat["title"]?.jsonPrimitive?.contentOrNull,
        ).joinToString(" ").ifBlank { chatId.toString() }

        val authorized = cfg.chats.any { it.id == chatId }
        if (!authorized) { pairing(chatId, name, text); return }

        // A reply to an SMS notification → send an SMS to that number.
        val replyTo = msg["reply_to_message"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
        if (replyTo != null && !text.startsWith("/")) {
            val phone = Regex("(\\+?\\d{3,20})").find(replyTo.lineSequence().firstOrNull().orEmpty())?.value
            if (phone != null) { doSend(chatId, phone, text, 0); return }
        }
        command(chatId, text)
    }

    private fun pairing(chatId: Long, name: String, text: String) {
        val tries = badAttempts[chatId] ?: 0
        if (tries >= 5) return
        val code = text.removePrefix("/start").removePrefix("/pair").trim()
        if (code.isEmpty()) {
            sendText(chatId, t(
                "🔒 Это приватный бот iSMS GW.\nОтправьте <code>/start КОД</code> — код привязки показан в приложении iSMS GW (Настройки → Telegram) и в веб-панели.",
                "🔒 This is a private iSMS GW bot.\nSend <code>/start CODE</code> — the pairing code is shown in the iSMS GW app (Settings → Telegram) and in the web panel.",
            ))
            return
        }
        if (Crypto.constantEquals(code, cfg.pairingCode)) {
            ConfigStore.update { c ->
                c.copy(telegram = c.telegram.copy(
                    chats = c.telegram.chats.filter { it.id != chatId } + TelegramChat(chatId, name.take(60)),
                    pairingCode = Crypto.randomToken(8), // single-use
                ))
            }
            badAttempts.remove(chatId)
            Log.sec(t("Telegram: привязан чат $name ($chatId)", "Telegram: chat $name ($chatId) paired"))
            sendText(chatId, t(
                "✅ Чат привязан к <b>${esc(ConfigStore.value.deviceName)}</b>.\nТеперь сюда будут приходить SMS, а шлюзом можно управлять командами.",
                "✅ Chat paired with <b>${esc(ConfigStore.value.deviceName)}</b>.\nIncoming SMS will arrive here, and you can control the gateway with commands.",
            ), menu())
        } else {
            badAttempts[chatId] = tries + 1
            Log.sec(t("Telegram: неверный код привязки от $name ($chatId) [${tries + 1}/5]", "Telegram: wrong pairing code from $name ($chatId) [${tries + 1}/5]"))
            sendText(chatId, if (tries + 1 >= 5) t("⛔ Слишком много попыток. Чат заблокирован.", "⛔ Too many attempts. Chat blocked.") else t("❌ Неверный код привязки.", "❌ Invalid pairing code."))
        }
    }

    private fun menu() = buildJsonArray {
        addJsonArray {
            addJsonObject { put("text", t("📊 Статус", "📊 Status")); put("callback_data", "status") }
            addJsonObject { put("text", t("📶 SIM-карты", "📶 SIM cards")); put("callback_data", "sims") }
        }
        addJsonArray {
            addJsonObject { put("text", t("💬 Последние", "💬 Recent")); put("callback_data", "last") }
            addJsonObject { put("text", t("⏳ Очередь", "⏳ Queue")); put("callback_data", "queue") }
        }
        addJsonArray { addJsonObject { put("text", t("✉️ Написать SMS", "✉️ New SMS")); put("callback_data", "compose") } }
    }

    private fun onCallback(cb: JsonObject) {
        val id = cb["id"]?.jsonPrimitive?.contentOrNull ?: return
        val chatId = cb["message"]?.jsonObject?.get("chat")?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull ?: return
        call("answerCallbackQuery", buildJsonObject { put("callback_query_id", id) })
        if (cfg.chats.none { it.id == chatId }) return
        val data = cb["data"]?.jsonPrimitive?.contentOrNull ?: return
        when {
            data.startsWith("reply:") -> sendText(chatId, t("↩️ Ответ на", "↩️ Reply to") + " ${esc(data.removePrefix("reply:"))}\n" + t("Напишите текст ответом на это сообщение.", "Type your text as a reply to this message."), forceReply = true)
            data == "compose" -> sendText(chatId, t(
                "Отправьте команду:\n<code>/send +79991234567 Текст</code>\n<code>/send2 +79991234567 Текст</code> — через SIM 2",
                "Send a command:\n<code>/send +79991234567 Text</code>\n<code>/send2 +79991234567 Text</code> — via SIM 2",
            ))
            else -> command(chatId, "/$data")
        }
    }

    private fun command(chatId: Long, text: String) {
        val cmd = text.substringBefore(' ').substringBefore('@').lowercase()
        val args = text.substringAfter(' ', "").trim()
        when (cmd) {
            "/start", "/menu" -> sendText(chatId, "📱 <b>${esc(ConfigStore.value.deviceName)}</b> — iSMS GW ${BuildConfig.VERSION_NAME}", menu())
            "/help" -> sendText(chatId, t(HELP_RU, HELP_EN))
            "/status" -> sendText(chatId, statusText(), menu())
            "/sims" -> sendText(chatId, simsText())
            "/last" -> sendText(chatId, lastText(args.toIntOrNull()?.coerceIn(1, 20) ?: 5))
            "/queue" -> {
                val q = Db.list(direction = "out", status = "queued", limit = 20, ascending = true)
                sendText(chatId, if (q.isEmpty()) t("Очередь пуста ✨", "Queue is empty ✨") else t("⏳ <b>В очереди: ${q.size}</b>\n", "⏳ <b>Queued: ${q.size}</b>\n") + q.joinToString("\n") { "#${it.id} → ${esc(it.phone)}: ${esc(it.text.take(40))}" })
            }
            "/send", "/send1", "/send2", "/send3" -> {
                val sim = cmd.removePrefix("/send").toIntOrNull() ?: 0
                val phone = args.substringBefore(' ').trim()
                val body = args.substringAfter(' ', "").trim()
                if (phone.isEmpty() || body.isEmpty()) sendText(chatId, t("Формат: <code>$cmd +79991234567 Текст сообщения</code>", "Format: <code>$cmd +79991234567 Message text</code>"))
                else doSend(chatId, phone, body, sim)
            }
            "/server" -> when (args.lowercase()) {
                "on", "off" -> {
                    val on = args.equals("on", true)
                    ConfigStore.update { it.copy(server = it.server.copy(enabled = on)) }
                    Gateway.restartServerLater()
                    Log.sec(if (on) t("Telegram: локальный сервер включён", "Telegram: local server enabled") else t("Telegram: локальный сервер выключен", "Telegram: local server disabled"))
                    sendText(chatId, if (on) t("🟢 Локальный сервер включается…", "🟢 Local server is starting…") else t("🔴 Локальный сервер выключен", "🔴 Local server is off"))
                }
                else -> sendText(chatId, t("Локальный сервер: ", "Local server: ") + (if (Gateway.server.value.running) t("🟢 работает", "🟢 running") + "\n" + Gateway.server.value.urls.joinToString("\n") else t("🔴 выключен", "🔴 off")) + "\n\n<code>/server on</code> | <code>/server off</code>")
            }
            "/ussd" -> {
                val code = args.substringBefore(' ').trim()
                val simArg = args.substringAfter(' ', "").trim().toIntOrNull() ?: 0
                if (code.isEmpty()) sendText(chatId, t("Формат: <code>/ussd *100# [номер SIM]</code>", "Format: <code>/ussd *100# [SIM number]</code>"))
                else runUssd(chatId, code, simArg)
            }
            "/balance" -> {
                val sims = SimManager.active().filter { it.balanceCode().isNotEmpty() }
                if (sims.isEmpty()) sendText(chatId, t("Коды баланса не заданы. Укажите их в веб-панели: SIM-карты → Код баланса.", "Balance codes are not set. Set them in the web panel: SIM cards → Balance code."))
                else sims.forEach { runUssd(chatId, it.balanceCode(), it.slot) }
            }
            "/calls" -> {
                val list = Db.listCalls(null, 10)
                val f = SimpleDateFormat("dd.MM HH:mm", Locale.US)
                sendText(chatId, if (list.isEmpty()) t("Звонков пока нет", "No calls yet") else t("📞 <b>Последние звонки</b>\n\n", "📞 <b>Recent calls</b>\n\n") +
                    list.joinToString("\n") { c ->
                        val icon = when (c.status) { "missed" -> "❗"; "answered", "ended" -> if (c.direction == "in") "↙️" else "↗️"; else -> "•" }
                        "$icon ${esc(c.number.ifEmpty { "?" })} · SIM ${c.slot} · ${f.format(Date(c.startedAt))}" + if (c.durationSec > 0) " · ${c.durationSec / 60}:${"%02d".format(c.durationSec % 60)}" else ""
                    })
            }
            "/unpair" -> {
                ConfigStore.update { c -> c.copy(telegram = c.telegram.copy(chats = c.telegram.chats.filter { it.id != chatId })) }
                Log.sec(t("Telegram: чат $chatId отвязан", "Telegram: chat $chatId unpaired"))
                sendText(chatId, t("Чат отвязан. Уведомления больше не придут.", "Chat unpaired. You will no longer receive notifications."))
            }
            else -> sendText(chatId, t("Неизвестная команда. /help — справка", "Unknown command. /help — help"), menu())
        }
    }

    private fun app.simgate.sms.SimInfo.balanceCode() = ConfigStore.value.sims.firstOrNull { it.simId == simId }?.balanceCode.orEmpty()

    private fun runUssd(chatId: Long, code: String, slot: Int) {
        val sim = if (slot > 0) SimManager.activeBySlot(slot) else SimManager.active().firstOrNull { it.isDefault } ?: SimManager.active().firstOrNull()
        if (sim == null) { sendText(chatId, t("❌ SIM не найдена", "❌ SIM not found")); return }
        sendText(chatId, "⏳ USSD <code>${esc(code)}</code> → ${esc(sim.label)}…")
        SimGateApp.scope.launch {
            val msg = try {
                val rec = app.simgate.sms.UssdService.run(code, sim, "telegram", app.simgate.data.User.ADMIN_ID)
                if (rec.status == "ok") "📟 <b>${esc(sim.label)}</b> · <code>${esc(code)}</code>\n\n${esc(rec.response.orEmpty())}"
                else "❌ <b>${esc(sim.label)}</b> · <code>${esc(code)}</code>\n${esc(rec.error.orEmpty())}"
            } catch (e: Exception) { "❌ ${esc(e.message ?: "USSD error")}" }
            sendText(chatId, msg)
        }
    }

    private fun doSend(chatId: Long, phone: String, text: String, sim: Int) {
        if (!cfg.allowSend) { sendText(chatId, t("⛔ Отправка SMS из Telegram отключена в настройках.", "⛔ Sending SMS from Telegram is disabled in settings.")); return }
        try {
            val m = SmsSender.enqueue(phone, text, sim, "telegram", null)
            sendText(chatId, t("📤 В очереди", "📤 Queued") + " #${m.id} → ${esc(m.phone)}${if (sim > 0) " (SIM $sim)" else ""}")
        } catch (e: SendException) {
            sendText(chatId, "❌ ${esc(e.message ?: t("Ошибка", "Error"))}")
        }
    }

    private fun statusText(): String {
        val st = Db.stats()
        val (bat, charging) = Gateway.battery()
        val up = (System.currentTimeMillis() - Gateway.startedAt.value) / 1000
        val srv = Gateway.server.value
        return buildString {
            append("📊 <b>${esc(ConfigStore.value.deviceName)}</b>\n")
            append("${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE}\n\n")
            append("🔋 $bat%${if (charging) " ⚡" else ""}   ⏱ ${up / 3600}" + t("ч", "h") + " ${up % 3600 / 60}" + t("м", "m") + "\n")
            append(t("📨 SMS-приложение: ", "📨 SMS app: ") + (if (SmsStore.isDefaultApp()) "✅" else t("⚠️ не по умолчанию", "⚠️ not default")) + "\n")
            append(t("🌐 Сервер: ", "🌐 Server: ") + (if (srv.running) srv.urls.firstOrNull() ?: t("работает", "running") else t("выключен", "off")) + "\n")
            append(t("☁️ SMS-сервер: ", "☁️ SMS server: ") + "${RemoteClient.state.value.name.lowercase()}\n\n")
            append(t(
                "<b>Сегодня</b>\n↗️ отправлено ${st.sentToday}   ↙️ получено ${st.receivedToday}\n⏳ в очереди ${st.queued}   ⚠️ ошибок ${st.failedToday}",
                "<b>Today</b>\n↗️ sent ${st.sentToday}   ↙️ received ${st.receivedToday}\n⏳ queued ${st.queued}   ⚠️ failed ${st.failedToday}",
            ))
        }
    }

    private fun simsText(): String {
        val sims = SimManager.list()
        if (sims.isEmpty()) return t("SIM-карты не найдены", "No SIM cards found")
        val used = Db.sentTodayBySim()
        return t("📶 <b>SIM-карты</b>\n\n", "📶 <b>SIM cards</b>\n\n") + sims.joinToString("\n\n") { s ->
            "<b>SIM ${s.slot}</b> — ${esc(s.label)}${if (s.isDefault) " ⭐" else ""}\n${esc(listOf(s.carrier, s.number).filter { it.isNotBlank() }.joinToString(" • "))}\n" +
                t("отправка", "send") + " ${if (s.sendEnabled) "✅" else "⛔"}  " + t("приём", "receive") + " ${if (s.receiveEnabled) "✅" else "⛔"}  " + t("сегодня", "today") + " ${used[s.simId] ?: 0}${if (s.dailyLimit > 0) "/${s.dailyLimit}" else ""}"
        }
    }

    private fun lastText(n: Int): String {
        val list = Db.list(limit = n)
        if (list.isEmpty()) return t("Сообщений пока нет", "No messages yet")
        val f = SimpleDateFormat("dd.MM HH:mm", Locale.US)
        return t("💬 <b>Последние $n</b>\n\n", "💬 <b>Last $n</b>\n\n") + list.joinToString("\n\n") { m ->
            "${if (m.direction == "in") "↙️" else "↗️"} <b>${esc(m.phone)}</b> · ${f.format(Date(m.createdAt))} · ${m.status}\n${esc(m.text.take(300))}"
        }
    }

    private const val HELP_EN = """<b>iSMS GW commands</b>

/status — gateway status
/sims — SIM cards
/send <i>number text</i> — send SMS
/send1, /send2 — via a specific SIM
/last [N] — recent messages
/queue — outgoing queue
/server on|off — local server
/ussd <i>*100#</i> [sim] — USSD request
/balance — balance of all SIMs
/calls — recent calls
/unpair — unpair this chat

💡 Reply to an SMS notification — your reply goes to the sender."""

    private const val HELP_RU = """<b>Команды iSMS GW</b>

/status — состояние шлюза
/sims — SIM-карты
/send <i>номер текст</i> — отправить SMS
/send1, /send2 — через конкретную SIM
/last [N] — последние сообщения
/queue — очередь отправки
/server on|off — локальный сервер
/ussd <i>*100#</i> [sim] — USSD-запрос
/balance — баланс всех SIM
/calls — последние звонки
/unpair — отвязать этот чат

💡 Ответьте (reply) на уведомление о SMS — ответ уйдёт отправителю."""
}
