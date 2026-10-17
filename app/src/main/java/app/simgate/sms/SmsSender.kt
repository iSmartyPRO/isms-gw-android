package app.simgate.sms

import android.Manifest
import app.simgate.data.t
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import app.simgate.data.ConfigStore
import app.simgate.data.Db
import app.simgate.data.Log
import app.simgate.data.Message
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class SendException(msg: String) : Exception(msg)

/**
 * Outgoing SMS queue. Messages are first written to the DB (status=queued),
 * then a worker sends them one by one, honoring the SIM selection strategy,
 * limits and the delay between sends.
 */
object SmsSender {
    const val ACTION_SENT = "app.simgate.SMS_SENT"
    const val ACTION_DELIVERED = "app.simgate.SMS_DELIVERED"

    private lateinit var ctx: Context
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private var rrIndex = 0

    /** Waiting for part send results: id → (number of expected parts, result). */
    private class Pending(var remaining: Int, val result: CompletableDeferred<String?>)
    private val pending = ConcurrentHashMap<Long, Pending>()

    val isRunning get() = worker?.isActive == true

    fun init(context: Context) { ctx = context.applicationContext }

    fun validatePhone(raw: String): String? {
        val p = raw.trim().replace(Regex("[\\s()\\-]"), "")
        return if (Regex("^\\+?[0-9]{3,20}$").matches(p)) p else null
    }

    /**
     * Enqueues a message.
     * sim: 0 = by strategy, N = slot; simId: a specific SIM/eSIM by subscriptionId (takes precedence over the slot).
     * owner/allowed: the sending user and the SIMs allowed to them (null = all).
     */
    fun enqueue(
        phone: String, text: String, sim: Int, source: String, ref: String?,
        simId: Int = -1, owner: String = app.simgate.data.User.ADMIN_ID, allowed: Set<Int>? = null,
    ): Message {
        if (!SmsStore.isDefaultApp()) throw SendException(t("iSMS GW не назначен SMS-приложением по умолчанию", "iSMS GW is not set as the default SMS app"))
        val p = validatePhone(phone) ?: throw SendException(t("Некорректный номер: $phone", "Invalid number: $phone"))
        if (text.isEmpty()) throw SendException(t("Пустой текст сообщения", "Message text is empty"))
        if (text.length > 1600) throw SendException(t("Текст слишком длинный (макс. 1600 символов)", "Text is too long (max 1600 characters)"))
        // Explicitly selected SIM: by id or by slot → check that it exists and permissions.
        val known = if (simId > 0 || sim > 0) SimManager.list() else emptyList()
        var targetId = simId
        if (targetId <= 0 && sim > 0) {
            targetId = known.firstOrNull { it.active && it.slot == sim }?.simId ?: throw SendException(t("SIM в слоте $sim не найдена", "SIM in slot $sim not found"))
        }
        if (targetId > 0) {
            val s = known.firstOrNull { it.simId == targetId } ?: throw SendException(t("SIM #$targetId не найдена", "SIM #$targetId not found"))
            if (allowed != null && targetId !in allowed) throw SendException(t("Нет доступа к SIM «${s.label}»", "No access to SIM \"${s.label}\""))
            if (!s.active) throw SendException(t("SIM «${s.label}» сейчас не активна", "SIM \"${s.label}\" is not active now"))
        } else if (allowed != null && allowed.isEmpty()) {
            throw SendException(t("Пользователю не назначены SIM-карты", "No SIM cards are assigned to this user"))
        }
        val m = Db.insertOutgoing(p, text, if (targetId > 0) known.firstOrNull { it.simId == targetId }?.slot ?: sim else 0, source, ref, targetId, owner, allowed)
        // Link to the system conversation: the message immediately shows as "sending" in the thread.
        SmsStore.insertOutbox(p, text, targetId)?.let { Db.setSysUri(m.id, it) }
        Log.i("sms", t("В очереди #${m.id} → $p (${source})", "Queued #${m.id} → $p (${source})"))
        poke()
        return m
    }

