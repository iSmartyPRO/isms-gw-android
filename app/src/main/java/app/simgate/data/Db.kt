package app.simgate.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import java.util.Calendar

@Serializable
data class Message(
    val id: Long,
    val direction: String,       // in | out
    val phone: String,
    val text: String,
    val sim: Int,                // slot 1..N, 0 = unknown / auto
    val simId: Int = -1,         // SIM/eSIM subscriptionId, -1 = not selected
    val status: String,          // queued | sending | sent | delivered | failed | received
    val error: String? = null,
    val parts: Int = 1,
    val source: String = "",     // api | web | remote | app | sim
    val ref: String? = null,     // client-side external ID
    val attempts: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val sentAt: Long = 0,
    val deliveredAt: Long = 0,
    val acked: Boolean = false,
    val owner: String = "",      // sender user ID (for outgoing messages)
)

@Serializable
data class LogEntry(val id: Long, val ts: Long, val level: String, val category: String, val message: String)

@Serializable
data class Stats(
    val sentToday: Int, val receivedToday: Int, val failedToday: Int, val queued: Int,
    val totalOut: Int, val totalIn: Int, val perSimToday: Map<Int, Int>,
)

object Db {
    private lateinit var helper: SQLiteOpenHelper
    /** Change counter: the UI subscribes to it and reloads data. */
    val revision = MutableStateFlow(0L)

