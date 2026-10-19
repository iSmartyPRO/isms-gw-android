package app.simgate.sms

import android.content.Context
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SmsMessage
import app.simgate.data.Log
import app.simgate.data.t
import kotlinx.serialization.Serializable

/** An SMS stored in the SIM card memory (ICC). */
@Serializable
data class IccMessage(
    val index: Int,
    val address: String,
    val body: String,
    val date: Long,
    /** read | unread | sent | unsent */
    val status: String,
    /** SIM indexes of every stored part of a long SMS, in order (one element for a regular SMS). */
    val parts: List<Int> = listOf(index),
    /** Number of parts the message was sent in; parts.size < total means some parts are missing. */
    val total: Int = 1,
) {
    val received get() = parts.size
}

/** A stored SIM record with its long-SMS part info (the provider path exposes no PDU). */
private typealias Raw = Pair<IccMessage, Concat.Part?>

/**
 * SIM card memory (ICC). Two access paths are tried, because support differs between firmwares:
 *  1. SmsManager for the specific subscription (getAllMessagesFromIcc / deleteMessageFromIcc).
 *     Works per SIM on dual-SIM phones; on Android 8-10 these methods are hidden and called via reflection.
 *  2. The system SMS provider: content://sms/icc_subId/<subId> (Android 11+), content://sms/icc
 *     (default SMS SIM) and content://sms/icc2 (second SIM on Samsung/MTK firmwares).
 * Every failed attempt is written to the log so unsupported firmwares can be diagnosed.
 */
object SimMemory {
    private lateinit var ctx: Context
    fun init(context: Context) { ctx = context.applicationContext }

    class Unsupported(msg: String) : Exception(msg)

    private sealed interface Source {
        data class Manager(val mgr: SmsManager) : Source
        data class Provider(val uri: Uri) : Source
    }

    private fun statusOf(code: Int) = when (code) {
        1 -> "read"; 3 -> "unread"; 5 -> "sent"; 7 -> "unsent"; else -> "read"
    }

    // ---------------------------------------------------------------- path 1: SmsManager

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    private fun managerList(mgr: SmsManager): List<Raw> {
        val m = SmsManager::class.java.getMethod("getAllMessagesFromIcc")
        val raw = m.invoke(mgr) as? List<SmsMessage?> ?: emptyList()
        return raw.filterNotNull().map { s ->
            IccMessage(
                index = s.indexOnIcc,
                address = s.displayOriginatingAddress ?: s.originatingAddress.orEmpty(),
                body = s.displayMessageBody ?: s.messageBody.orEmpty(),
                date = s.timestampMillis,
                status = statusOf(s.statusOnIcc),
            ) to Concat.parse(runCatching { s.pdu }.getOrNull())
        }
    }

    private fun managerDelete(mgr: SmsManager, index: Int): Boolean {
        val m = SmsManager::class.java.getMethod("deleteMessageFromIcc", Int::class.javaPrimitiveType)
        return m.invoke(mgr, index) as? Boolean ?: false
    }

    // ---------------------------------------------------------------- path 2: SMS provider

    private fun providerCandidates(sim: SimInfo): List<Uri> = buildList {
        if (Build.VERSION.SDK_INT >= 30) add(Uri.parse("content://sms/icc_subId/${sim.simId}"))
        if (sim.isDefault) add(Uri.parse("content://sms/icc"))
        if (sim.slot == 2) add(Uri.parse("content://sms/icc2"))
        if (sim.slot == 1 && !sim.isDefault) add(Uri.parse("content://sms/icc1"))
    }

    private fun providerList(uri: Uri): List<Raw> {
        val c = ctx.contentResolver.query(uri, null, null, null, null) ?: error("provider returned no cursor")
        return c.use {
            val iIdx = c.getColumnIndex("index_on_icc"); val iAddr = c.getColumnIndex("address")
            val iBody = c.getColumnIndex("body"); val iDate = c.getColumnIndex("date"); val iSt = c.getColumnIndex("status")
            buildList {
                while (c.moveToNext()) add(IccMessage(
                    index = if (iIdx >= 0) c.getInt(iIdx) else c.position,
                    address = if (iAddr >= 0) c.getString(iAddr).orEmpty() else "",
                    body = if (iBody >= 0) c.getString(iBody).orEmpty() else "",
                    date = if (iDate >= 0) c.getLong(iDate) else 0,
                    status = if (iSt >= 0) statusOf(c.getInt(iSt)) else "read",
                ) to null)
            }
        }
    }