    fun poke() { wake.trySend(Unit) }

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        worker = scope.launch {
            while (isActive) {
                val next = Db.nextQueued()
                if (next == null) {
                    withTimeoutOrNull(15_000) { wake.receive() }
                    continue
                }
                // Per-minute rate limit, to avoid being blocked by the carrier.
                val perMin = ConfigStore.value.sending.maxPerMinute
                if (perMin > 0 && Db.sentLastMinute() >= perMin) { delay(3_000); continue }
                process(next)
                delay(ConfigStore.value.sending.delayMs.coerceIn(0, 60_000))
            }
        }
    }

    fun stop() { worker?.cancel(); worker = null }

    private suspend fun process(m: Message) {
        val cfg = ConfigStore.value.sending
        val sim = try { chooseSim(m.simId, Db.allowedSims(m.id)) } catch (e: SendException) {
            fail(m, e.message ?: t("SIM недоступна", "SIM unavailable"), retry = false); return
        }
        if (!SmsStore.isDefaultApp()) {
            Db.markRetry(m.id, t("Ожидание роли SMS-приложения по умолчанию", "Waiting for the default SMS app role"), 60_000); return
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            fail(m, t("Нет разрешения SEND_SMS", "SEND_SMS permission missing"), retry = false); return
        }
        val mgr = SimManager.smsManagerFor(sim.subscriptionId)
        val parts = mgr.divideMessage(m.text)
        Db.markSending(m.id, sim.slot, sim.simId, parts.size)
        val waiter = Pending(parts.size, CompletableDeferred())
        pending[m.id] = waiter
        val sentIntents = ArrayList<PendingIntent>()
        val deliveryIntents = ArrayList<PendingIntent>()
        parts.indices.forEach { i ->
            sentIntents += pi(ACTION_SENT, m.id, i)
            if (cfg.deliveryReports) deliveryIntents += pi(ACTION_DELIVERED, m.id, i)
        }
        try {
            if (parts.size == 1) {
                mgr.sendTextMessage(m.phone, null, m.text, sentIntents[0], deliveryIntents.firstOrNull())
            } else {
                mgr.sendMultipartTextMessage(m.phone, null, parts, sentIntents, if (cfg.deliveryReports) deliveryIntents else null)
            }
        } catch (e: Exception) {
            pending.remove(m.id)
            fail(m, t("Ошибка отправки: ${e.message}", "Send error: ${e.message}"), retry = true); return
        }
        val error = withTimeoutOrNull(90_000) { waiter.result.await() } ?: "timeout"
        pending.remove(m.id)
        if (error.isEmpty()) {
            Db.markSent(m.id)
            val uri = Db.sysUri(m.id)
            if (uri != null) SmsStore.updateOutgoing(uri, android.provider.Telephony.Sms.MESSAGE_TYPE_SENT, sim.simId)
            else SmsStore.writeOutgoing(m.phone, m.text, sim.subscriptionId, failed = false)
            Log.i("sms", t("Отправлено #${m.id} через SIM ${sim.slot} → ${m.phone}", "Sent #${m.id} via SIM ${sim.slot} → ${m.phone}"))
            Db.get(m.id)?.let { Events.emit(Events.SENT, it) }
        } else {
            fail(m, if (error == "timeout") t("Нет ответа от радиомодуля", "No response from the radio") else error, retry = true)
        }
    }

    private fun fail(m: Message, error: String, retry: Boolean) {
        val attempts = (Db.get(m.id)?.attempts ?: m.attempts)
        if (retry && attempts <= ConfigStore.value.sending.maxRetries) {
            Db.markRetry(m.id, error, 30_000L * attempts.coerceAtLeast(1))
            Log.w("sms", t("Повтор #${m.id}: $error", "Retry #${m.id}: $error"))
            poke()
        } else {
            Db.markFailed(m.id, error)
            val uri = Db.sysUri(m.id)
            if (uri != null) SmsStore.updateOutgoing(uri, android.provider.Telephony.Sms.MESSAGE_TYPE_FAILED, -1)
            else SmsStore.writeOutgoing(m.phone, m.text, -1, failed = true)
            Log.e("sms", t("Не отправлено #${m.id}: $error", "Not sent #${m.id}: $error"))
            Db.get(m.id)?.let { Events.emit(Events.FAILED, it) }
        }
    }

    private fun chooseSim(requestedId: Int, allowed: Set<Int>?): SimInfo {
        val sims = SimManager.active()
        if (sims.isEmpty()) throw SendException(t("Нет активных SIM-карт", "No active SIM cards"))
        val usage = Db.sentTodayBySim()
        fun available(s: SimInfo) = s.sendEnabled && (s.dailyLimit <= 0 || (usage[s.simId] ?: 0) < s.dailyLimit)

        if (requestedId > 0) {
            val s = sims.find { it.simId == requestedId } ?: throw SendException(t("Выбранная SIM не активна", "The selected SIM is not active"))
            if (!s.sendEnabled) throw SendException(t("Отправка через «${s.label}» отключена", "Sending via \"${s.label}\" is disabled"))
            if (!available(s)) throw SendException(t("Достигнут дневной лимит «${s.label}»", "Daily limit reached for \"${s.label}\""))
            return s
        }
        val pool = sims.filter { available(it) && (allowed == null || it.simId in allowed) }
        if (pool.isEmpty()) throw SendException(t("Нет доступных SIM (отключены, нет прав или исчерпан лимит)", "No available SIMs (disabled, no access or limit reached)"))
        val strategy = ConfigStore.value.sending.strategy
        return when {
            strategy.startsWith("slot") -> {
                val slot = strategy.removePrefix("slot").toIntOrNull()
                pool.find { it.slot == slot } ?: pool.first()
            }
            strategy.startsWith("sim:") -> pool.find { it.simId == strategy.removePrefix("sim:").toIntOrNull() } ?: pool.first()
            strategy == "roundrobin" -> pool[(rrIndex++).mod(pool.size)]
            strategy == "balanced" -> pool.minBy { usage[it.simId] ?: 0 }
            else -> pool.find { it.isDefault } ?: pool.first()
        }
    }

    private fun pi(action: String, id: Long, part: Int): PendingIntent {
        val intent = Intent(action, Uri.parse("simgate://$action/$id/$part"), ctx, SmsStatusReceiver::class.java)
            .putExtra("id", id).putExtra("part", part)
        return PendingIntent.getBroadcast(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Called from SmsStatusReceiver. error=null means the part was sent successfully. */
    internal fun onPartSent(id: Long, error: String?) {
        val p = pending[id]
        if (p == null) {
            // The result arrived after a timeout/restart; record it directly.
            if (error == null) Db.get(id)?.takeIf { it.status == "sending" }?.let { Db.markSent(id) }
            return
        }
        if (error != null) { p.result.complete(error); return }
        p.remaining--
        if (p.remaining <= 0) p.result.complete("")
    }

    internal fun onPartDelivered(id: Long, ok: Boolean) {
        if (!ok) {
            Db.markFailed(id, t("Оператор сообщил о недоставке", "Carrier reported non-delivery"))
            Db.sysUri(id)?.let { SmsStore.updateOutgoing(it, android.provider.Telephony.Sms.MESSAGE_TYPE_FAILED, -1) }
            Db.get(id)?.let { Events.emit(Events.FAILED, it) }
            return
        }
        if (Db.partDelivered(id)) Db.get(id)?.let {
            Log.i("sms", t("Доставлено #$id → ${it.phone}", "Delivered #$id → ${it.phone}"))
            Events.emit(Events.DELIVERED, it)
        }
    }
}