    fun init(context: Context) {
        helper = object : SQLiteOpenHelper(context, "ismsgw.db", null, 3) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE messages(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, direction TEXT NOT NULL, phone TEXT NOT NULL,
                    text TEXT NOT NULL, sim INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, error TEXT,
                    parts INTEGER NOT NULL DEFAULT 1, parts_sent INTEGER NOT NULL DEFAULT 0,
                    parts_delivered INTEGER NOT NULL DEFAULT 0, source TEXT, ref TEXT,
                    attempts INTEGER NOT NULL DEFAULT 0, next_at INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                    sent_at INTEGER NOT NULL DEFAULT 0, delivered_at INTEGER NOT NULL DEFAULT 0,
                    acked INTEGER NOT NULL DEFAULT 0, remote_synced INTEGER NOT NULL DEFAULT 0,
                    sim_id INTEGER NOT NULL DEFAULT -1, owner TEXT NOT NULL DEFAULT '', allowed TEXT, sys_uri TEXT)"""
                )
                db.execSQL("CREATE INDEX idx_msg_dir ON messages(direction, id)")
                db.execSQL("CREATE INDEX idx_msg_status ON messages(status, next_at)")
                db.execSQL("CREATE INDEX idx_msg_sim ON messages(sim_id, id)")
                createV3(db)
                db.execSQL(
                    "CREATE TABLE logs(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                        "level TEXT NOT NULL, category TEXT NOT NULL, message TEXT NOT NULL)"
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // v2: users and SIMs by subscriptionId
                if (oldVersion < 2) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN sim_id INTEGER NOT NULL DEFAULT -1")
                    db.execSQL("ALTER TABLE messages ADD COLUMN owner TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE messages ADD COLUMN allowed TEXT")
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_msg_sim ON messages(sim_id, id)")
                }
                // v3: webhook queue, bans, USSD, calls, link to system SMS
                if (oldVersion < 3) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN sys_uri TEXT")
                    createV3(db)
                }
            }
        }
        // Time index: auto-cleanup, statistics and duplicate checks when importing a backup.
        runCatching { helper.writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_msg_created ON messages(created_at)") }
        // Messages stuck in "sending" after a restart are returned to the queue.
        helper.writableDatabase.execSQL("UPDATE messages SET status='queued' WHERE status='sending'")
    }

    private fun createV3(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS webhook_queue(id INTEGER PRIMARY KEY AUTOINCREMENT, hook_id TEXT NOT NULL, " +
                "event TEXT NOT NULL, body TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_at INTEGER NOT NULL DEFAULT 0, " +
                "error TEXT, created_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS bans(ip TEXT PRIMARY KEY, until INTEGER NOT NULL, reason TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS ussd(id INTEGER PRIMARY KEY AUTOINCREMENT, sim_id INTEGER NOT NULL, slot INTEGER NOT NULL, " +
                "code TEXT NOT NULL, status TEXT NOT NULL, response TEXT, error TEXT, source TEXT, owner TEXT, " +
                "created_at INTEGER NOT NULL, finished_at INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS calls(id INTEGER PRIMARY KEY AUTOINCREMENT, sim_id INTEGER NOT NULL, slot INTEGER NOT NULL, " +
                "number TEXT NOT NULL, direction TEXT NOT NULL, status TEXT NOT NULL, source TEXT, owner TEXT, " +
                "started_at INTEGER NOT NULL, answered_at INTEGER NOT NULL DEFAULT 0, ended_at INTEGER NOT NULL DEFAULT 0)"
        )
    }

    private val db get() = helper.writableDatabase
    private fun now() = System.currentTimeMillis()
    private fun bump() { revision.value = revision.value + 1 }

    // ---------- messages ----------

    fun insertOutgoing(
        phone: String, text: String, sim: Int, source: String, ref: String?,
        simId: Int = -1, owner: String = "", allowed: Set<Int>? = null,
    ): Message {
        val t = now()
        val cv = ContentValues().apply {
            put("direction", "out"); put("phone", phone); put("text", text); put("sim", sim)
            put("sim_id", simId); put("owner", owner); put("allowed", allowed?.joinToString(","))
            put("status", "queued"); put("source", source); put("ref", ref)
            put("created_at", t); put("updated_at", t)
        }
        val id = db.insert("messages", null, cv)
        bump()
        return get(id)!!
    }

    fun insertIncoming(phone: String, text: String, sim: Int, receivedAt: Long, simId: Int = -1): Message {
        val t = now()
        val cv = ContentValues().apply {
            put("direction", "in"); put("phone", phone); put("text", text); put("sim", sim); put("sim_id", simId)
            put("status", "received"); put("source", "sim")
            put("created_at", receivedAt); put("updated_at", t)
        }
        val id = db.insert("messages", null, cv)
        bump()
        return get(id)!!
    }

    fun get(id: Long): Message? =
        db.rawQuery("SELECT * FROM messages WHERE id=?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.toMessage() else null }

    /** Visibility restriction: messages of allowed SIMs and the user's own outgoing messages. */
    data class Scope(val sims: Set<Int>?, val owner: String?, val canRead: Boolean)

    private fun scopeClause(scope: Scope?, where: MutableList<String>, args: MutableList<String>) {
        if (scope == null || scope.sims == null && scope.canRead) return
        val parts = mutableListOf<String>()
        if (scope.canRead) {
            val ids = scope.sims.orEmpty()
            if (ids.isNotEmpty()) parts += "sim_id IN (${ids.joinToString(",")})"
        }
        if (!scope.owner.isNullOrEmpty()) { parts += "(direction='out' AND owner=?)"; args += scope.owner }
        where += if (parts.isEmpty()) "0" else "(" + parts.joinToString(" OR ") + ")"
    }

    fun list(
        direction: String? = null, status: String? = null, query: String? = null, sinceId: Long = 0,
        unackedOnly: Boolean = false, limit: Int = 50, offset: Int = 0, ascending: Boolean = false,
        scope: Scope? = null,
    ): List<Message> {
        val where = mutableListOf<String>(); val args = mutableListOf<String>()
        scopeClause(scope, where, args)
        if (direction != null) { where += "direction=?"; args += direction }
        if (status != null) { where += "status=?"; args += status }
        if (!query.isNullOrBlank()) { where += "(phone LIKE ? OR text LIKE ?)"; args += "%$query%"; args += "%$query%" }
        if (sinceId > 0) { where += "id>?"; args += sinceId.toString() }
        if (unackedOnly) where += "acked=0"
        val w = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val order = if (ascending) "ASC" else "DESC"
        val sql = "SELECT * FROM messages $w ORDER BY id $order LIMIT ${limit.coerceIn(1, 500)} OFFSET ${offset.coerceAtLeast(0)}"
        return db.rawQuery(sql, args.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(c.toMessage()) } }
    }

    fun count(direction: String? = null, status: String? = null, query: String? = null, scope: Scope? = null): Int {
        val where = mutableListOf<String>(); val args = mutableListOf<String>()
        scopeClause(scope, where, args)
        if (direction != null) { where += "direction=?"; args += direction }
        if (status != null) { where += "status=?"; args += status }
        if (!query.isNullOrBlank()) { where += "(phone LIKE ? OR text LIKE ?)"; args += "%$query%"; args += "%$query%" }
        val w = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        return db.rawQuery("SELECT COUNT(*) FROM messages $w", args.toTypedArray()).use { c -> c.moveToFirst(); c.getInt(0) }
    }

    /** Next queued message ready to be sent. */
    fun nextQueued(): Message? = db.rawQuery(
        "SELECT * FROM messages WHERE direction='out' AND status='queued' AND next_at<=? ORDER BY id ASC LIMIT 1",
        arrayOf(now().toString()),
    ).use { c -> if (c.moveToFirst()) c.toMessage() else null }

    fun hasQueued(): Boolean = count("out", "queued") > 0

    fun markSending(id: Long, sim: Int, simId: Int, parts: Int) = updateRaw(id) {
        put("status", "sending"); put("sim", sim); put("sim_id", simId); put("parts", parts)
        put("parts_sent", 0); put("parts_delivered", 0); put("error", null as String?)
    }.also { db.execSQL("UPDATE messages SET attempts=attempts+1 WHERE id=?", arrayOf(id)) }

    fun markRetry(id: Long, error: String, delayMs: Long) = updateRaw(id) {
        put("status", "queued"); put("error", error); put("next_at", now() + delayMs)
    }

    fun markFailed(id: Long, error: String) = updateRaw(id) { put("status", "failed"); put("error", error) }

    fun markSent(id: Long) = updateRaw(id) { put("status", "sent"); put("sent_at", now()) }

    /** Increments the delivered-parts counter; returns true if all parts are delivered. */
    fun partDelivered(id: Long): Boolean {
        db.execSQL("UPDATE messages SET parts_delivered=parts_delivered+1 WHERE id=?", arrayOf(id))
        val done = db.rawQuery("SELECT parts_delivered>=parts FROM messages WHERE id=?", arrayOf(id.toString()))
            .use { c -> c.moveToFirst() && c.getInt(0) == 1 }
        if (done) updateRaw(id) { put("status", "delivered"); put("delivered_at", now()) }
        return done
    }

    fun retry(id: Long): Boolean {
        val m = get(id) ?: return false
        if (m.direction != "out" || m.status !in setOf("failed", "sent")) return false
        updateRaw(id) { put("status", "queued"); put("error", null as String?); put("next_at", 0); put("attempts", 0) }
        return true
    }

    fun ack(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        val n = db.compileStatement("UPDATE messages SET acked=1 WHERE direction='in' AND id IN (${ids.joinToString(",")})").executeUpdateDelete()
        bump(); return n
    }

    fun unsyncedIncoming(limit: Int = 100): List<Message> = db.rawQuery(
        "SELECT * FROM messages WHERE direction='in' AND remote_synced=0 ORDER BY id ASC LIMIT $limit", null,
    ).use { c -> buildList { while (c.moveToNext()) add(c.toMessage()) } }

    fun markRemoteSynced(id: Long) { db.execSQL("UPDATE messages SET remote_synced=1 WHERE id=?", arrayOf(id)) }

    fun delete(id: Long) { db.delete("messages", "id=?", arrayOf(id.toString())); bump() }

    fun clear(direction: String?) {
        if (direction == null) db.delete("messages", null, null) else db.delete("messages", "direction=?", arrayOf(direction))
        bump()
    }

    fun startOfDay(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Sent today, by simId. */
    fun sentTodayBySim(): Map<Int, Int> = db.rawQuery(
        "SELECT sim_id, COUNT(*) FROM messages WHERE direction='out' AND status IN ('sending','sent','delivered') AND updated_at>=? GROUP BY sim_id",
        arrayOf(startOfDay().toString()),
    ).use { c -> buildMap { while (c.moveToNext()) put(c.getInt(0), c.getInt(1)) } }

    fun sentLastMinute(): Int = db.rawQuery(
        "SELECT COUNT(*) FROM messages WHERE direction='out' AND sent_at>=?", arrayOf((now() - 60_000).toString()),
    ).use { c -> c.moveToFirst(); c.getInt(0) }

    fun stats(scope: Scope? = null): Stats {
        val sod = startOfDay().toString()
        val w = mutableListOf<String>(); val a = mutableListOf<String>()
        scopeClause(scope, w, a)
        val extra = if (w.isEmpty()) "" else " AND " + w.joinToString(" AND ")
        fun q(sql: String) = db.rawQuery(sql + extra, (listOf(sod) + a).toTypedArray()).use { c -> c.moveToFirst(); c.getInt(0) }
        fun cnt(sql: String) = db.rawQuery(sql + extra, a.toTypedArray()).use { c -> c.moveToFirst(); c.getInt(0) }
        return Stats(
            sentToday = q("SELECT COUNT(*) FROM messages WHERE direction='out' AND status IN ('sent','delivered') AND sent_at>=?"),
            receivedToday = q("SELECT COUNT(*) FROM messages WHERE direction='in' AND created_at>=?"),
            failedToday = q("SELECT COUNT(*) FROM messages WHERE direction='out' AND status='failed' AND updated_at>=?"),
            queued = cnt("SELECT COUNT(*) FROM messages WHERE direction='out' AND status IN ('queued','sending')"),
            totalOut = cnt("SELECT COUNT(*) FROM messages WHERE direction='out'"),
            totalIn = cnt("SELECT COUNT(*) FROM messages WHERE direction='in'"),
            perSimToday = sentTodayBySim().let { m -> if (scope?.sims == null) m else m.filterKeys { it in scope.sims } },
        )
    }

    /** Hourly activity over the last 24 hours: list of (hourOffset, out, in). */
    fun hourly(scope: Scope? = null): List<IntArray> {
        val from = now() - 24 * 3600_000L
        val res = Array(24) { IntArray(2) }
        val w = mutableListOf<String>(); val a = mutableListOf<String>()
        scopeClause(scope, w, a)
        val extra = if (w.isEmpty()) "" else " AND " + w.joinToString(" AND ")
        db.rawQuery("SELECT direction, created_at FROM messages WHERE created_at>=?$extra", (listOf(from.toString()) + a).toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val idx = (((c.getLong(1) - from) / 3600_000L).toInt()).coerceIn(0, 23)
                if (c.getString(0) == "out") res[idx][0]++ else res[idx][1]++
            }
        }
        return res.toList()
    }

    private inline fun updateRaw(id: Long, block: ContentValues.() -> Unit) {
        val cv = ContentValues().apply(block); cv.put("updated_at", now())
        db.update("messages", cv, "id=?", arrayOf(id.toString()))
        bump()
    }

    fun setSysUri(id: Long, uri: String?) { db.execSQL("UPDATE messages SET sys_uri=? WHERE id=?", arrayOf(uri, id)) }

    fun sysUri(id: Long): String? = db.rawQuery("SELECT sys_uri FROM messages WHERE id=?", arrayOf(id.toString()))
        .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }

    // ---------- webhook queue (survives restarts) ----------

    data class HookJob(val id: Long, val hookId: String, val event: String, val body: String, val attempts: Int)

    fun enqueueHook(hookId: String, event: String, body: String): Long = db.insert("webhook_queue", null, ContentValues().apply {
        put("hook_id", hookId); put("event", event); put("body", body); put("created_at", now()); put("next_at", 0)
    })

    fun dueHooks(limit: Int = 20): List<HookJob> = db.rawQuery(
        "SELECT id, hook_id, event, body, attempts FROM webhook_queue WHERE next_at<=? ORDER BY id LIMIT $limit", arrayOf(now().toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(HookJob(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4))) } }

    fun nextHookAt(): Long? = db.rawQuery("SELECT MIN(next_at) FROM webhook_queue", null)
        .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    fun hookDone(id: Long) { db.delete("webhook_queue", "id=?", arrayOf(id.toString())) }

    fun hookRetry(id: Long, attempts: Int, nextAt: Long, error: String) {
        db.execSQL("UPDATE webhook_queue SET attempts=?, next_at=?, error=? WHERE id=?", arrayOf(attempts, nextAt, error, id))
    }

    fun hookQueueSize(): Int = db.rawQuery("SELECT COUNT(*) FROM webhook_queue", null).use { c -> c.moveToFirst(); c.getInt(0) }

    // ---------- IP bans ----------

    fun saveBan(ip: String, until: Long, reason: String) {
        db.insertWithOnConflict("bans", null, ContentValues().apply { put("ip", ip); put("until", until); put("reason", reason) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteBan(ip: String) { db.delete("bans", "ip=?", arrayOf(ip)) }

    fun loadBans(): List<Triple<String, Long, String>> = db.rawQuery("SELECT ip, until, reason FROM bans WHERE until>?", arrayOf(now().toString()))
        .use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getLong(1), c.getString(2))) } }

    // ---------- USSD ----------

    @kotlinx.serialization.Serializable
    data class Ussd(val id: Long, val simId: Int, val slot: Int, val code: String, val status: String, val response: String?,
                    val error: String?, val source: String, val owner: String, val createdAt: Long, val finishedAt: Long)

    fun insertUssd(simId: Int, slot: Int, code: String, source: String, owner: String): Long {
        val id = db.insert("ussd", null, ContentValues().apply {
            put("sim_id", simId); put("slot", slot); put("code", code); put("status", "pending")
            put("source", source); put("owner", owner); put("created_at", now())
        })
        bump(); return id
    }

    fun finishUssd(id: Long, ok: Boolean, text: String) {
        db.update("ussd", ContentValues().apply {
            put("status", if (ok) "ok" else "error"); put(if (ok) "response" else "error", text); put("finished_at", now())
        }, "id=?", arrayOf(id.toString()))
        bump()
    }

    fun getUssd(id: Long): Ussd? = db.rawQuery("SELECT * FROM ussd WHERE id=?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.toUssd() else null }

    fun listUssd(sims: Set<Int>?, limit: Int = 50): List<Ussd> {
        val w = sims?.let { "WHERE sim_id IN (${it.joinToString(",").ifEmpty { "-99" }})" } ?: ""
        return db.rawQuery("SELECT * FROM ussd $w ORDER BY id DESC LIMIT ${limit.coerceIn(1, 500)}", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toUssd()) } }
    }

    private fun Cursor.toUssd() = Ussd(
        getLong(getColumnIndexOrThrow("id")), getInt(getColumnIndexOrThrow("sim_id")), getInt(getColumnIndexOrThrow("slot")),
        getString(getColumnIndexOrThrow("code")), getString(getColumnIndexOrThrow("status")),
        getString(getColumnIndexOrThrow("response")), getString(getColumnIndexOrThrow("error")),
        getString(getColumnIndexOrThrow("source")) ?: "", getString(getColumnIndexOrThrow("owner")) ?: "",
        getLong(getColumnIndexOrThrow("created_at")), getLong(getColumnIndexOrThrow("finished_at")),
    )

    // ---------- calls ----------

    @kotlinx.serialization.Serializable
    data class Call(val id: Long, val simId: Int, val slot: Int, val number: String, val direction: String, val status: String,
                    val source: String, val owner: String, val startedAt: Long, val answeredAt: Long, val endedAt: Long) {
        val durationSec get() = if (answeredAt > 0 && endedAt > answeredAt) (endedAt - answeredAt) / 1000 else 0
    }

    fun insertCall(simId: Int, slot: Int, number: String, direction: String, status: String, source: String = "", owner: String = ""): Long {
        val id = db.insert("calls", null, ContentValues().apply {
            put("sim_id", simId); put("slot", slot); put("number", number); put("direction", direction); put("status", status)
            put("source", source); put("owner", owner); put("started_at", now())
        })
        bump(); return id
    }

    fun updateCall(id: Long, status: String, answered: Boolean = false, ended: Boolean = false, number: String? = null) {
        db.update("calls", ContentValues().apply {
            put("status", status)
            if (answered) put("answered_at", now())
            if (ended) put("ended_at", now())
            if (!number.isNullOrEmpty()) put("number", number)
        }, "id=?", arrayOf(id.toString()))
        bump()
    }

    fun getCall(id: Long): Call? = db.rawQuery("SELECT * FROM calls WHERE id=?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.toCall() else null }

    fun listCalls(sims: Set<Int>?, limit: Int = 100): List<Call> {
        val w = sims?.let { "WHERE sim_id IN (${it.joinToString(",").ifEmpty { "-99" }})" } ?: ""
        return db.rawQuery("SELECT * FROM calls $w ORDER BY id DESC LIMIT ${limit.coerceIn(1, 500)}", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toCall()) } }
    }

    private fun Cursor.toCall() = Call(
        getLong(getColumnIndexOrThrow("id")), getInt(getColumnIndexOrThrow("sim_id")), getInt(getColumnIndexOrThrow("slot")),
        getString(getColumnIndexOrThrow("number")), getString(getColumnIndexOrThrow("direction")), getString(getColumnIndexOrThrow("status")),
        getString(getColumnIndexOrThrow("source")) ?: "", getString(getColumnIndexOrThrow("owner")) ?: "",
        getLong(getColumnIndexOrThrow("started_at")), getLong(getColumnIndexOrThrow("answered_at")), getLong(getColumnIndexOrThrow("ended_at")),
    )

    // ---------- auto-cleanup ----------

    /** Deletes data older than the retention periods. Returns the number of deleted messages. */
    fun cleanup(st: StorageConfig): Int {
        val day = 86_400_000L
        var removed = 0
        if (st.historyDays > 0) removed += db.delete("messages", "created_at<? AND status NOT IN ('queued','sending')", arrayOf((now() - st.historyDays * day).toString()))
        if (st.maxMessages > 0) {
            db.execSQL("DELETE FROM messages WHERE id <= (SELECT MAX(id) FROM messages) - ? AND status NOT IN ('queued','sending')", arrayOf(st.maxMessages))
        }
        if (st.logDays > 0) db.delete("logs", "ts<?", arrayOf((now() - st.logDays * day).toString()))
        if (st.ussdDays > 0) db.delete("ussd", "created_at<?", arrayOf((now() - st.ussdDays * day).toString()))
        if (st.callDays > 0) db.delete("calls", "started_at<?", arrayOf((now() - st.callDays * day).toString()))
        db.delete("bans", "until<?", arrayOf(now().toString()))
        db.delete("webhook_queue", "created_at<?", arrayOf((now() - 3 * day).toString()))
        bump()
        return removed
    }

    fun exportMessages(limit: Int = 50_000): List<Message> =
        db.rawQuery("SELECT * FROM messages ORDER BY id DESC LIMIT $limit", null).use { c -> buildList { while (c.moveToNext()) add(c.toMessage()) } }.reversed()

    /** Imports history from a backup (new ids, deduplicated by time + number + text). */
    fun importMessages(list: List<Message>): Int {
        var n = 0
        db.beginTransaction()
        try {
            for (m in list) {
                val exists = db.rawQuery("SELECT 1 FROM messages WHERE created_at=? AND phone=? AND text=? LIMIT 1",
                    arrayOf(m.createdAt.toString(), m.phone, m.text)).use { it.moveToFirst() }
                if (exists) continue
                db.insert("messages", null, ContentValues().apply {
                    put("direction", m.direction); put("phone", m.phone); put("text", m.text); put("sim", m.sim); put("sim_id", m.simId)
                    put("status", if (m.status == "queued" || m.status == "sending") "failed" else m.status)
                    put("error", m.error); put("parts", m.parts); put("source", m.source); put("ref", m.ref); put("owner", m.owner)
                    put("attempts", m.attempts); put("created_at", m.createdAt); put("updated_at", m.updatedAt)
                    put("sent_at", m.sentAt); put("delivered_at", m.deliveredAt); put("acked", if (m.acked) 1 else 0); put("remote_synced", 1)
                })
                n++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        bump(); return n
    }

    /** Database file size, in bytes. */
    fun sizeBytes(): Long = runCatching { java.io.File(helper.readableDatabase.path).length() }.getOrDefault(0)

    /** Allowed SIMs stored with the message (null = unrestricted). */
    fun allowedSims(id: Long): Set<Int>? = db.rawQuery("SELECT allowed FROM messages WHERE id=?", arrayOf(id.toString())).use { c ->
        if (!c.moveToFirst() || c.isNull(0)) null else c.getString(0).split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    private fun Cursor.toMessage() = Message(
        id = getLong(getColumnIndexOrThrow("id")),
        direction = getString(getColumnIndexOrThrow("direction")),
        phone = getString(getColumnIndexOrThrow("phone")),
        text = getString(getColumnIndexOrThrow("text")),
        sim = getInt(getColumnIndexOrThrow("sim")),
        status = getString(getColumnIndexOrThrow("status")),
        error = getString(getColumnIndexOrThrow("error")),
        parts = getInt(getColumnIndexOrThrow("parts")),
        source = getString(getColumnIndexOrThrow("source")) ?: "",
        ref = getString(getColumnIndexOrThrow("ref")),
        attempts = getInt(getColumnIndexOrThrow("attempts")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        sentAt = getLong(getColumnIndexOrThrow("sent_at")),
        deliveredAt = getLong(getColumnIndexOrThrow("delivered_at")),
        acked = getInt(getColumnIndexOrThrow("acked")) == 1,
        simId = getInt(getColumnIndexOrThrow("sim_id")),
        owner = getString(getColumnIndexOrThrow("owner")) ?: "",
    )

    // ---------- logs ----------

    fun log(level: String, category: String, message: String) {
        runCatching {
            db.insert("logs", null, ContentValues().apply {
                put("ts", now()); put("level", level); put("category", category); put("message", message.take(1000))
            })
            if (kotlin.random.Random.nextInt(50) == 0) db.execSQL("DELETE FROM logs WHERE id <= (SELECT MAX(id) FROM logs) - 5000")
            bump()
        }
    }

    fun logs(category: String?, limit: Int = 200): List<LogEntry> {
        val (w, a) = if (category.isNullOrBlank()) "" to emptyArray<String>() else "WHERE category=?" to arrayOf(category)
        return db.rawQuery("SELECT * FROM logs $w ORDER BY id DESC LIMIT ${limit.coerceIn(1, 1000)}", a).use { c ->
            buildList { while (c.moveToNext()) add(LogEntry(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4))) }
        }
    }

    fun clearLogs() { db.delete("logs", null, null); bump() }
}

object Log {
    fun i(cat: String, msg: String) = Db.log("info", cat, msg)
    fun w(cat: String, msg: String) = Db.log("warn", cat, msg)
    fun e(cat: String, msg: String) = Db.log("error", cat, msg)
    fun sec(msg: String) = Db.log("warn", "security", msg)
}
