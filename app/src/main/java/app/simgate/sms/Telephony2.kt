package app.simgate.sms

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.simgate.data.ConfigStore
import app.simgate.data.Db
import app.simgate.data.Log
import app.simgate.data.t
import app.simgate.net.TelegramBot
import app.simgate.net.WebhookDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private fun hasPerm(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

/**
 * USSD requests via the selected SIM (Android 8.0+: TelephonyManager.sendUssdRequest).
 * Single-step requests are supported (balance, remaining bundles, SIM number).
 * Requests run one at a time, since the radio does not handle parallel USSD sessions.
 */
object UssdService {
    private lateinit var ctx: Context
    private val mutex = Mutex()
    fun init(context: Context) { ctx = context.applicationContext }

    fun validCode(code: String) = Regex("^[*#][0-9*#]{1,40}#$").matches(code.trim())

    class UssdException(msg: String) : Exception(msg)

    /** Runs the request and returns a history record (status ok | error). */
    @SuppressLint("MissingPermission")
    suspend fun run(code: String, sim: SimInfo, source: String, owner: String): Db.Ussd {
        val c = code.trim()
        if (!validCode(c)) throw UssdException(t("Некорректный USSD-код. Пример: *100#", "Invalid USSD code. Example: *100#"))
        if (!sim.active) throw UssdException(t("SIM «${sim.label}» не активна", "SIM \"${sim.label}\" is not active"))
        if (!hasPerm(ctx, Manifest.permission.CALL_PHONE)) throw UssdException(t("Нет разрешения «Телефон» (CALL_PHONE)", "Phone permission (CALL_PHONE) is missing"))
        val id = Db.insertUssd(sim.simId, sim.slot, c, source, owner)
        val result: Pair<Boolean, String> = mutex.withLock {
            withTimeoutOrNull(45_000) {
                suspendCancellableCoroutine { cont ->
                    val tm = ctx.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sim.simId)
                    try {
                        tm.sendUssdRequest(c, object : TelephonyManager.UssdResponseCallback() {
                            override fun onReceiveUssdResponse(t: TelephonyManager, request: String, response: CharSequence) {
                                if (cont.isActive) cont.resume(true to response.toString())
                            }
                            override fun onReceiveUssdResponseFailed(t: TelephonyManager, request: String, failureCode: Int) {
                                val msg = when (failureCode) {
                                    TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> app.simgate.data.t("USSD-сервис недоступен", "USSD service unavailable")
                                    else -> app.simgate.data.t("Оператор вернул ошибку или запрос требует меню", "The carrier returned an error or the request needs a menu")
                                }
                                if (cont.isActive) cont.resume(false to msg)
                            }
                        }, Handler(Looper.getMainLooper()))
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(false to (e.message ?: e.javaClass.simpleName))
                    }
                }
            } ?: (false to t("Нет ответа от оператора (таймаут)", "No response from the carrier (timeout)"))
        }
        Db.finishUssd(id, result.first, result.second)
        val rec = Db.getUssd(id)!!
        Log.i("ussd", "$c → ${sim.label}: " + (if (result.first) "OK" else result.second))
        WebhookDispatcher.dispatch(Events.USSD, ConfigStore.json.encodeToJsonElement(Db.Ussd.serializer(), rec))
        return rec
    }
}

/**
 * Calls: log and incoming/missed call notifications, click-to-call.
 * Audio is not transmitted: Android does not allow regular apps to access call audio.
 */
