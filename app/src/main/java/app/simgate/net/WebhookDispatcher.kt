package app.simgate.net

import app.simgate.SimGateApp
import app.simgate.data.t
import app.simgate.data.ConfigStore
import app.simgate.data.Crypto
import app.simgate.data.Log
import app.simgate.data.Message
import app.simgate.data.Webhook
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import app.simgate.data.Db
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Delivers events to external URLs. Each request is signed with HMAC-SHA256:
 *   X-ISMS-Signature: sha256=hex(HMAC(secret, timestamp + "." + body))
 * Retries with exponential backoff: 5s, 20s, 1m, 3m, 10m.
 */
object WebhookDispatcher {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val backoff = listOf(5_000L, 20_000L, 60_000L, 180_000L, 600_000L)
    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun payload(event: String, m: Message): String = payload(event, ConfigStore.json.encodeToJsonElement(m))

    fun payload(event: String, data: JsonElement): String {
        val cfg = ConfigStore.value
        return buildJsonObject {
            put("event", event)
            put("deviceId", cfg.deviceId)
            put("deviceName", cfg.deviceName)
            put("timestamp", System.currentTimeMillis())
            put("data", data)
        }.toString()
    }

    fun dispatch(event: String, m: Message) = dispatch(event, ConfigStore.json.encodeToJsonElement(m))

    /** Enqueues the event in the persistent queue (DB) for each subscribed webhook. */
    fun dispatch(event: String, data: JsonElement) {
        val hooks = ConfigStore.value.webhooks.filter { it.enabled && event in it.events }
        if (hooks.isEmpty()) return
        val body = payload(event, data)
        hooks.forEach { Db.enqueueHook(it.id, event, body) }
        wake.trySend(Unit)
        ensureWorker()
    }

    /** Returns null on success, or the error text. */
    fun send(h: Webhook, event: String, body: String): String? {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val req = Request.Builder().url(h.url)
            .header("User-Agent", "iSMS-GW/${app.simgate.BuildConfig.VERSION_NAME}")
            .header("X-ISMS-Event", event)
            .header("X-ISMS-Timestamp", ts)
            .apply { if (h.secret.isNotEmpty()) header("X-ISMS-Signature", "sha256=" + Crypto.hmacSha256Hex(h.secret, "$ts.$body")) }
            .post(body.toRequestBody(JSON)).build()
        return try {
            http.newCall(req).execute().use { r -> if (r.isSuccessful) null else "HTTP ${r.code}" }
        } catch (e: Exception) { e.javaClass.simpleName + ": " + (e.message ?: "") }
    }

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null

    /** Background delivery from the queue; started on app launch and on new events. */
    @Synchronized
    fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = SimGateApp.scope.launch {
            while (isActive) {
                val jobs = Db.dueHooks()
                for (j in jobs) {
                    val h = ConfigStore.value.webhooks.find { it.id == j.hookId && it.enabled }
                    if (h == null) { Db.hookDone(j.id); continue }
                    val err = runCatching { send(h, j.event, j.body) }.getOrElse { it.message ?: "error" }
                    if (err == null) {
                        Db.hookDone(j.id)
                        if (j.attempts > 0) Log.i("webhook", t("${j.event} → ${h.url}: доставлено с попытки ${j.attempts + 1}", "${j.event} → ${h.url}: delivered on attempt ${j.attempts + 1}"))
                    } else if (j.attempts >= backoff.size) {
                        Db.hookDone(j.id)
                        Log.e("webhook", t("${j.event} → ${h.url}: не доставлено ($err)", "${j.event} → ${h.url}: not delivered ($err)"))
                    } else {
                        val wait = backoff[j.attempts]
                        Db.hookRetry(j.id, j.attempts + 1, System.currentTimeMillis() + wait, err)
                        Log.w("webhook", t("${j.event} → ${h.url}: $err, повтор через ${wait / 1000}с", "${j.event} → ${h.url}: $err, retry in ${wait / 1000}s"))
                    }
                }
                if (jobs.isNotEmpty()) continue
                val next = Db.nextHookAt()
                val sleep = if (next == null) 60_000L else (next - System.currentTimeMillis()).coerceIn(500, 60_000)
                withTimeoutOrNull(sleep) { wake.receive() }
            }
        }
    }
}
