package app.simgate.sms

import android.content.Context
import android.net.Uri
import android.os.Build
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
)

/**
 * SIM card memory. Android exposes it through the system SMS provider:
 *  • Android 11+: content://sms/icc_subId/<subId> for any SIM;
 *  • older versions: content://sms/icc for the default SMS SIM,
 *    content://sms/icc2 for the second SIM on Samsung/MTK firmwares.
 * Support depends on the firmware, so we try the variants and report when a SIM is not supported.
 */
object SimMemory {
    private lateinit var ctx: Context
    fun init(context: Context) { ctx = context.applicationContext }

    class Unsupported(msg: String) : Exception(msg)

    private fun candidates(sim: SimInfo): List<Uri> {
        val list = mutableListOf<Uri>()
        if (Build.VERSION.SDK_INT >= 30) list += Uri.parse("content://sms/icc_subId/${sim.simId}")
        if (sim.isDefault) list += Uri.parse("content://sms/icc")
        else if (sim.slot == 2) list += Uri.parse("content://sms/icc2")
        if (sim.slot == 1 && !sim.isDefault && Build.VERSION.SDK_INT < 30) list += Uri.parse("content://sms/icc1")
        return list
    }

    private fun statusOf(code: Int) = when (code) {
        1 -> "read"; 3 -> "unread"; 5 -> "sent"; 7 -> "unsent"; else -> "read"
    }

    private fun query(uri: Uri): List<IccMessage>? = runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val iIdx = c.getColumnIndex("index_on_icc"); val iAddr = c.getColumnIndex("address")
            val iBody = c.getColumnIndex("body"); val iDate = c.getColumnIndex("date"); val iSt = c.getColumnIndex("status")
            buildList {
                while (c.moveToNext()) add(IccMessage(
                    index = if (iIdx >= 0) c.getInt(iIdx) else c.position,
                    address = if (iAddr >= 0) c.getString(iAddr).orEmpty() else "",
                    body = if (iBody >= 0) c.getString(iBody).orEmpty() else "",
                    date = if (iDate >= 0) c.getLong(iDate) else 0,
                    status = if (iSt >= 0) statusOf(c.getInt(iSt)) else "read",
                ))
            }
        }
    }.getOrNull()

    private fun resolve(sim: SimInfo): Pair<Uri, List<IccMessage>> {
        if (!sim.active) throw Unsupported(t("SIM не активна", "The SIM is not active"))
        for (u in candidates(sim)) query(u)?.let { return u to it }
        throw Unsupported(t("Прошивка не даёт доступ к памяти этой SIM", "The firmware does not expose this SIM's memory"))
    }

    fun list(sim: SimInfo): List<IccMessage> = resolve(sim).second.sortedByDescending { it.date }

    fun delete(sim: SimInfo, index: Int): Boolean {
        val (base, _) = resolve(sim)
        val ok = runCatching { ctx.contentResolver.delete(Uri.withAppendedPath(base, index.toString()), null, null) > 0 }.getOrDefault(false)
        if (ok) Log.i("sms", t("Удалено SMS #$index из памяти «${sim.label}»", "SMS #$index deleted from \"${sim.label}\" memory"))
        return ok
    }

    /** Copies SMS from SIM memory into the phone's conversations (and deletes them from the SIM when move=true). */
    fun copyToPhone(sim: SimInfo, index: Int, move: Boolean): Boolean {
        val (_, items) = resolve(sim)
        val m = items.firstOrNull { it.index == index } ?: return false
        if (m.status == "sent" || m.status == "unsent") SmsStore.writeOutgoing(m.address, m.body, sim.simId, failed = false)
        else SmsStore.writeIncoming(m.address, m.body, m.date, sim.simId)
        if (move) delete(sim, index)
        return true
    }
}
