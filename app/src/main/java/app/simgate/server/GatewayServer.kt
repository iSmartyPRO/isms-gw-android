package app.simgate.server

import android.content.Context
import app.simgate.data.t
import android.os.Build
import app.simgate.BuildConfig
import app.simgate.data.Access
import app.simgate.data.ApiKey
import app.simgate.data.User
import app.simgate.data.Users
import app.simgate.data.ConfigStore
import app.simgate.data.Crypto
import app.simgate.data.Db
import app.simgate.data.FirewallConfig
import app.simgate.data.I18n
import app.simgate.data.Log
import app.simgate.data.ReceivingConfig
import app.simgate.data.SendingConfig
import app.simgate.data.SimConfig
import app.simgate.data.Webhook
import app.simgate.net.RemoteClient
import app.simgate.net.TelegramBot
import app.simgate.net.WebhookDispatcher
import app.simgate.service.Gateway
import app.simgate.sms.Events
import app.simgate.sms.SendException
import app.simgate.sms.SimManager
import app.simgate.sms.SmsSender
import app.simgate.sms.SmsStore
import app.simgate.sms.SimInfo
import app.simgate.sms.SimMemory
import app.simgate.sms.UssdService
import app.simgate.sms.CallService
import app.simgate.data.Backup
import app.simgate.data.StorageConfig
import app.simgate.data.CallsConfig
import kotlinx.coroutines.runBlocking
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class GatewayServer(private val ctx: Context, port: Int) : NanoHTTPD(port) {
    private val json = ConfigStore.json
    private inline fun <reified T> enc(v: T): JsonElement = json.encodeToJsonElement(v)

    /** Request language: X-ISMS-Lang header (sent by the web panel), else Accept-Language, else app language. */
    override fun serve(session: IHTTPSession): Response {
        val headers = session.headers ?: emptyMap()
        val lang = headers["x-isms-lang"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: headers["accept-language"]?.trim()?.takeIf { it.length >= 2 }?.take(2)
        return I18n.with(lang) { handle(session) }
    }

    private fun handle(session: IHTTPSession): Response {
        val ip = Firewall.normalize(session.remoteIpAddress ?: "")
        val uri = session.uri ?: "/"
        val isApi = uri.startsWith("/api/")
        val cfg = ConfigStore.value
        val started = System.currentTimeMillis()

        if (!isApi && !cfg.server.webAdmin) return secure(Http.error(404, t("Веб-панель отключена", "Web panel is disabled")), false)

        val verdict = Firewall.check(ip, admin = !isApi)
        if (verdict is Firewall.Verdict.Deny) {
            if (verdict.code != 429) Log.sec(t("Отклонён запрос $ip ${session.method} $uri: ${verdict.reason}", "Rejected request $ip ${session.method} $uri: ${verdict.reason}"))
            return secure(Http.error(verdict.code, if (verdict.code == 429) t("Слишком много запросов", "Too many requests") else t("Доступ запрещён", "Access denied")), isApi)
        }

        val req = Req(session, ip)
        val resp = try {
            when {
                isApi -> api(req)
                uri.startsWith("/admin/api/") -> admin(req)
                else -> static(uri)
            }
        } catch (e: ApiError) {
            Http.error(e.code, e.message ?: t("Ошибка", "Error"))
        } catch (e: SendException) {
            Http.error(422, e.message ?: t("Ошибка отправки", "Send error"))
        } catch (e: UssdService.UssdException) {
            Http.error(422, e.message ?: "USSD")
        } catch (e: CallService.CallException) {
            Http.error(422, e.message ?: "Call")
        } catch (e: SimMemory.Unsupported) {
            Http.error(422, e.message ?: "SIM")
        } catch (e: Backup.BadBackup) {
            Http.error(400, e.message ?: "Backup")
        } catch (e: Exception) {
            Log.e("server", "500 ${session.method} $uri: ${e.javaClass.simpleName} ${e.message}")
            Http.error(500, t("Внутренняя ошибка", "Internal error"))
        }
        if (isApi) Log.i("api", "${session.method} $uri → ${resp.status.requestStatus} ($ip, ${System.currentTimeMillis() - started} " + t("мс", "ms") + ")")
        return secure(resp, isApi)
    }

    private fun secure(r: Response, api: Boolean): Response {
        r.addHeader("X-Content-Type-Options", "nosniff")
        r.addHeader("X-Frame-Options", "DENY")
        r.addHeader("Referrer-Policy", "no-referrer")
        r.addHeader("Cross-Origin-Resource-Policy", "same-origin")
        r.addHeader("Server", "iSMS-GW")
        if (api || r.mimeType?.startsWith("application/json") == true) r.addHeader("Cache-Control", "no-store")
        if (r.mimeType?.startsWith("text/html") == true) {
            r.addHeader("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        }
        return r
    }

    // ======================= Web panel static files =======================

    private fun static(uri: String): Response {
        val path = if (uri == "/" || uri.isEmpty()) "index.html" else uri.trimStart('/')
        if (path.contains("..") || path.contains('\\')) return Http.error(400, "Bad path")
        val mime = when (path.substringAfterLast('.', "")) {
            "html" -> "text/html; charset=utf-8"; "css" -> "text/css; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"; "svg" -> "image/svg+xml"
            "png" -> "image/png"; "ico" -> "image/x-icon"; "json" -> Http.JSON_MIME
            else -> "application/octet-stream"
        }
        return try {
            val bytes = ctx.assets.open("web/$path").use { it.readBytes() }
            newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong()).apply {
                addHeader("Cache-Control", "no-cache") // always serve a fresh panel after an app update
            }
        } catch (e: Exception) {
            // SPA: unknown paths are served index.html
            if (!path.contains('.')) static("/") else Http.error(404, "Not found")
        }
    }

    // ======================= Public REST API (keys) =======================

    private fun apiKey(r: Req, scope: String): Access {
        val raw = r.header("authorization")?.let { h ->
            if (h.startsWith("Bearer ", ignoreCase = true)) h.substring(7).trim() else null
        } ?: r.header("x-api-key")?.trim()
        if (raw.isNullOrEmpty()) throw ApiError(401, t("Требуется API-ключ (Authorization: Bearer …)", "API key required (Authorization: Bearer …)"))
        val hash = Crypto.sha256Hex(raw)
        val key = ConfigStore.value.apiKeys.firstOrNull { Crypto.constantEquals(it.hash, hash) }
        if (key == null || !key.enabled) {
            Firewall.authFailed(r.ip, t("API-ключ", "API key"))
            throw ApiError(401, t("Неверный или отключённый API-ключ", "Invalid or disabled API key"))
        }
        if (key.allowedIps.isNotEmpty()) {
            val addr = Firewall.parse(r.ip)
            if (addr == null || key.allowedIps.none { Firewall.matches(addr, it) }) {
                Log.sec(t("Ключ «${key.name}» использован с недопустимого IP ${r.ip}", "Key \"${key.name}\" used from a disallowed IP ${r.ip}"))
                throw ApiError(403, t("Ключ не разрешён для этого IP", "Key is not allowed for this IP"))
            }
        }
        val access = Users.accessForKey(key) ?: throw ApiError(403, t("Владелец ключа отключён или не имеет доступа к API", "The key owner is disabled or has no API access"))
        val allowed = when (scope) { "send" -> access.canSend; "read" -> access.canRead; else -> false }
        if (scope !in key.scopes || !allowed) throw ApiError(403, t("У ключа нет права '$scope'", "Key lacks the '$scope' permission"))
        Firewall.authOk(r.ip)
        val now = System.currentTimeMillis()
        if (now - key.lastUsedAt > 60_000 || key.lastIp != r.ip) {
            ConfigStore.update { c -> c.copy(apiKeys = c.apiKeys.map { if (it.id == key.id) it.copy(lastUsedAt = now, lastIp = r.ip) else it }) }
        }
        return access
    }

    /** SIM from the simId / sim (slot) fields; if not specified, the default SIM among the accessible ones. */
    private fun simFrom(r: Req, a: Access, requireActive: Boolean = true): SimInfo {
        val simId = r.body["simId"]?.jsonPrimitive?.intOrNull ?: r.q("simId")?.toIntOrNull() ?: -1
        val slot = r.body["sim"]?.jsonPrimitive?.let { it.intOrNull ?: it.content.removePrefix("sim").toIntOrNull() } ?: r.q("sim")?.toIntOrNull() ?: 0
        val pool = SimManager.list().filter { a.allows(it.simId) && (!requireActive || it.active) }
        val sim = when {
            simId > 0 -> pool.find { it.simId == simId }
            slot > 0 -> pool.find { it.slot == slot }
            else -> pool.find { it.isDefault } ?: pool.firstOrNull()
        }
        return sim ?: throw ApiError(404, t("SIM не найдена или нет доступа", "SIM not found or no access"))
    }

    private fun simById(id: Int, a: Access): SimInfo =
        SimManager.list().find { it.simId == id && a.allows(id) } ?: throw ApiError(404, t("SIM не найдена или нет доступа", "SIM not found or no access"))

    private fun ussdRun(r: Req, a: Access, source: String, code: String? = null): JsonElement {
        if (!a.canUssd) throw ApiError(403, t("Нет права на USSD-запросы", "No permission for USSD requests"))
        val sim = simFrom(r, a)
        val c = code ?: r.requireStr("code")
        val rec = runBlocking { UssdService.run(c, sim, source, a.user.id) }
        return enc(rec)
    }

    private fun callDial(r: Req, a: Access, source: String): JsonElement {
        if (!a.canCall) throw ApiError(403, t("Нет права на звонки", "No permission for calls"))
        val to = r.str("to") ?: r.requireStr("phone")
        return enc(CallService.dial(to, simFrom(r, a), source, a.user.id))
    }

    private fun scopeOf(a: Access) = if (a.isAdmin) null else Db.Scope(a.sims, a.user.id, a.canRead)

    private fun simsFor(a: Access) = SimManager.list().filter { a.allows(it.simId) }

    private fun checkMessage(a: Access, m: app.simgate.data.Message) {
        if (a.isAdmin) return
        val visible = (m.direction == "out" && m.owner == a.user.id) || (a.canRead && a.allows(m.simId))
        if (!visible) throw ApiError(404, t("Сообщение не найдено", "Message not found"))
    }

    private fun api(r: Req): Response {
        val s = r.seg // ["api","v1",...]
        if (s.getOrNull(1) != "v1") throw ApiError(404, t("Неизвестная версия API", "Unknown API version"))
        val res = s.drop(2)
        return when {
            res == listOf("status") && r.method == "GET" -> { val a = apiKey(r, "read"); Http.json(statusJson(a)) }
            res == listOf("sims") && r.method == "GET" -> { val a = apiKey(r, "read"); Http.json(enc(simsFor(a))) }
            res == listOf("sms") && r.method == "POST" -> { val a = apiKey(r, "send"); Http.json(sendFromBody(r, "api", a)) }
            res == listOf("sms") && r.method == "GET" -> {
                val a = apiKey(r, "read")
                val list = Db.list(
                    direction = r.q("direction")?.takeIf { it == "in" || it == "out" },
                    status = r.q("status"), sinceId = r.q("since_id")?.toLongOrNull() ?: 0,
                    unackedOnly = r.q("unacked") == "1" || r.q("unacked") == "true",
                    limit = r.qi("limit", 50), ascending = r.q("since_id") != null, scope = scopeOf(a),
                )
                Http.json(buildJsonObject { put("messages", enc(list)) })
            }
            res == listOf("sms", "ack") && r.method == "POST" -> {
                val a = apiKey(r, "read")
                val ids = (r.body["ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.longOrNull } ?: throw ApiError(400, t("Поле 'ids' обязательно", "Field 'ids' is required")))
                    .filter { id -> Db.get(id)?.let { m -> runCatching { checkMessage(a, m) }.isSuccess } == true }
                Http.ok("acked" to Db.ack(ids))
            }
            res == listOf("ussd") && r.method == "POST" -> { val a = apiKey(r, "ussd"); Http.json(ussdRun(r, a, "api")) }
            res.size == 2 && res[0] == "ussd" && r.method == "GET" -> {
                val a = apiKey(r, "ussd")
                val u = res[1].toLongOrNull()?.let { Db.getUssd(it) }?.takeIf { a.allows(it.simId) } ?: throw ApiError(404, t("Запрос не найден", "Request not found"))
                Http.json(enc(u))
            }
            res == listOf("calls") && r.method == "GET" -> {
                val a = apiKey(r, "call")
                Http.json(buildJsonObject { put("calls", enc(Db.listCalls(a.sims, r.qi("limit", 50)))) })
            }
            res == listOf("calls") && r.method == "POST" -> { val a = apiKey(r, "call"); Http.json(callDial(r, a, "api")) }
            res.size == 3 && res[0] == "sims" && res[2] == "messages" && r.method == "GET" -> {
                val a = apiKey(r, "read")
                val sim = simById(res[1].toIntOrNull() ?: -1, a)
                Http.json(buildJsonObject { put("simId", sim.simId); put("messages", enc(SimMemory.list(sim))) })
            }
            res.size == 4 && res[0] == "sims" && res[2] == "messages" && r.method == "DELETE" -> {
                val a = apiKey(r, "send")
                val sim = simById(res[1].toIntOrNull() ?: -1, a)
                Http.ok("deleted" to SimMemory.delete(sim, res[3].toIntOrNull() ?: throw ApiError(400, "index")))
            }
            res.size == 2 && res[0] == "sms" && r.method == "GET" -> {
                val a = apiKey(r, "read")
                val m = res[1].toLongOrNull()?.let { Db.get(it) } ?: throw ApiError(404, t("Сообщение не найдено", "Message not found"))
                checkMessage(a, m)
                Http.json(enc(m))
            }
            else -> throw ApiError(404, t("Метод не найден", "Method not found"))
        }
    }

    private fun sendFromBody(r: Req, source: String, a: Access): JsonElement {
        if (!a.canSend) throw ApiError(403, t("Нет права на отправку SMS", "No permission to send SMS"))
        val text = r.requireStr("text")
        val sim = r.body["sim"]?.jsonPrimitive?.let { it.intOrNull ?: it.content.removePrefix("sim").toIntOrNull() } ?: 0
        val simId = r.body["simId"]?.jsonPrimitive?.intOrNull ?: -1
        val ref = r.str("ref")
        val toEl = r.body["to"] ?: r.body["phone"] ?: throw ApiError(400, t("Поле 'to' обязательно", "Field 'to' is required"))
        val phones = when (toEl) {
            is JsonArray -> toEl.map { it.jsonPrimitive.content }
            else -> toEl.jsonPrimitive.content.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        }
        if (phones.isEmpty()) throw ApiError(400, t("Не указаны получатели", "No recipients specified"))
        if (phones.size > 100) throw ApiError(400, t("Не более 100 получателей за запрос", "At most 100 recipients per request"))
        phones.forEach { SmsSender.validatePhone(it) ?: throw ApiError(400, t("Некорректный номер: $it", "Invalid number: $it")) }
        val msgs = phones.map { SmsSender.enqueue(it, text, sim, source, ref, simId, a.user.id, a.sims) }
        return buildJsonObject {
            put("ok", true)
            put("messages", buildJsonArray { msgs.forEach { add(buildJsonObject { put("id", it.id); put("to", it.phone); put("status", it.status); put("simId", it.simId) }) } })
        }
    }

    private fun statusJson(a: Access): JsonObject {
        val cfg = ConfigStore.value
        val (bat, charging) = Gateway.battery()
        return buildJsonObject {
            put("device", buildJsonObject {
                put("id", cfg.deviceId); put("name", cfg.deviceName)
                put("model", "${Build.MANUFACTURER} ${Build.MODEL}"); put("android", Build.VERSION.RELEASE)
                put("app", BuildConfig.VERSION_NAME); put("battery", bat); put("charging", charging)
                put("defaultSmsApp", SmsStore.isDefaultApp())
                put("uptimeSec", if (Gateway.startedAt.value > 0) (System.currentTimeMillis() - Gateway.startedAt.value) / 1000 else 0)
            })
            put("sims", enc(simsFor(a)))
            put("stats", enc(Db.stats(scopeOf(a))))
        }
    }

    // ======================= Web panel admin API =======================

    private fun cookie(r: Req) = r.s.cookies.read("sg_sid")

    /** Web panel session user. */
    private fun requireUser(r: Req): Access {
        val uid = Sessions.userId(cookie(r), r.ip) ?: throw ApiError(401, t("Требуется вход", "Login required"))
        val u = Users.find(uid)?.takeIf { it.enabled && (it.isAdmin || it.canWeb) }
            ?: run { Sessions.drop(cookie(r)); throw ApiError(401, t("Требуется вход", "Login required")) }
        // CSRF: state-changing requests must carry a header that a browser will not send cross-origin without CORS.
        if (r.method != "GET" && r.header("x-isms-csrf") != "1") throw ApiError(403, "CSRF")
        return Users.access(u)
    }

    private fun adminOnly(a: Access) { if (!a.isAdmin) throw ApiError(403, t("Доступно только администратору", "Administrator only")) }

    private fun userJson(u: User) = buildJsonObject {
        put("id", u.id); put("username", u.username); put("displayName", u.displayName); put("role", u.role)
        put("enabled", u.enabled); put("canSend", u.canSend || u.isAdmin); put("canRead", u.canRead || u.isAdmin)
        put("canUseApi", u.canUseApi || u.isAdmin); put("canWeb", u.canWeb || u.isAdmin)
        put("canUssd", u.canUssd || u.isAdmin); put("canCall", u.canCall || u.isAdmin)
        put("allSims", u.allSims || u.isAdmin); put("sims", enc(u.sims))
        put("createdAt", u.createdAt); put("lastLoginAt", u.lastLoginAt); put("lastLoginIp", u.lastLoginIp)
        put("apiKeys", ConfigStore.value.apiKeys.count { it.ownerId == u.id })
    }

    private fun simIds(el: JsonElement?): List<Int> =
        (el as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }?.distinct().orEmpty()

    private fun usersApi(r: Req, s: List<String>, me: Access): Response {
        adminOnly(me)
        val m = r.method
        return when {
            s.size == 1 && m == "GET" -> Http.json(buildJsonArray { Users.all().forEach { add(userJson(it)) } })
            s.size == 1 && m == "POST" -> {
                val username = r.requireStr("username").trim()
                if (!Users.validUsername(username)) throw ApiError(400, t("Логин: 3–32 символа, латиница, цифры, . _ -", "Username: 3–32 chars, latin letters, digits, . _ -"))
                if (Users.byUsername(username) != null) throw ApiError(409, t("Такой логин уже есть", "This username is taken"))
                val pwd = r.requireStr("password")
                if (pwd.length < 8) throw ApiError(400, t("Пароль: минимум 8 символов", "Password: at least 8 characters"))
                val (hash, salt) = Crypto.hashPassword(pwd)
                val u = User(
                    id = Crypto.randomToken(10), username = username, displayName = r.str("displayName")?.take(60).orEmpty(),
                    role = User.ROLE_USER, hash = hash, salt = salt,
                    canSend = r.bool("canSend") ?: true, canRead = r.bool("canRead") ?: true,
                    canUseApi = r.bool("canUseApi") ?: true, canWeb = r.bool("canWeb") ?: true,
                    canUssd = r.bool("canUssd") ?: false, canCall = r.bool("canCall") ?: false,
                    allSims = r.bool("allSims") ?: false, sims = simIds(r.body["sims"]),
                )
                ConfigStore.update { it.copy(users = it.users + u) }
                Log.sec(t("Создан пользователь ${u.username}", "User ${u.username} created"))
                Http.json(userJson(u))
            }
            s.size == 2 && m == "PUT" -> {
                val target = Users.find(s[1]) ?: throw ApiError(404, t("Пользователь не найден", "User not found"))
                if (target.isAdmin) {
                    // for the administrator only the name and login can be changed
                    val name = r.str("username")?.trim()
                    if (name != null && name != target.username) {
                        if (!Users.validUsername(name)) throw ApiError(400, t("Логин: 3–32 символа, латиница, цифры, . _ -", "Username: 3–32 chars, latin letters, digits, . _ -"))
                        if (Users.byUsername(name) != null) throw ApiError(409, t("Такой логин уже есть", "This username is taken"))
                    }
                    ConfigStore.update { c -> c.copy(users = c.users.map { if (it.id != target.id) it else it.copy(
                        username = name ?: it.username, displayName = r.str("displayName")?.take(60) ?: it.displayName) }) }
                } else {
                    ConfigStore.update { c -> c.copy(users = c.users.map { u -> if (u.id != target.id) u else u.copy(
                        displayName = r.str("displayName")?.take(60) ?: u.displayName,
                        enabled = r.bool("enabled") ?: u.enabled, canSend = r.bool("canSend") ?: u.canSend,
                        canRead = r.bool("canRead") ?: u.canRead, canUseApi = r.bool("canUseApi") ?: u.canUseApi,
                        canWeb = r.bool("canWeb") ?: u.canWeb, allSims = r.bool("allSims") ?: u.allSims,
                        canUssd = r.bool("canUssd") ?: u.canUssd, canCall = r.bool("canCall") ?: u.canCall,
                        sims = if (r.body.containsKey("sims")) simIds(r.body["sims"]) else u.sims,
                    ) }) }
                    if (r.bool("enabled") == false || r.bool("canWeb") == false) Sessions.dropUser(target.id)
                    Log.sec(t("Изменены права пользователя ${target.username}", "Permissions of user ${target.username} changed"))
                }
                Http.json(userJson(Users.find(target.id)!!))
            }
            s.size == 2 && m == "DELETE" -> {
                val target = Users.find(s[1]) ?: throw ApiError(404, t("Пользователь не найден", "User not found"))
                if (target.isAdmin) throw ApiError(400, t("Администратора удалить нельзя", "The administrator cannot be deleted"))
                ConfigStore.update { c -> c.copy(users = c.users.filter { it.id != target.id }, apiKeys = c.apiKeys.filter { it.ownerId != target.id }) }
                Sessions.dropUser(target.id)
                Log.sec(t("Удалён пользователь ${target.username} и его API-ключи", "User ${target.username} and their API keys deleted"))
                Http.ok()
            }
            s.size == 3 && s[2] == "password" && m == "POST" -> {
                val target = Users.find(s[1]) ?: throw ApiError(404, t("Пользователь не найден", "User not found"))
                val pwd = r.requireStr("password")
                if (pwd.length < 8) throw ApiError(400, t("Пароль: минимум 8 символов", "Password: at least 8 characters"))
                Users.setPassword(target.id, pwd)
                Sessions.dropUser(target.id)
                Log.sec(t("Пароль пользователя ${target.username} сброшен администратором", "Password of ${target.username} reset by the administrator"))
                Http.ok()
            }
            else -> throw ApiError(404, t("Не найдено", "Not found"))
        }
    }

    private fun admin(r: Req): Response {
        val s = r.seg.drop(2) // after admin/api
        val m = r.method

        if (s == listOf("login") && m == "POST") return login(r)
        if (s == listOf("logout") && m == "POST") {
            Sessions.drop(cookie(r))
            return Http.ok().apply { addHeader("Set-Cookie", "sg_sid=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict") }
        }
        val me = requireUser(r)
        val isAdmin = me.isAdmin
        // Configuration sections are admin-only.
        val adminSections = setOf("settings", "webhooks", "remote", "telegram", "firewall", "logs", "backup", "restore")
        if (s.firstOrNull() in adminSections) adminOnly(me)
        if (s.firstOrNull() == "users") return usersApi(r, s, me)

        return when {
            s == listOf("me") -> Http.json(buildJsonObject {
                put("ok", true); put("user", userJson(me.user))
                put("sims", enc(simsFor(me)))
            })
            s == listOf("dashboard") -> Http.json(dashboard(me))

            // --- messages ---
            s == listOf("messages") && m == "GET" -> {
                val dir = r.q("direction")?.takeIf { it == "in" || it == "out" }
                val st = r.q("status")?.takeIf { it.isNotBlank() }
                val q = r.q("q")
                val limit = r.qi("limit", 50); val offset = r.qi("offset", 0)
                val sc = scopeOf(me)
                Http.json(buildJsonObject {
                    put("total", Db.count(dir, st, q, sc))
                    put("messages", enc(Db.list(direction = dir, status = st, query = q, limit = limit, offset = offset, scope = sc)))
                })
            }
            s == listOf("messages", "send") && m == "POST" -> Http.json(sendFromBody(r, "web", me))
            s == listOf("messages", "clear") && m == "POST" -> { adminOnly(me); Db.clear(r.str("direction")); Http.ok() }
            s.size == 3 && s[0] == "messages" && s[2] == "retry" && m == "POST" -> {
                val msg = s[1].toLongOrNull()?.let { Db.get(it) } ?: throw ApiError(404, t("Сообщение не найдено", "Message not found"))
                checkMessage(me, msg)
                if (!me.canSend) throw ApiError(403, t("Нет права на отправку SMS", "No permission to send SMS"))
                val ok = Db.retry(msg.id)
                if (ok) SmsSender.poke(); Http.ok("retried" to ok)
            }
            s.size == 2 && s[0] == "messages" && m == "DELETE" -> {
                val msg = s[1].toLongOrNull()?.let { Db.get(it) } ?: throw ApiError(404, t("Сообщение не найдено", "Message not found"))
                checkMessage(me, msg)
                val alsoPhone = r.q("phone") == "1" || ConfigStore.value.storage.deleteFromPhone
                val removedPhone = alsoPhone && Db.sysUri(msg.id)?.let { SmsStore.deleteSystem(it) } == true
                Db.delete(msg.id); Http.ok("deletedFromPhone" to removedPhone)
            }

            // --- settings ---
            s == listOf("settings") && m == "GET" -> Http.json(publicConfig())
            s == listOf("settings") && m == "PUT" -> updateSettings(r)
            s == listOf("sims") && m == "GET" -> Http.json(enc(simsFor(me)))
            s == listOf("sims") && m == "PUT" -> {
                adminOnly(me)
                val edits = (r.body["sims"] as? JsonArray) ?: throw ApiError(400, "sims")
                edits.forEach { e ->
                    val code = e.jsonObject["balanceCode"]?.jsonPrimitive?.content?.trim().orEmpty()
                    if (code.isNotEmpty() && !UssdService.validCode(code)) throw ApiError(400, t("Некорректный код баланса: $code (пример: *100#)", "Invalid balance code: $code (example: *100#)"))
                }
                ConfigStore.update { c -> c.copy(sims = c.sims.map { cur ->
                    val e = edits.map { it.jsonObject }.firstOrNull { it["simId"]?.jsonPrimitive?.intOrNull == cur.simId } ?: return@map cur
                    fun b(k: String) = e[k]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                    cur.copy(
                        label = e["label"]?.jsonPrimitive?.content?.trim()?.take(30) ?: cur.label,
                        color = e["color"]?.jsonPrimitive?.content?.takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) } ?: cur.color,
                        sendEnabled = b("sendEnabled") ?: cur.sendEnabled, receiveEnabled = b("receiveEnabled") ?: cur.receiveEnabled,
                        dailyLimit = e["dailyLimit"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: cur.dailyLimit,
                        balanceCode = e["balanceCode"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isEmpty() || UssdService.validCode(it) } ?: cur.balanceCode,
                    )
                }) }
                Log.i("config", t("Изменены настройки SIM", "SIM settings changed"))
                Http.json(enc(SimManager.list()))
            }
            s.size == 2 && s[0] == "sims" && m == "DELETE" -> {
                adminOnly(me)
                val id = s[1].toIntOrNull() ?: throw ApiError(400, "simId")
                if (!SimManager.forget(id)) throw ApiError(400, t("Активную SIM удалить нельзя", "An active SIM cannot be removed"))
                ConfigStore.update { c -> c.copy(users = c.users.map { u -> u.copy(sims = u.sims - id) }, apiKeys = c.apiKeys.map { k -> k.copy(sims = k.sims - id) }) }
                Http.json(enc(SimManager.list()))
            }

            // --- USSD ---
            s == listOf("ussd") && m == "GET" -> {
                if (!me.canUssd) throw ApiError(403, t("Нет права на USSD-запросы", "No permission for USSD requests"))
                Http.json(enc(Db.listUssd(me.sims, r.qi("limit", 50))))
            }
            s == listOf("ussd") && m == "POST" -> Http.json(ussdRun(r, me, "web"))
            s.size == 3 && s[0] == "sims" && s[2] == "balance" && m == "POST" -> {
                val sim = simById(s[1].toIntOrNull() ?: -1, me)
                val code = ConfigStore.value.sims.find { it.simId == sim.simId }?.balanceCode.orEmpty()
                if (code.isEmpty()) throw ApiError(400, t("Для этой SIM не задан код баланса", "No balance code set for this SIM"))
                if (!me.canUssd) throw ApiError(403, t("Нет права на USSD-запросы", "No permission for USSD requests"))
                Http.json(enc(runBlocking { UssdService.run(code, sim, "web", me.user.id) }))
            }

            // --- SIM memory ---
            s.size == 3 && s[0] == "sims" && s[2] == "icc" && m == "GET" -> {
                if (!me.canRead) throw ApiError(403, t("Нет права на чтение", "No read permission"))
                val sim = simById(s[1].toIntOrNull() ?: -1, me)
                Http.json(buildJsonObject { put("simId", sim.simId); put("messages", enc(SimMemory.list(sim))) })
            }
            s.size == 4 && s[0] == "sims" && s[2] == "icc" && m == "DELETE" -> {
                adminOnly(me)
                val sim = simById(s[1].toIntOrNull() ?: -1, me)
                Http.ok("deleted" to SimMemory.delete(sim, s[3].toIntOrNull() ?: throw ApiError(400, "index")))
            }
            s.size == 5 && s[0] == "sims" && s[2] == "icc" && s[4] == "copy" && m == "POST" -> {
                adminOnly(me)
                val sim = simById(s[1].toIntOrNull() ?: -1, me)
                Http.ok("copied" to SimMemory.copyToPhone(sim, s[3].toIntOrNull() ?: throw ApiError(400, "index"), r.bool("move") == true))
            }

            // --- calls ---
            s == listOf("calls") && m == "GET" -> {
                if (!me.canCall) throw ApiError(403, t("Нет права на звонки", "No permission for calls"))
                Http.json(enc(Db.listCalls(me.sims, r.qi("limit", 100))))
            }
            s == listOf("calls") && m == "POST" -> Http.json(callDial(r, me, "web"))

            // --- backup ---
            s == listOf("backup") && m == "POST" -> {
                val pwd = r.requireStr("password")
                if (pwd.length < 8) throw ApiError(400, t("Пароль резервной копии: минимум 8 символов", "Backup password: at least 8 characters"))
                val body = Backup.export(pwd, r.bool("includeHistory") == true)
                Log.sec(t("Создана резервная копия (${me.user.username}, ${r.ip})", "Backup created (${me.user.username}, ${r.ip})"))
                NanoHTTPD.newFixedLengthResponse(Response.Status.OK, Http.JSON_MIME, body).apply {
                    addHeader("Content-Disposition", "attachment; filename=\"isms-gw-backup.json\"")
                }
            }
            s == listOf("restore") && m == "POST" -> {
                val file = r.body["backup"]?.let { if (it is JsonPrimitive) it.content else it.toString() } ?: throw ApiError(400, t("Поле 'backup' обязательно", "Field 'backup' is required"))
                val n = Backup.restore(file, r.requireStr("password"), r.bool("includeHistory") == true)
                Gateway.restartServerLater(); Gateway.restartRemote(); TelegramBot.restart()
                Http.ok("messages" to n)
            }

            // --- API keys ---
            s == listOf("keys") && m == "GET" -> Http.json(keysJson(me))
            s == listOf("keys") && m == "POST" -> {
                val owner = r.str("ownerId")?.takeIf { isAdmin }?.let { Users.find(it) ?: throw ApiError(404, t("Пользователь не найден", "User not found")) } ?: me.user
                val ownerAccess = Users.access(owner)
                if (!owner.isAdmin && !owner.canUseApi) throw ApiError(403, t("Пользователю запрещён доступ к API", "API access is disabled for this user"))
                val keySims = simIds(r.body["sims"])
                if (keySims.any { !ownerAccess.allows(it) }) throw ApiError(403, t("Ключ может включать только SIM владельца", "A key may include only the owner's SIMs"))
                val name = r.requireStr("name").take(40)
                val scopes = r.body["scopes"]?.jsonArray?.map { it.jsonPrimitive.content }?.filter { it in setOf("send", "read", "ussd", "call") }
                    ?.ifEmpty { null } ?: listOf("send", "read")
                val ips = cidrList(r.body["allowedIps"])
                val (token, key) = Crypto.newApiKey(name, scopes, ips, owner.id, keySims)
                ConfigStore.update { it.copy(apiKeys = it.apiKeys + key) }
                Log.sec(t("Создан API-ключ «$name» (${key.prefix}…)", "API key \"$name\" created (${key.prefix}…)"))
                Http.ok("token" to token, "id" to key.id)
            }
            s.size == 2 && s[0] == "keys" && m == "PUT" -> {
                val id = s[1]
                val k0 = ConfigStore.value.apiKeys.find { it.id == id } ?: throw ApiError(404, t("Ключ не найден", "Key not found"))
                if (!isAdmin && k0.ownerId != me.user.id) throw ApiError(404, t("Ключ не найден", "Key not found"))
                val newSims = if (r.body.containsKey("sims")) simIds(r.body["sims"]) else null
                val ownerAccess = Users.find(k0.ownerId)?.let { Users.access(it) }
                if (newSims != null && newSims.any { ownerAccess?.allows(it) != true }) throw ApiError(403, t("Ключ может включать только SIM владельца", "A key may include only the owner's SIMs"))
                ConfigStore.update { c -> c.copy(apiKeys = c.apiKeys.map { k ->
                    if (k.id != id) k else k.copy(
                        name = r.str("name")?.take(40) ?: k.name,
                        enabled = r.bool("enabled") ?: k.enabled,
                        scopes = r.body["scopes"]?.jsonArray?.map { it.jsonPrimitive.content }?.filter { it in setOf("send", "read", "ussd", "call") } ?: k.scopes,
                        allowedIps = if (r.body.containsKey("allowedIps")) cidrList(r.body["allowedIps"]) else k.allowedIps,
                        sims = newSims ?: k.sims,
                    )
                }) }
                Http.json(keysJson(me))
            }
            s.size == 2 && s[0] == "keys" && m == "DELETE" -> {
                val k0 = ConfigStore.value.apiKeys.find { it.id == s[1] } ?: throw ApiError(404, t("Ключ не найден", "Key not found"))
                if (!isAdmin && k0.ownerId != me.user.id) throw ApiError(404, t("Ключ не найден", "Key not found"))
                ConfigStore.update { c -> c.copy(apiKeys = c.apiKeys.filter { it.id != s[1] }) }
                Log.sec(t("Удалён API-ключ ${s[1]}", "API key ${s[1]} deleted"))
                Http.json(keysJson(me))
            }

            // --- webhooks ---
            s == listOf("webhooks") && m == "GET" -> Http.json(enc(ConfigStore.value.webhooks))
            s == listOf("webhooks") && m == "POST" -> {
                val h = Webhook(
                    id = Crypto.randomToken(8), url = validUrl(r.requireStr("url")),
                    events = eventsList(r.body["events"]), secret = r.str("secret")?.takeIf { it.isNotBlank() } ?: Crypto.randomToken(24),
                )
                ConfigStore.update { it.copy(webhooks = it.webhooks + h) }
                Http.json(enc(ConfigStore.value.webhooks))
            }
            s.size == 2 && s[0] == "webhooks" && m == "PUT" -> {
                ConfigStore.update { c -> c.copy(webhooks = c.webhooks.map { h ->
                    if (h.id != s[1]) h else h.copy(
                        url = r.str("url")?.let(::validUrl) ?: h.url,
                        events = if (r.body.containsKey("events")) eventsList(r.body["events"]) else h.events,
                        secret = r.str("secret") ?: h.secret, enabled = r.bool("enabled") ?: h.enabled,
                    )
                }) }
                Http.json(enc(ConfigStore.value.webhooks))
            }
            s.size == 2 && s[0] == "webhooks" && m == "DELETE" -> {
                ConfigStore.update { c -> c.copy(webhooks = c.webhooks.filter { it.id != s[1] }) }
                Http.json(enc(ConfigStore.value.webhooks))
            }
            s.size == 3 && s[0] == "webhooks" && s[2] == "test" && m == "POST" -> {
                val h = ConfigStore.value.webhooks.find { it.id == s[1] } ?: throw ApiError(404, t("Вебхук не найден", "Webhook not found"))
                val now = System.currentTimeMillis()
                val sample = app.simgate.data.Message(0, "in", "+70000000000", t("Тестовое сообщение iSMS GW", "iSMS GW test message"), 1, status = "received", createdAt = now, updatedAt = now)
                val err = WebhookDispatcher.send(h, "test", WebhookDispatcher.payload("test", sample))
                Http.ok("delivered" to (err == null), "error" to err)
            }

            // --- remote SMS server ---
            s == listOf("remote") && m == "GET" -> Http.json(remoteJson())
            s == listOf("remote") && m == "PUT" -> {
                val url = r.str("url")?.trim().orEmpty()
                if (url.isNotEmpty() && !Regex("^(wss?|https?)://.+", RegexOption.IGNORE_CASE).matches(url)) throw ApiError(400, t("URL должен начинаться с wss:// или ws://", "URL must start with wss:// or ws://"))
                ConfigStore.update { c -> c.copy(remote = c.remote.copy(
                    enabled = r.bool("enabled") ?: c.remote.enabled, url = url, token = r.str("token") ?: c.remote.token,
                )) }
                Gateway.restartRemote()
                Http.json(remoteJson())
            }

            // --- Telegram bot ---
            s == listOf("telegram") && m == "GET" -> Http.json(telegramJson())
            s == listOf("telegram") && m == "PUT" -> {
                val token = r.str("token")?.trim()
                if (token != null && token.isNotEmpty() && !Regex("^\\d{5,}:[A-Za-z0-9_-]{30,}$").matches(token)) throw ApiError(400, t("Неверный формат токена (получите у @BotFather)", "Invalid token format (get one from @BotFather)"))
                ConfigStore.update { c -> c.copy(telegram = c.telegram.copy(
                    enabled = r.bool("enabled") ?: c.telegram.enabled, token = token ?: c.telegram.token,
                    notifyIncoming = r.bool("notifyIncoming") ?: c.telegram.notifyIncoming,
                    notifyFailed = r.bool("notifyFailed") ?: c.telegram.notifyFailed,
                    notifyDelivered = r.bool("notifyDelivered") ?: c.telegram.notifyDelivered,
                    allowSend = r.bool("allowSend") ?: c.telegram.allowSend,
                )) }
                TelegramBot.restart()
                Http.json(telegramJson())
            }
            s == listOf("telegram", "code") && m == "POST" -> {
                ConfigStore.update { c -> c.copy(telegram = c.telegram.copy(pairingCode = Crypto.randomToken(8))) }
                Http.json(telegramJson())
            }
            s.size == 3 && s[0] == "telegram" && s[1] == "chats" && m == "DELETE" -> {
                val id = s[2].toLongOrNull()
                ConfigStore.update { c -> c.copy(telegram = c.telegram.copy(chats = c.telegram.chats.filter { it.id != id })) }
                Log.sec(t("Telegram: чат $id отвязан из панели", "Telegram: chat $id unpaired from the panel"))
                Http.json(telegramJson())
            }

            // --- firewall ---
            s == listOf("firewall", "bans") && m == "GET" -> Http.json(enc(Firewall.bans()))
            s.size == 3 && s[0] == "firewall" && s[1] == "bans" && m == "DELETE" -> {
                Firewall.unban(s[2]); Log.sec(t("Снята блокировка ${s[2]}", "Ban lifted for ${s[2]}")); Http.json(enc(Firewall.bans()))
            }

            // --- log ---
            s == listOf("logs") && m == "GET" -> Http.json(enc(Db.logs(r.q("category"), r.qi("limit", 200))))
            s == listOf("logs") && m == "DELETE" -> { Db.clearLogs(); Http.ok() }

            // --- password ---
            s == listOf("password") && m == "POST" -> {
                val cur = r.requireStr("current"); val new = r.requireStr("new")
                if (!Crypto.verifyPassword(me.user.hash, me.user.salt, cur)) { Firewall.authFailed(r.ip, t("смена пароля", "password change")); throw ApiError(403, t("Текущий пароль неверен", "Current password is incorrect")) }
                if (new.length < 8) throw ApiError(400, t("Минимум 8 символов", "At least 8 characters"))
                Users.setPassword(me.user.id, new)
                Sessions.dropUser(me.user.id)
                Log.sec(t("Пароль веб-панели изменён с ${r.ip}", "Web panel password changed from ${r.ip}"))
                Http.ok()
            }
            else -> throw ApiError(404, t("Не найдено", "Not found"))
        }
    }

    private fun login(r: Req): Response {
        val pwd = r.str("password").orEmpty()
        val username = r.str("username")?.trim()?.ifEmpty { null } ?: Users.admin().username
        val user = if (pwd.isNotEmpty()) Users.verify(username, pwd) else null
        if (user == null) {
            Firewall.authFailed(r.ip, t("вход в панель", "panel login"))
            Thread.sleep(600) // slow down brute force
            throw ApiError(401, t("Неверный пароль", "Invalid password"))
        }
        Firewall.authOk(r.ip)
        val token = Sessions.create(r.ip, user.id)
        Users.touchLogin(user.id, r.ip)
        Log.sec(t("Вход в веб-панель: ${user.username} с ${r.ip}", "Web panel login: ${user.username} from ${r.ip}"))
        val secureFlag = if (ConfigStore.value.server.https) "; Secure" else ""
        return Http.json(buildJsonObject { put("ok", true); put("user", userJson(user)) }).apply { addHeader("Set-Cookie", "sg_sid=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200$secureFlag") }
    }

    private fun validUrl(u: String): String {
        val t = u.trim()
        if (!Regex("^https?://[^\\s]+$", RegexOption.IGNORE_CASE).matches(t)) throw ApiError(400, t("URL должен начинаться с http:// или https://", "URL must start with http:// or https://"))
        return t
    }

    private fun eventsList(el: JsonElement?): List<String> =
        el?.jsonArray?.map { it.jsonPrimitive.content }?.filter { it in Events.ALL }?.ifEmpty { null } ?: listOf(Events.RECEIVED)

    private fun cidrList(el: JsonElement?): List<String> {
        val items = when (el) {
            null -> emptyList()
            is JsonArray -> el.map { it.jsonPrimitive.content }
            else -> el.jsonPrimitive.content.split(',', '\n', ' ')
        }.map { it.trim() }.filter { it.isNotEmpty() }
        items.forEach { if (!Firewall.isValidRule(it)) throw ApiError(400, t("Некорректное правило: $it", "Invalid rule: $it")) }
        return items
    }

    private fun keysJson(me: Access): JsonElement = buildJsonArray {
        ConfigStore.value.apiKeys.filter { me.isAdmin || it.ownerId == me.user.id }.forEach { k ->
            add(buildJsonObject {
                put("ownerId", k.ownerId); put("owner", Users.find(k.ownerId)?.username ?: "—"); put("sims", enc(k.sims))
                put("id", k.id); put("name", k.name); put("prefix", k.prefix); put("enabled", k.enabled)
                put("scopes", enc(k.scopes)); put("allowedIps", enc(k.allowedIps))
                put("createdAt", k.createdAt); put("lastUsedAt", k.lastUsedAt); put("lastIp", k.lastIp)
            })
        }
    }

    private fun telegramJson() = buildJsonObject {
        val t = ConfigStore.value.telegram
        put("enabled", t.enabled); put("token", t.token); put("pairingCode", t.pairingCode)
        put("chats", enc(t.chats)); put("notifyIncoming", t.notifyIncoming); put("notifyFailed", t.notifyFailed)
        put("notifyDelivered", t.notifyDelivered); put("allowSend", t.allowSend)
        put("state", TelegramBot.state.value.name.lowercase()); put("error", TelegramBot.lastError.value)
        put("bot", TelegramBot.botName.value)
    }

    private fun remoteJson() = buildJsonObject {
        val rc = ConfigStore.value.remote
        put("enabled", rc.enabled); put("url", rc.url); put("token", rc.token)
        put("state", RemoteClient.state.value.name.lowercase()); put("error", RemoteClient.lastError.value)
    }

    private fun publicConfig(): JsonObject {
        val c = ConfigStore.value
        return buildJsonObject {
            put("deviceName", c.deviceName); put("deviceId", c.deviceId)
            put("autostart", c.autostart); put("keepAwake", c.keepAwake)
            put("language", c.language); put("theme", c.theme)
            put("server", enc(c.server)); put("firewall", enc(c.firewall))
            put("sending", enc(c.sending)); put("receiving", enc(c.receiving))
            put("storage", enc(c.storage)); put("calls", enc(c.calls))
            put("storageInfo", buildJsonObject {
                put("dbBytes", Db.sizeBytes()); put("messages", Db.count()); put("webhookQueue", Db.hookQueueSize())
                put("secretsEncrypted", true)
            })
            put("tlsFingerprint", Tls.fingerprint(ctx))
        }
    }

    private fun updateSettings(r: Req): Response {
        val b = r.body
        var restartServer = false
        ConfigStore.update { c ->
            var n = c
            r.str("deviceName")?.let { n = n.copy(deviceName = it.take(40).ifBlank { c.deviceName }) }
            r.bool("autostart")?.let { n = n.copy(autostart = it) }
            r.bool("keepAwake")?.let { n = n.copy(keepAwake = it) }
            r.str("language")?.takeIf { it in setOf("", "ru", "en") }?.let { n = n.copy(language = it) }
            r.str("theme")?.takeIf { it in setOf("system", "light", "dark") }?.let { n = n.copy(theme = it) }
            b["sending"]?.let {
                val sc = json.decodeFromJsonElement<SendingConfig>(it)
                n = n.copy(sending = sc.copy(delayMs = sc.delayMs.coerceIn(0, 60_000), maxRetries = sc.maxRetries.coerceIn(0, 10), maxPerMinute = sc.maxPerMinute.coerceIn(0, 600)))
            }
            b["receiving"]?.let { n = n.copy(receiving = json.decodeFromJsonElement<ReceivingConfig>(it)) }
            b["storage"]?.let {
                val st = json.decodeFromJsonElement<StorageConfig>(it)
                n = n.copy(storage = st.copy(historyDays = st.historyDays.coerceIn(0, 3650), maxMessages = st.maxMessages.coerceIn(0, 1_000_000),
                    logDays = st.logDays.coerceIn(0, 3650), ussdDays = st.ussdDays.coerceIn(0, 3650), callDays = st.callDays.coerceIn(0, 3650)))
            }
            b["calls"]?.let { n = n.copy(calls = json.decodeFromJsonElement<CallsConfig>(it)) }
            b["firewall"]?.let {
                val fw = json.decodeFromJsonElement<FirewallConfig>(it)
                if (fw.mode !in setOf("lan", "whitelist", "all")) throw ApiError(400, t("Неизвестный режим файрвола", "Unknown firewall mode"))
                (fw.whitelist + fw.blacklist + fw.adminWhitelist).forEach { rule -> if (!Firewall.isValidRule(rule)) throw ApiError(400, t("Некорректное правило: $rule", "Invalid rule: $rule")) }
                // Self-lockout protection: the current IP must keep access to the panel.
                val addr = Firewall.parse(r.ip)
                if (addr != null && fw.adminWhitelist.isNotEmpty() && !addr.isLoopbackAddress && fw.adminWhitelist.none { w -> Firewall.matches(addr, w) })
                    throw ApiError(400, t("Ваш IP ${r.ip} не входит в белый список панели — вы потеряете доступ", "Your IP ${r.ip} is not in the panel whitelist — you would lose access"))
                if (addr != null && fw.blacklist.any { w -> Firewall.matches(addr, w) }) throw ApiError(400, t("Нельзя заблокировать собственный IP ${r.ip}", "You cannot block your own IP ${r.ip}"))
                n = n.copy(firewall = fw.copy(rateLimitPerMinute = fw.rateLimitPerMinute.coerceIn(0, 10_000), maxAuthFailures = fw.maxAuthFailures.coerceIn(0, 100), banMinutes = fw.banMinutes.coerceIn(1, 100_000)))
                Log.sec(t("Изменены правила файрвола (режим ${fw.mode})", "Firewall rules changed (mode ${fw.mode})"))
            }
            b["server"]?.jsonObject?.let { so ->
                val port = so["port"]?.jsonPrimitive?.intOrNull ?: c.server.port
                if (port !in 1024..65535) throw ApiError(400, t("Порт должен быть 1024–65535", "Port must be 1024–65535"))
                val https = (so["https"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: c.server.https
                if (port != c.server.port || https != c.server.https) restartServer = true
                n = n.copy(server = c.server.copy(port = port, https = https))
            }
            n
        }
        if (restartServer) Gateway.restartServerLater()
        Gateway.applyLocks()
        I18n.apply(ConfigStore.value.language)
        if (r.body.containsKey("calls") && Gateway.serviceRunning.value) CallService.start()
        if (r.body.containsKey("storage")) runCatching { Db.cleanup(ConfigStore.value.storage) }
        return Http.json(buildJsonObject { put("ok", true); put("restart", restartServer); put("config", publicConfig()) })
    }

    private fun dashboard(me: Access): JsonObject {
        val st = statusJson(me)
        return buildJsonObject {
            st.forEach { (k, v) -> put(k, v) }
            put("me", userJson(me.user))
            put("recent", enc(Db.list(limit = 8, scope = scopeOf(me))))
            put("hourly", buildJsonArray { Db.hourly(scopeOf(me)).forEach { add(buildJsonArray { add(JsonPrimitive(it[0])); add(JsonPrimitive(it[1])) }) } })
            put("server", buildJsonObject {
                put("urls", enc(Gateway.urls())); put("https", ConfigStore.value.server.https)
                put("fingerprint", Tls.fingerprint(ctx))
            })
            put("remote", remoteJson())
            put("telegram", buildJsonObject {
                put("enabled", ConfigStore.value.telegram.enabled); put("state", TelegramBot.state.value.name.lowercase())
                put("bot", TelegramBot.botName.value); put("chats", ConfigStore.value.telegram.chats.size)
            })
            put("webhooks", ConfigStore.value.webhooks.count { it.enabled })
            put("apiKeys", ConfigStore.value.apiKeys.count { it.enabled && (me.isAdmin || it.ownerId == me.user.id) })
            put("users", ConfigStore.value.users.size)
            put("bans", if (me.isAdmin) Firewall.bans().size else 0)
            put("firewallMode", ConfigStore.value.firewall.mode)
        }
    }
}
