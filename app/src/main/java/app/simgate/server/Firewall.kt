package app.simgate.server

import app.simgate.data.ConfigStore
import app.simgate.data.t
import app.simgate.data.Log
import kotlinx.serialization.Serializable
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class Ban(val ip: String, val until: Long, val reason: String)

/**
 * Built-in firewall:
 *  1. Blacklist (CIDR): always denied.
 *  2. Temporary bans for key/password brute force.
 *  3. Access mode: LAN only / whitelist only / everyone.
 *  4. Separate restrictions for the web panel.
 *  5. Per-IP requests-per-minute limit.
 */
object Firewall {
    sealed class Verdict { data object Allow : Verdict(); data class Deny(val code: Int, val reason: String) : Verdict() }

    private val bans = ConcurrentHashMap<String, Ban>()
    private val failures = ConcurrentHashMap<String, MutableList<Long>>()
    private val hits = ConcurrentHashMap<String, IntArray>() // [minute, count]

    fun check(rawIp: String, admin: Boolean): Verdict {
        val ip = normalize(rawIp)
        val fw = ConfigStore.value.firewall
        val addr = parse(ip) ?: return Verdict.Deny(403, "bad address")

        if (fw.blacklist.any { matches(addr, it) }) return Verdict.Deny(403, "blacklisted")
        bans[ip]?.let { b ->
            if (b.until > System.currentTimeMillis()) return Verdict.Deny(403, "banned")
            bans.remove(ip)
        }
        val allowed = when (fw.mode) {
            "all" -> true
            "whitelist" -> addr.isLoopbackAddress || fw.whitelist.any { matches(addr, it) }
            else -> isPrivate(addr) || fw.whitelist.any { matches(addr, it) }
        }
        if (!allowed) return Verdict.Deny(403, "not allowed by policy '${fw.mode}'")
        if (admin) {
            if (fw.adminLanOnly && !isPrivate(addr)) return Verdict.Deny(403, "admin: LAN only")
            if (fw.adminWhitelist.isNotEmpty() && !addr.isLoopbackAddress && fw.adminWhitelist.none { matches(addr, it) })
                return Verdict.Deny(403, "admin: not in admin whitelist")
        }
        if (fw.rateLimitPerMinute > 0) {
            val minute = (System.currentTimeMillis() / 60_000).toInt()
            val h = hits.getOrPut(ip) { intArrayOf(minute, 0) }
            synchronized(h) {
                if (h[0] != minute) { h[0] = minute; h[1] = 0 }
                h[1]++
                if (h[1] > fw.rateLimitPerMinute) return Verdict.Deny(429, "rate limit")
            }
        }
        return Verdict.Allow
    }

    /** Records a failed authentication; the IP is blocked once the threshold is exceeded. */
    fun authFailed(rawIp: String, what: String) {
        val ip = normalize(rawIp)
        val fw = ConfigStore.value.firewall
        val now = System.currentTimeMillis()
        val list = failures.getOrPut(ip) { mutableListOf() }
        val count = synchronized(list) {
            list.removeAll { it < now - 15 * 60_000 }
            list += now
            list.size
        }
        Log.sec(t("Неудачная аутентификация ($what) с $ip [$count/${fw.maxAuthFailures}]", "Failed authentication ($what) from $ip [$count/${fw.maxAuthFailures}]"))
        if (fw.maxAuthFailures > 0 && count >= fw.maxAuthFailures) {
            val ban = Ban(ip, now + fw.banMinutes * 60_000L, t("Перебор: $what", "Brute force: $what"))
            bans[ip] = ban
            runCatching { app.simgate.data.Db.saveBan(ban.ip, ban.until, ban.reason) }
            failures.remove(ip)
            Log.sec(t("IP $ip заблокирован на ${fw.banMinutes} мин.", "IP $ip banned for ${fw.banMinutes} min"))
        }
    }

    fun authOk(rawIp: String) { failures.remove(normalize(rawIp)) }

    fun bans(): List<Ban> {
        val now = System.currentTimeMillis()
        bans.values.removeAll { it.until < now }
        return bans.values.sortedByDescending { it.until }
    }

    fun unban(ip: String) { bans.remove(ip); runCatching { app.simgate.data.Db.deleteBan(ip) } }

    /** Restore bans from the DB after a restart. */
    fun restore() { runCatching { app.simgate.data.Db.loadBans().forEach { (ip, until, reason) -> bans[ip] = Ban(ip, until, reason) } } }

    fun normalize(ip: String) = ip.substringBefore('%').removePrefix("/").let {
        if (it.startsWith("::ffff:") && it.count { c -> c == '.' } == 3) it.removePrefix("::ffff:") else it
    }

    /** Parses an IP literal without DNS lookups. */
    fun parse(ip: String): InetAddress? {
        val s = ip.trim()
        val looksLikeIp = Regex("^[0-9.]+$").matches(s) || (s.contains(':') && Regex("^[0-9a-fA-F:.]+$").matches(s))
        if (!looksLikeIp) return null
        return runCatching { InetAddress.getByName(s) }.getOrNull()
    }

    fun isValidRule(rule: String): Boolean {
        val (ip, bits) = rule.trim().split('/').let { it[0] to it.getOrNull(1) }
        val a = parse(ip) ?: return false
        if (bits == null) return true
        val b = bits.toIntOrNull() ?: return false
        return b in 0..(a.address.size * 8)
    }

    fun matches(addr: InetAddress, rule: String): Boolean {
        val r = rule.trim()
        if (r.isEmpty()) return false
        val parts = r.split('/')
        val net = parse(parts[0]) ?: return false
        val a = addr.address; val n = net.address
        if (a.size != n.size) return false
        val bits = parts.getOrNull(1)?.toIntOrNull() ?: (a.size * 8)
        var remaining = bits
        for (i in a.indices) {
            if (remaining <= 0) return true
            val mask = if (remaining >= 8) 0xFF else (0xFF shl (8 - remaining)) and 0xFF
            if ((a[i].toInt() and mask) != (n[i].toInt() and mask)) return false
            remaining -= 8
        }
        return true
    }

    fun isPrivate(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
        if (a is Inet6Address) {
            val b0 = a.address[0].toInt() and 0xFF
            return (b0 and 0xFE) == 0xFC // fc00::/7 — unique local
        }
        return false
    }
}
