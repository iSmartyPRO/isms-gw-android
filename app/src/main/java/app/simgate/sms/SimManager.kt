package app.simgate.sms

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import app.simgate.data.ConfigStore
import app.simgate.data.SimConfig
import kotlinx.serialization.Serializable

/**
 * SIM card / eSIM profile for the UI and API.
 * simId is the Android subscriptionId: a stable card identifier (used in permissions and the API).
 * slot is the current physical slot (1-based); 0 = the card is currently inactive.
 */
@Serializable
data class SimInfo(
    val simId: Int,
    val slot: Int,
    val active: Boolean,
    val embedded: Boolean,
    val carrier: String,
    val displayName: String,
    val number: String,
    val country: String,
    val label: String,
    val color: String,
    val sendEnabled: Boolean,
    val receiveEnabled: Boolean,
    val dailyLimit: Int,
    val isDefault: Boolean,
    val iccTail: String,
    val lastSeen: Long,
    val balanceCode: String = "",
) {
    /** Backward compatibility with API v1: the subscriptionId field. */
    val subscriptionId get() = simId
}

object SimManager {
    private lateinit var ctx: Context
    private val PALETTE = listOf("#34D399", "#818CF8", "#38BDF8", "#FBBF24", "#F472B6", "#A78BFA", "#FB923C", "#2DD4BF")

    fun init(context: Context) { ctx = context.applicationContext }

    fun hasPermission() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** Subscription list cache for 3 s: list() is called many times per request (once per recipient). */
    @Volatile private var subsCache: Pair<Long, List<SubscriptionInfo>>? = null

    @SuppressLint("MissingPermission")
    private fun activeSubs(): List<SubscriptionInfo> {
        if (!hasPermission()) return emptyList()
        subsCache?.let { (ts, list) -> if (System.currentTimeMillis() - ts < 3_000) return list }
        val sm = ctx.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val list = runCatching { sm.activeSubscriptionInfoList }.getOrNull().orEmpty()
        subsCache = System.currentTimeMillis() to list
        return list
    }

    /**
     * All known SIMs: active + previously seen (inactive). Updates the registry:
     * new cards get a color and a name; known ones get their carrier/number/slot updated.
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun list(includeInactive: Boolean = true): List<SimInfo> {
        val subs = activeSubs()
        val now = System.currentTimeMillis()
        val defaultSub = defaultSmsSubId()
        var registry = ConfigStore.value.sims
        var changed = false

        val active = subs.map { s ->
            val slot = s.simSlotIndex + 1
            val embedded = Build.VERSION.SDK_INT >= 28 && s.isEmbedded
            val icc = runCatching { s.iccId }.getOrNull().orEmpty().filter { it.isDigit() }.takeLast(4)
            val carrier = s.carrierName?.toString().orEmpty()
            val number = runCatching { s.number }.getOrNull().orEmpty()
            // Migration of old entries (by slot, without simId).
            var cfg = registry.find { it.simId == s.subscriptionId }
                ?: registry.find { it.simId == -1 && it.slot == slot }
            if (cfg == null) {
                cfg = SimConfig(
                    simId = s.subscriptionId, slot = slot, color = PALETTE[registry.size % PALETTE.size],
                    firstSeen = now,
                )
            }
            val updated = cfg.copy(
                simId = s.subscriptionId, slot = slot, embedded = embedded, carrier = carrier.ifBlank { cfg.carrier },
                number = number.ifBlank { cfg.number }, iccTail = icc.ifBlank { cfg.iccTail },
                color = cfg.color.ifBlank { PALETTE[registry.size % PALETTE.size] },
                lastSeen = if (now - cfg.lastSeen > 3_600_000) now else cfg.lastSeen,
            )
            if (updated != cfg || registry.none { it.simId == updated.simId }) {
                registry = registry.filter { it !== cfg && it.simId != updated.simId } + updated
                changed = true
            }
            toInfo(updated, s, defaultSub)
        }
        if (changed) {
            // Merge only system fields (carrier, number, slot…) into the current configuration,
            // so as not to overwrite concurrent user edits (name, limits, balance code).
            val fresh = registry.filter { r -> active.any { it.simId == r.simId } }
            ConfigStore.update { c ->
                val merged = c.sims.filter { s -> fresh.none { it.simId == s.simId } && !(s.simId == -1 && fresh.any { it.slot == s.slot }) }.toMutableList()
                fresh.forEach { f ->
                    val cur = c.sims.find { it.simId == f.simId }
                    merged += cur?.copy(slot = f.slot, embedded = f.embedded, carrier = f.carrier, number = f.number, iccTail = f.iccTail, lastSeen = f.lastSeen) ?: f
                }
                c.copy(sims = merged.sortedBy { it.firstSeen })
            }
            registry = ConfigStore.value.sims
        }

        val activeIds = active.map { it.simId }.toSet()
        val inactive = if (!includeInactive) emptyList() else registry
            .filter { it.simId > 0 && it.simId !in activeIds }
            .map { toInfo(it, null, defaultSub) }
        return active.sortedBy { it.slot } + inactive.sortedByDescending { it.lastSeen }
    }

    fun active(): List<SimInfo> = list(includeInactive = false)

    fun byId(simId: Int): SimInfo? = list().firstOrNull { it.simId == simId }
    fun activeBySlot(slot: Int): SimInfo? = active().firstOrNull { it.slot == slot }

    /** Remove an inactive SIM from the registry. */
    fun forget(simId: Int): Boolean {
        if (active().any { it.simId == simId }) return false
        ConfigStore.update { c -> c.copy(sims = c.sims.filter { it.simId != simId }) }
        return true
    }

    private fun toInfo(c: SimConfig, s: SubscriptionInfo?, defaultSub: Int) = SimInfo(
        simId = c.simId,
        slot = if (s != null) s.simSlotIndex + 1 else 0,
        active = s != null,
        embedded = c.embedded,
        carrier = s?.carrierName?.toString().orEmpty().ifBlank { c.carrier },
        displayName = s?.displayName?.toString().orEmpty().ifBlank { c.carrier },
        number = c.number,
        country = s?.countryIso.orEmpty().uppercase(),
        label = c.label.ifBlank { if (c.embedded) "eSIM ${c.slot}" else "SIM ${c.slot}" },
        color = c.color,
        sendEnabled = c.sendEnabled,
        receiveEnabled = c.receiveEnabled,
        dailyLimit = c.dailyLimit,
        isDefault = s != null && s.subscriptionId == defaultSub,
        iccTail = c.iccTail,
        lastSeen = c.lastSeen,
        balanceCode = c.balanceCode,
    )

    fun defaultSmsSubId(): Int = SubscriptionManager.getDefaultSmsSubscriptionId()

    @SuppressLint("MissingPermission")
    fun slotForSubId(subId: Int): Int {
        if (subId < 0 || !hasPermission()) return 0
        val sm = ctx.getSystemService(SubscriptionManager::class.java) ?: return 0
        return runCatching { sm.getActiveSubscriptionInfo(subId)?.simSlotIndex?.plus(1) }.getOrNull() ?: 0
    }

    /** subscriptionId by slot (for incoming messages where only the slot is known). */
    fun subIdForSlot(slot: Int): Int = active().firstOrNull { it.slot == slot }?.simId ?: -1

    @Suppress("DEPRECATION")
    fun smsManagerFor(subId: Int): SmsManager {
        if (subId < 0) {
            return if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        }
        return if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
        } else SmsManager.getSmsManagerForSubscriptionId(subId)
    }
}