    // ---------------------------------------------------------------- resolution

    private val lastReported = HashMap<Int, String>()

    private fun resolve(sim: SimInfo): Pair<Source, List<IccMessage>> {
        if (!sim.active) throw Unsupported(t("SIM не активна", "The SIM is not active"))
        val errors = mutableListOf<String>()
        runCatching { SimManager.smsManagerFor(sim.simId) }.getOrNull()?.let { mgr ->
            runCatching { managerList(mgr) }
                .onSuccess { return Source.Manager(mgr) to Concat.merge(it) }
                .onFailure { errors += "SmsManager: " + rootMessage(it) }
        }
        for (u in providerCandidates(sim)) {
            runCatching { providerList(u) }
                .onSuccess { return Source.Provider(u) to Concat.merge(it) }
                .onFailure { errors += "$u: " + rootMessage(it) }
        }
        val details = errors.joinToString("; ")
        if (lastReported[sim.simId] != details) {
            lastReported[sim.simId] = details
            Log.w("sms", t("Память SIM «${sim.label}» недоступна: $details", "SIM memory of \"${sim.label}\" is unavailable: $details"))
        }
        throw Unsupported(t(
            "Прошивка не даёт доступ к памяти этой SIM. Подробности — в журнале (категория sms).",
            "The firmware does not expose this SIM's memory. Details are in the log (category sms).",
        ))
    }

    private fun rootMessage(e: Throwable): String {
        var c: Throwable = e
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return c.javaClass.simpleName + (c.message?.let { ": $it" } ?: "")
    }

    fun list(sim: SimInfo): List<IccMessage> = resolve(sim).second.sortedByDescending { it.date }

    /** Deletes the message containing SIM record [index] — every part of a long SMS. */
    fun delete(sim: SimInfo, index: Int): Boolean {
        val (src, items) = resolve(sim)
        val m = items.firstOrNull { index in it.parts } ?: return false
        return deleteParts(sim, src, m)
    }

    private fun deleteParts(sim: SimInfo, src: Source, m: IccMessage): Boolean {
        val ok = m.parts.map { deleteRecord(src, it) }.all { it }
        if (ok) Log.i("sms", t("Удалено SMS #${m.index} из памяти «${sim.label}»", "SMS #${m.index} deleted from \"${sim.label}\" memory") +
            if (m.parts.size > 1) " (${m.parts.size})" else "")
        return ok
    }

    private fun deleteRecord(src: Source, index: Int): Boolean {
        return runCatching {
            when (src) {
                is Source.Manager -> managerDelete(src.mgr, index)
                is Source.Provider -> ctx.contentResolver.delete(Uri.withAppendedPath(src.uri, index.toString()), null, null) > 0
            }
        }.getOrElse { Log.w("sms", "SIM delete #$index: " + rootMessage(it)); false }
    }

    private fun writeToPhone(sim: SimInfo, m: IccMessage) {
        if (m.status == "sent" || m.status == "unsent") SmsStore.writeOutgoing(m.address, m.body, sim.simId, failed = false)
        else SmsStore.writeIncoming(m.address, m.body, m.date, sim.simId)
    }

    /** Copies one SMS from SIM memory into the phone's conversations (and deletes it from the SIM when move=true). */
    fun copyToPhone(sim: SimInfo, index: Int, move: Boolean): Boolean {
        val (src, items) = resolve(sim)
        val m = items.firstOrNull { index in it.parts } ?: return false
        writeToPhone(sim, m)
        if (move) deleteParts(sim, src, m)
        return true
    }

    data class SyncResult(val copied: Int, val deleted: Int)

    /** Synchronisation: copies every SMS from the SIM to the phone; with clearSim=true frees the SIM memory afterwards. */
    fun syncToPhone(sim: SimInfo, clearSim: Boolean): SyncResult {
        val (src, items) = resolve(sim)
        var deleted = 0
        items.forEach { m ->
            writeToPhone(sim, m)
            if (clearSim && deleteParts(sim, src, m)) deleted++
        }
        Log.i("sms", t("Синхронизация памяти «${sim.label}»: перенесено ${items.size}, удалено с SIM $deleted",
            "SIM memory sync \"${sim.label}\": copied ${items.size}, deleted from SIM $deleted"))
        return SyncResult(items.size, deleted)
    }
}
