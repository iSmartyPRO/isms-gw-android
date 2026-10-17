package app.simgate.sms

import android.app.Activity
import app.simgate.data.t
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import app.simgate.SimGateApp
import app.simgate.data.ConfigStore
import app.simgate.data.Db
import app.simgate.data.Log
import kotlinx.coroutines.launch

/** Send results and delivery reports. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        if (id < 0) return
        when (intent.action) {
            SmsSender.ACTION_SENT -> {
                val err = when (resultCode) {
                    Activity.RESULT_OK -> null
                    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> t("Общая ошибка отправки", "Generic send failure")
                    SmsManager.RESULT_ERROR_RADIO_OFF -> t("Радиомодуль выключен", "Radio is off")
                    SmsManager.RESULT_ERROR_NULL_PDU -> t("Ошибка PDU", "PDU error")
                    SmsManager.RESULT_ERROR_NO_SERVICE -> t("Нет сети", "No service")
                    else -> t("Ошибка отправки (код $resultCode)", "Send error (code $resultCode)")
                }
                SmsSender.onPartSent(id, err)
            }
            SmsSender.ACTION_DELIVERED -> {
                val pdu = intent.getByteArrayExtra("pdu")
                val format = intent.getStringExtra("format")
                val status = runCatching { SmsMessage.createFromPdu(pdu, format).status }.getOrDefault(0)
                // TP-Status: 0..31 = delivered, 32..63 = pending, ≥64 = error.
                // CDMA and some firmwares return other codes; treat as success if resultCode is OK.
                when {
                    status in 32..63 -> Unit
                    status >= 64 && format == "3gpp" -> SmsSender.onPartDelivered(id, false)
                    else -> SmsSender.onPartDelivered(id, true)
                }
            }
        }
    }
}

/** Incoming SMS. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val msgs = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        if (msgs.isEmpty()) return
        val slot = resolveSlot(intent)
        val subId = subIdOf(intent)
        // System storage and notification: always, like a regular SMS app.
        val sysUris = HashMap<String, String>()
        msgs.groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "" }.forEach { (from, parts) ->
            val text = parts.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
            SmsStore.writeIncoming(from, text, parts.first().timestampMillis, subId)?.let { sysUris[from] = it }
            SmsStore.notifyIncoming(from, text)
        }
        val pending = goAsync()
        SimGateApp.scope.launch {
            try {
                // simId: from extras, otherwise by slot
                val simId = if (subId > 0) subId else SimManager.subIdForSlot(slot)
                handle(msgs, if (slot > 0) slot else SimManager.slotForSubId(simId), simId, sysUris)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handle(msgs: Array<SmsMessage>, slot: Int, simId: Int, sysUris: Map<String, String>) {
        val cfg = ConfigStore.value
        val simCfg = cfg.sims.find { it.simId == simId && simId > 0 } ?: cfg.sims.find { it.slot == slot && slot > 0 }
        if (simCfg != null && !simCfg.receiveEnabled) return
        // Multipart SMS arrive in a batch; join them by sender.
        msgs.groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "" }.forEach { (from, parts) ->
            val text = parts.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
            if (cfg.receiving.ignoreSenders.any { it.isNotBlank() && from.contains(it, ignoreCase = true) }) return@forEach
            val ts = parts.first().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
            if (!cfg.receiving.store) {
                // Without storage: still publish the event via a temporary record.
                val m = Db.insertIncoming(from, text, slot, ts, simId)
                Events.emit(Events.RECEIVED, m)
                Db.delete(m.id)
                return@forEach
            }
            val m = Db.insertIncoming(from, text, slot, ts, simId)
            sysUris[from]?.let { Db.setSysUri(m.id, it) }
            Log.i("sms", t("Входящее", "Incoming") + " #${m.id} " + t("от", "from") + " $from (SIM ${if (slot > 0) slot else "?"})")
            Events.emit(Events.RECEIVED, m)
        }
    }

    private fun subIdOf(intent: Intent): Int {
        val ex = intent.extras ?: return -1
        for (k in listOf("android.telephony.extra.SUBSCRIPTION_INDEX", "subscription", "sub_id", "subscription_id")) {
            val v = ex.get(k)
            return (v as? Int) ?: (v as? Long)?.toInt() ?: continue
        }
        return -1
    }

    @Suppress("DEPRECATION")
    private fun resolveSlot(intent: Intent): Int {
        val ex = intent.extras ?: return 0
        val subKeys = listOf("android.telephony.extra.SUBSCRIPTION_INDEX", "subscription", "sub_id", "subscription_id")
        for (k in subKeys) {
            val v = ex.get(k)
            val sub = (v as? Int) ?: (v as? Long)?.toInt() ?: continue
            val slot = SimManager.slotForSubId(sub)
            if (slot > 0) return slot
        }
        // Samsung / MTK: explicit slot index (0-based)
        for (k in listOf("android.telephony.extra.SLOT_INDEX", "slot", "simSlot", "slot_id", "simId", "phone")) {
            val v = ex.get(k)
            val s = (v as? Int) ?: (v as? Long)?.toInt() ?: continue
            if (s >= 0) return s + 1
        }
        return 0
    }
}
