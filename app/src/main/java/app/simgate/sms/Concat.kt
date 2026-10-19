package app.simgate.sms

/**
 * Concatenated-SMS header parser for raw GSM PDUs (3GPP TS 23.040).
 * The public SmsMessage API hides the user-data header, so the parts of a long SMS stored
 * in SIM memory are linked by reading the concatenation IE (0x00 / 0x08) from the PDU directly.
 */
object Concat {
    data class Part(val ref: Int, val count: Int, val seq: Int)

    /**
     * @param pdu PDU as returned by SmsMessage.getPdu(), starting with the SMSC address
     *            (set [withSmsc] = false for a bare TPDU).
     * @return concatenation info, or null for a single-part message or an unparseable PDU.
     */
    fun parse(pdu: ByteArray?, withSmsc: Boolean = true): Part? = runCatching { parseOrThrow(pdu ?: return null, withSmsc) }.getOrNull()

    private fun parseOrThrow(p: ByteArray, withSmsc: Boolean): Part? {
        fun u(i: Int) = p[i].toInt() and 0xFF
        var i = if (withSmsc) 1 + u(0) else 0
        val first = u(i++)
        if (first and 0x40 == 0) return null // TP-UDHI: no user-data header
        when (first and 0x03) {
            0 -> { // SMS-DELIVER: OA, PID, DCS, SCTS
                i += 2 + (u(i) + 1) / 2
                i += 2 + 7
            }
            1 -> { // SMS-SUBMIT: MR, DA, PID, DCS, VP
                i += 1
                i += 2 + (u(i) + 1) / 2
                i += 2
                i += when ((first shr 3) and 0x03) { 0 -> 0; 2 -> 1; else -> 7 }
            }
            else -> return null
        }
        i += 1 // TP-UDL
        val udhl = u(i++)
        val end = i + udhl
        if (end > p.size) return null
        while (i + 1 < end) {
            val iei = u(i); val len = u(i + 1); val d = i + 2
            if (d + len > end) return null
            when {
                iei == 0x00 && len == 3 -> return part(u(d), u(d + 1), u(d + 2))
                iei == 0x08 && len == 4 -> return part((u(d) shl 8) or u(d + 1), u(d + 2), u(d + 3))
            }
            i = d + len
        }
        return null
    }

    private fun part(ref: Int, count: Int, seq: Int) = if (count > 1 && seq in 1..count) Part(ref, count, seq) else null

    /**
     * Joins the parts of long SMS into one message. Parts are matched by sender, reference number,
     * part count and direction; a message keeps every SIM index it consists of in [IccMessage.parts].
     * Missing parts leave the message incomplete ([IccMessage.received] < [IccMessage.total]).
     */
    fun merge(items: List<Pair<IccMessage, Part?>>): List<IccMessage> {
        val singles = mutableListOf<IccMessage>()
        val groups = LinkedHashMap<String, MutableList<Pair<IccMessage, Part>>>()
        for ((m, p) in items) {
            if (p == null) { singles += m; continue }
            val outgoing = m.status == "sent" || m.status == "unsent"
            groups.getOrPut("${m.address}|${p.ref}|${p.count}|$outgoing") { mutableListOf() } += m to p
        }
        val joined = groups.values.flatMap { g ->
            // A reference number can be reused for a later message: duplicates of a sequence start a new chain.
            val chains = mutableListOf<MutableList<Pair<IccMessage, Part>>>()
            for (e in g.sortedBy { it.first.index }) {
                val chain = chains.firstOrNull { c -> c.none { it.second.seq == e.second.seq } }
                if (chain != null) chain += e else chains += mutableListOf(e)
            }
            chains.map { c ->
                val parts = c.sortedBy { it.second.seq }
                val head = parts.first().first
                head.copy(
                    index = parts.minOf { it.first.index },
                    body = parts.joinToString("") { it.first.body },
                    date = parts.map { it.first.date }.filter { it > 0 }.minOrNull() ?: 0,
                    status = if (parts.any { it.first.status == "unread" }) "unread" else head.status,
                    parts = parts.map { it.first.index },
                    total = parts.first().second.count,
                )
            }
        }
        return singles + joined
    }
}
