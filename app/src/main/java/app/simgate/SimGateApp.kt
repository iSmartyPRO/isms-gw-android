package app.simgate

import android.app.Application
import app.simgate.data.t
import android.app.NotificationChannel
import android.app.NotificationManager
import app.simgate.data.ConfigStore
import app.simgate.data.Db
import app.simgate.net.RemoteClient
import app.simgate.service.Gateway
import app.simgate.sms.SimManager
import app.simgate.sms.SmsSender
import app.simgate.sms.SmsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SimGateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ConfigStore.init(this)
        Db.init(this)
        SimManager.init(this)
        SmsStore.init(this)
        SmsSender.init(this)
        app.simgate.sms.SimMemory.init(this)
        app.simgate.sms.UssdService.init(this)
        app.simgate.sms.CallService.init(this)
        Gateway.init(this)
        createChannels()
        // The send queue always runs: for both the messenger and the gateway.
        Gateway.ensureQueue()
        // Bans and the webhook queue survive restarts.
        app.simgate.server.Firewall.restore()
        app.simgate.net.WebhookDispatcher.ensureWorker()
        // Retention-based auto-cleanup: on startup and every 6 hours.
        scope.launch {
            while (true) {
                runCatching { Db.cleanup(ConfigStore.value.storage) }
                kotlinx.coroutines.delay(6 * 3600_000L)
            }
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, t("Работа шлюза", "Gateway service"), NotificationManager.IMPORTANCE_LOW).apply {
            description = t("Постоянное уведомление о работе SMS-шлюза", "Persistent notification while the SMS gateway is running"); setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, t("Входящие SMS", "Incoming SMS"), NotificationManager.IMPORTANCE_HIGH).apply {
            description = t("Уведомления о новых сообщениях", "Notifications about new messages")
        })
    }

    companion object {
        const val CH_SERVICE = "gateway"
        const val CH_MESSAGES = "messages"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
