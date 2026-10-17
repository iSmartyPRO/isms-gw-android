package app.simgate.net

import app.simgate.BuildConfig
import app.simgate.data.t
import app.simgate.SimGateApp
import app.simgate.data.ConfigStore
import app.simgate.data.Db
import app.simgate.data.Log
import app.simgate.data.Message
import app.simgate.sms.SendException
import app.simgate.sms.SimManager
import app.simgate.sms.SmsSender
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * "SMS server" mode: a persistent WebSocket connection from the phone to your server.
 * Works behind NAT / over mobile data; no inbound connections to the phone are needed.
 *
 * Protocol (JSON frames):
 *  phone → server: hello, event, ack, status, pong, error
 *  server → phone: send, ack_event, get_status, ping
 */
object RemoteClient {
    enum class State { DISABLED, CONNECTING, CONNECTED, DISCONNECTED }

    val state = MutableStateFlow(State.DISABLED)
    val lastError = MutableStateFlow("")
    private var ws: WebSocket? = null
    private var loop: Job? = null
    private var attempt = 0
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).build()

    fun start() {
        stop()
        val cfg = ConfigStore.value.remote
        if (!cfg.enabled || cfg.url.isBlank()) { state.value = State.DISABLED; return }
        loop = SimGateApp.scope.launch {
            while (true) {
                if (state.value != State.CONNECTED && state.value != State.CONNECTING) connect()
                delay(2_000)
                if (state.value == State.DISCONNECTED) {
                    val wait = (2_000L shl attempt.coerceAtMost(5)).coerceAtMost(60_000L)
                    attempt++
                    delay(wait)
                }
            }
        }
    }

    fun stop() {
        loop?.cancel(); loop = null
        ws?.close(1000, "stop"); ws = null
        state.value = State.DISABLED
    }

    private fun connect() {
        val cfg = ConfigStore.value
        state.value = State.CONNECTING
        val url = cfg.remote.url.trim().replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws")
        val req = try {
            Request.Builder().url(url)
                .header("Authorization", "Bearer ${cfg.remote.token}")
                .header("X-Device-Id", cfg.deviceId)
                .header("X-Device-Name", cfg.deviceName)
                .header("User-Agent", "iSMS-GW/${BuildConfig.VERSION_NAME}")
                .build()
        } catch (e: Exception) {
            lastError.value = t("Неверный URL: ${e.message}", "Invalid URL: ${e.message}"); state.value = State.DISCONNECTED; return
        }
        ws = client.newWebSocket(req, Listener())
    }

    private fun sendJson(o: JsonObject): Boolean = ws?.send(o.toString()) ?: false

    private fun simsJson() = ConfigStore.json.encodeToJsonElement(SimManager.list())

    fun sendEvent(event: String, m: Message) {
        if (state.value != State.CONNECTED) return
        sendJson(buildJsonObject {
            put("type", "event"); put("event", event)
            put("data", ConfigStore.json.encodeToJsonElement(m))
        })
    }

    private fun flushUnsynced() {
        Db.unsyncedIncoming().forEach { sendEvent("sms.received", it) }
    }

    private class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            state.value = State.CONNECTED
            lastError.value = ""
            Log.i("remote", t("Подключено к ${response.request.url.host}", "Connected to ${response.request.url.host}"))
            val cfg = ConfigStore.value
            sendJson(buildJsonObject {
                put("type", "hello"); put("deviceId", cfg.deviceId); put("deviceName", cfg.deviceName)
                put("version", BuildConfig.VERSION_NAME); put("sims", simsJson())
            })
            flushUnsynced()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val o = runCatching { ConfigStore.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val type = o["type"]?.jsonPrimitive?.content
            when (type) {
                "ping" -> sendJson(buildJsonObject { put("type", "pong"); put("ts", System.currentTimeMillis()) })
                "send" -> {
                    val ref = o["id"]?.jsonPrimitive?.content
                    val sim = o["sim"]?.jsonPrimitive?.intOrNull ?: 0
                    val txt = o["text"]?.jsonPrimitive?.content.orEmpty()
                    val to = o["to"]?.let { el ->
                        runCatching { el.jsonArray.map { it.jsonPrimitive.content } }.getOrElse { listOf(el.jsonPrimitive.content) }
                    } ?: emptyList()
                    to.forEach { phone ->
                        try {
                            val m = SmsSender.enqueue(phone, txt, sim, "remote", ref)
                            sendJson(buildJsonObject { put("type", "ack"); put("id", ref); put("to", phone); put("messageId", m.id); put("status", m.status) })
                        } catch (e: SendException) {
                            sendJson(buildJsonObject { put("type", "error"); put("id", ref); put("to", phone); put("error", e.message) })
                        }
                    }
                }
                "ack_event" -> o["messageId"]?.jsonPrimitive?.longOrNull?.let { Db.markRemoteSynced(it) }
                "get_status" -> sendJson(buildJsonObject {
                    put("type", "status"); put("sims", simsJson())
                    put("stats", ConfigStore.json.encodeToJsonElement(Db.stats()))
                })
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (state.value != State.DISABLED) state.value = State.DISCONNECTED
            Log.w("remote", t("Соединение закрыто: $code $reason", "Connection closed: $code $reason"))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (state.value == State.DISABLED) return
            val msg = response?.let { "HTTP ${it.code}" } ?: (t.message ?: t.javaClass.simpleName)
            if (lastError.value != msg) Log.w("remote", t("Ошибка подключения: $msg", "Connection error: $msg"))
            lastError.value = msg
            state.value = State.DISCONNECTED
        }
    }
}
