package app.simgate.sms

import app.simgate.data.ConfigStore
import app.simgate.data.DestinationFilter
import app.simgate.data.Log
import app.simgate.data.t
import kotlinx.serialization.Serializable

/**
 * Outgoing SMS destination filter: protects against accidental or abusive sending to
 * unwanted countries (e.g. +2… Africa), satellite numbers or premium short codes.
 *
 * Rules are number prefixes in international format: "+7", "+44 7700", "+1 555 0147".
 * Spaces, dashes and a trailing "*" are ignored. Text after "#" is a comment.
 *  • blocklist always wins;
 *  • allowlist (when enabled and not empty) lets through only matching numbers;
 *  • short codes (3–6 digits, no "+") can be blocked separately;
 *  • local numbers (no "+"): "00…" is treated as "+…"; others are checked against
 *    "default country code" when it is set, otherwise follow the local-numbers switch.
 */
object Destinations {
    @Serializable
    data class Verdict(val allowed: Boolean, val normalized: String, val reason: String, val rule: String = "")

    fun cleanRule(raw: String): String? {
        val r = raw.substringBefore('#').trim().replace(Regex("[\\s\\-*xX]"), "")
        if (r.isEmpty()) return null
        return if (Regex("^\\+?[0-9]{1,15}$").matches(r)) r else null
    }

    fun isValidRule(raw: String) = raw.substringBefore('#').isBlank() || cleanRule(raw) != null

    /** Normalised form used for matching: "+<digits>" for international numbers, digits otherwise. */
    fun normalize(phone: String, f: DestinationFilter): String {
        val p = phone.trim().replace(Regex("[\\s()\\-]"), "")
        return when {
            p.startsWith("+") -> p
            p.startsWith("00") -> "+" + p.drop(2)
            f.defaultCountryCode.isNotBlank() && p.length > 6 -> {
                val cc = f.defaultCountryCode.trim().removePrefix("+")
                val local = if (f.trunkPrefix.isNotBlank() && p.startsWith(f.trunkPrefix)) p.drop(f.trunkPrefix.length) else p
                "+" + cc + local
            }
            else -> p
        }
    }

    private fun matches(number: String, rule: String): Boolean {
        val r = cleanRule(rule) ?: return false
        return if (r.startsWith("+")) number.startsWith(r) else number.removePrefix("+").startsWith(r)
    }

    fun check(phone: String, f: DestinationFilter = ConfigStore.value.sending.destinations): Verdict {
        val n = normalize(phone, f)
        val digits = n.removePrefix("+")
        if (!f.enabled) return Verdict(true, n, t("Фильтр выключен", "Filter is off"))
        val short = !n.startsWith("+") && digits.length in 3..6
        if (short && f.blockShortCodes)
            return Verdict(false, n, t("Короткие и платные номера запрещены", "Short and premium codes are blocked"))
        f.blocklist.firstOrNull { cleanRule(it) != null && matches(n, it) }?.let {
            return Verdict(false, n, t("Направление в чёрном списке", "Destination is blocklisted"), it.substringBefore('#').trim())
        }
        if (!n.startsWith("+") && !short && !f.allowLocal)
            return Verdict(false, n, t("Номера без международного кода запрещены", "Numbers without an international code are blocked"))
        val allow = f.allowlist.filter { cleanRule(it) != null }
        if (f.allowlistOnly && allow.isNotEmpty()) {
            val hit = allow.firstOrNull { matches(n, it) }
                ?: return Verdict(false, n, t("Направление не входит в белый список", "Destination is not in the allowlist"))
            return Verdict(true, n, t("Разрешено белым списком", "Allowed by the allowlist"), hit.substringBefore('#').trim())
        }
        return Verdict(true, n, t("Разрешено", "Allowed"))
    }

    /** Throws [SendException] when the number is not allowed; logs the attempt in the security log. */
    fun enforce(phone: String, source: String) {
        val v = check(phone)
        if (v.allowed) return
        Log.sec(t("Заблокирована отправка на ${v.normalized} ($source): ${v.reason}", "Blocked sending to ${v.normalized} ($source): ${v.reason}") +
            if (v.rule.isNotEmpty()) " [${v.rule}]" else "")
        throw SendException(t("Отправка на ${v.normalized} запрещена фильтром направлений: ${v.reason}", "Sending to ${v.normalized} is blocked by the destination filter: ${v.reason}") +
            if (v.rule.isNotEmpty()) " (${v.rule})" else "")
    }
}
