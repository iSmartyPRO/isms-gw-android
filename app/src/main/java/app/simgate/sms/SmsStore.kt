package app.simgate.sms

import android.Manifest
import app.simgate.data.t
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.simgate.R
import app.simgate.SimGateApp
import app.simgate.data.Log
import app.simgate.ui.MainActivity

data class SmsRow(val id: Long, val threadId: Long, val address: String, val body: String, val date: Long, val type: Int, val read: Boolean, val subId: Int)

data class Conversation(val threadId: Long, val address: String, val snippet: String, val date: Long, val unread: Int, val outgoing: Boolean)

/**
 * Access to the system SMS storage (Telephony provider).
 * As the default SMS app, iSMS GW must write incoming and sent
 * messages itself, so that other apps and backups can see them.
 * Per Google Play policy, SMS access ends when the default role is lost.
 */
object SmsStore {
    private lateinit var ctx: Context

    fun init(context: Context) { ctx = context.applicationContext }

    fun isDefaultApp(): Boolean = Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName

    private fun canRead() = isDefaultApp() &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun writeIncoming(address: String, body: String, date: Long, subId: Int): String? {
        if (!isDefaultApp()) return null
        return runCatching {
            ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.DATE_SENT, date)
                put(Telephony.Sms.READ, 0); put(Telephony.Sms.SEEN, 0)
                if (subId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
            })?.toString()
        }.onFailure { Log.w("sms", t("Не удалось записать во входящие: ${it.message}", "Failed to write to inbox: ${it.message}")) }.getOrNull()
    }

    /** An outgoing message appears in the conversation immediately with the "sending" status (outbox). */
    fun insertOutbox(address: String, body: String, subId: Int): String? {
        if (!isDefaultApp()) return null
        return runCatching {
            ctx.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
                if (subId > 0) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
            })?.toString()
        }.getOrNull()
    }

    /** Update the record type in the system storage (sent / failed). */
    fun updateOutgoing(uri: String, type: Int, subId: Int) {
        if (!isDefaultApp()) return
        runCatching {
            ctx.contentResolver.update(android.net.Uri.parse(uri), ContentValues().apply {
                put(Telephony.Sms.TYPE, type)
                if (type == Telephony.Sms.MESSAGE_TYPE_SENT) put(Telephony.Sms.DATE_SENT, System.currentTimeMillis())
                if (subId > 0) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
            }, null, null)
        }
    }

    /** Delete a message from the phone's conversations. */
    fun deleteSystem(uri: String): Boolean {
        if (!isDefaultApp()) return false
        return runCatching { ctx.contentResolver.delete(android.net.Uri.parse(uri), null, null) > 0 }.getOrDefault(false)
    }

    fun writeOutgoing(address: String, body: String, subId: Int, failed: Boolean) {
        if (!isDefaultApp()) return
        runCatching {
            val uri = if (failed) Telephony.Sms.CONTENT_URI else Telephony.Sms.Sent.CONTENT_URI
            ctx.contentResolver.insert(uri, ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.TYPE, if (failed) Telephony.Sms.MESSAGE_TYPE_FAILED else Telephony.Sms.MESSAGE_TYPE_SENT)
                if (subId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
            })
        }
    }

    fun conversations(limitRows: Int = 3000): List<Conversation> {
        if (!canRead()) return emptyList()
        val map = LinkedHashMap<Long, Conversation>()
        runCatching {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
                null, null, "${Telephony.Sms.DATE} DESC LIMIT $limitRows",
            )?.use { c ->
                while (c.moveToNext()) {
                    val t = c.getLong(0)
                    val unread = if (c.getInt(5) == 0 && c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX) 1 else 0
                    val ex = map[t]
                    if (ex == null) {
                        map[t] = Conversation(t, c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3), unread, c.getInt(4) != Telephony.Sms.MESSAGE_TYPE_INBOX)
                    } else if (unread > 0) map[t] = ex.copy(unread = ex.unread + 1)
                }
            }
        }
        return map.values.toList()
    }

    fun thread(address: String, limit: Int = 500): List<SmsRow> {
        if (!canRead()) return emptyList()
        val (sel, args) = threadSelection(address)
        val res = ArrayList<SmsRow>()
        runCatching {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.SUBSCRIPTION_ID),
                sel, args,
                "${Telephony.Sms.DATE} DESC LIMIT $limit",
            )?.use { c ->
                while (c.moveToNext()) res += SmsRow(
                    c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getString(3) ?: "", c.getLong(4),
                    c.getInt(5), c.getInt(6) == 1, c.getInt(7),
                )
            }
        }
        return res.reversed()
    }

    fun markRead(address: String) {
        if (!canRead()) return
        val (sel, args) = threadSelection(address)
        runCatching {
            ctx.contentResolver.update(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
                put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1)
            }, "$sel AND ${Telephony.Sms.READ}=0", args)
        }
        ctx.getSystemService(NotificationManager::class.java).cancel(address.hashCode())
    }

    /**
     * Fetches the messages of a conversation. The primary method is the system thread_id (works for alphanumeric
     * senders such as "t2.ru", "Bank" too); the fallback is address comparison.
     */
    private fun threadSelection(address: String): Pair<String, Array<String>> {
        val threadId = runCatching { Telephony.Threads.getOrCreateThreadId(ctx, address) }.getOrNull()
        if (threadId != null && threadId > 0) return "${Telephony.Sms.THREAD_ID}=?" to arrayOf(threadId.toString())
        val digits = address.filter { it.isDigit() }
        return if (isNumeric(address) && digits.length >= 5) "${Telephony.Sms.ADDRESS} LIKE ?" to arrayOf("%" + digits.takeLast(10))
        else "${Telephony.Sms.ADDRESS}=? COLLATE NOCASE" to arrayOf(address)
    }

    /** A phone number (can be replied to) or an alphanumeric sender (cannot be replied to). */
    fun isNumeric(address: String) = Regex("^\\+?[0-9\\s()\\-]{3,}$").matches(address.trim())

    /** Same contact? Numbers are compared by the last 10 digits, alphanumeric senders case-insensitively. */
    fun sameAddress(a: String, b: String): Boolean =
        if (isNumeric(a) && isNumeric(b)) a.filter { it.isDigit() }.takeLast(10) == b.filter { it.isDigit() }.takeLast(10)
        else a.trim().equals(b.trim(), ignoreCase = true)

    fun notifyIncoming(from: String, text: String) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33) return
        val open = PendingIntent.getActivity(
            ctx, from.hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_PHONE, from).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, SimGateApp.CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle(from).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(open).build()
        ctx.getSystemService(NotificationManager::class.java).notify(from.hashCode(), n)
    }
}

/** "Quick reply" from an incoming call: a required component of an SMS app. */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val to = intent?.data?.schemeSpecificPart?.substringBefore('?')
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!to.isNullOrBlank() && !text.isNullOrBlank()) {
            to.split(',', ';').forEach { runCatching { SmsSender.enqueue(it, text, 0, "app", null) } }
            app.simgate.service.Gateway.ensureQueue()
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/** MMS is not supported by the gateway; the receiver is required for the SMS app role. */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("sms", t("Получено MMS-уведомление (MMS не обрабатываются)", "MMS notification received (MMS is not supported)"))
    }
}