object CallService {
    private lateinit var ctx: Context
    private val listeners = mutableListOf<Pair<TelephonyManager, PhoneStateListener>>()
    /** Current call by simId → call log record id. */
    private val current = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context) { ctx = context.applicationContext }

    class CallException(msg: String) : Exception(msg)

    /** Start tracking calls on all active SIMs. */
    fun start() = main.post {
        stopNow()
        if (!ConfigStore.value.calls.enabled || !hasPerm(ctx, Manifest.permission.READ_PHONE_STATE)) return@post
        SimManager.active().forEach { sim ->
            val tm = ctx.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sim.simId)
            @Suppress("DEPRECATION")
            val l = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(sim, state, phoneNumber.orEmpty())
            }
            @Suppress("DEPRECATION")
            runCatching { tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE); listeners += tm to l }
        }
    }

    fun stop() = main.post { stopNow() }

    private fun stopNow() {
        @Suppress("DEPRECATION")
        listeners.forEach { (tm, l) -> runCatching { tm.listen(l, PhoneStateListener.LISTEN_NONE) } }
        listeners.clear()
    }

    private fun onState(sim: SimInfo, state: Int, number: String) {
        val cfg = ConfigStore.value.calls
        val curId = current[sim.simId]
        val cur = curId?.let { Db.getCall(it) }
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (cur == null || cur.status !in setOf("ringing")) {
                    val id = Db.insertCall(sim.simId, sim.slot, number, "in", "ringing")
                    current[sim.simId] = id
                    val rec = Db.getCall(id)!!
                    emit(Events.CALL_RINGING, rec)
                    if (cfg.notifyIncoming) TelegramBot.onCall(Events.CALL_RINGING, rec, sim)
                } else if (number.isNotEmpty()) Db.updateCall(cur.id, cur.status, number = number)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                when {
                    cur != null && cur.status == "ringing" -> Db.updateCall(cur.id, "answered", answered = true)
                    cur != null && cur.status == "dialing" -> Db.updateCall(cur.id, "active", answered = true)
                    cur == null -> current[sim.simId] = Db.insertCall(sim.simId, sim.slot, number, "out", "active", "phone").also { Db.updateCall(it, "active", answered = true) }
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (cur != null) {
                    val missed = cur.status == "ringing"
                    Db.updateCall(cur.id, if (missed) "missed" else if (cur.status == "dialing") "failed" else "ended", ended = true)
                    val rec = Db.getCall(cur.id)!!
                    emit(if (missed) Events.CALL_MISSED else Events.CALL_ENDED, rec)
                    if (missed) {
                        Log.i("calls", t("Пропущенный звонок", "Missed call") + " ${rec.number.ifEmpty { "?" }} (${sim.label})")
                        if (cfg.notifyMissed) TelegramBot.onCall(Events.CALL_MISSED, rec, sim)
                    }
                }
                current.remove(sim.simId)
            }
        }
    }

    private fun emit(event: String, c: Db.Call) =
        WebhookDispatcher.dispatch(event, ConfigStore.json.encodeToJsonElement(Db.Call.serializer(), c))

    /** Click-to-call: the phone dials the number via the selected SIM. */
    @SuppressLint("MissingPermission")
    fun dial(number: String, sim: SimInfo, source: String, owner: String): Db.Call {
        val n = SmsSender.validatePhone(number) ?: throw CallException(t("Некорректный номер: $number", "Invalid number: $number"))
        if (!sim.active) throw CallException(t("SIM «${sim.label}» не активна", "SIM \"${sim.label}\" is not active"))
        if (!hasPerm(ctx, Manifest.permission.CALL_PHONE)) throw CallException(t("Нет разрешения «Телефон» (CALL_PHONE)", "Phone permission (CALL_PHONE) is missing"))
        val telecom = ctx.getSystemService(TelecomManager::class.java)
        val extras = Bundle()
        accountFor(sim)?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        val id = Db.insertCall(sim.simId, sim.slot, n, "out", "dialing", source, owner)
        current[sim.simId] = id
        try {
            telecom.placeCall(Uri.fromParts("tel", n, null), extras)
        } catch (e: Exception) {
            Db.updateCall(id, "failed", ended = true)
            throw CallException(t("Не удалось начать звонок: ${e.message}", "Could not start the call: ${e.message}"))
        }
        Log.i("calls", t("Звонок по клику", "Click-to-call") + " → $n (${sim.label}, $source)")
        return Db.getCall(id)!!
    }

    /** PhoneAccountHandle for a SIM: by subscriptionId (Android 11+) or by ICCID/subId in the account id. */
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun accountFor(sim: SimInfo): PhoneAccountHandle? {
        val telecom = ctx.getSystemService(TelecomManager::class.java)
        val accounts = runCatching { telecom.callCapablePhoneAccounts }.getOrNull().orEmpty()
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val iccid = runCatching {
            ctx.getSystemService(SubscriptionManager::class.java).getActiveSubscriptionInfo(sim.simId)?.iccId
        }.getOrNull().orEmpty()
        return accounts.firstOrNull { h ->
            (Build.VERSION.SDK_INT >= 30 && runCatching { tm.getSubscriptionId(h) }.getOrDefault(-1) == sim.simId) ||
                h.id == sim.simId.toString() || (iccid.isNotEmpty() && (h.id == iccid || h.id.startsWith(iccid) || iccid.startsWith(h.id)))
        } ?: accounts.getOrNull(sim.slot - 1)
    }
}
