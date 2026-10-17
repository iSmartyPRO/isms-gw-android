package app.simgate.service

import android.app.Notification
import app.simgate.data.t
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import app.simgate.R
import app.simgate.SimGateApp
import app.simgate.data.ConfigStore
import app.simgate.net.RemoteClient
import app.simgate.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class GatewayService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground(build(t("Запуск…", "Starting…")))
        Gateway.onServiceStarted()
        watcher = SimGateApp.scope.launch {
            combine(Gateway.server, RemoteClient.state) { s, r -> s to r }.collect { (s, r) ->
                val parts = mutableListOf<String>()
                if (s.running) parts += s.urls.firstOrNull() ?: t("сервер активен", "server active")
                else if (s.error.isNotEmpty()) parts += t("ошибка сервера", "server error")
                when (r) {
                    RemoteClient.State.CONNECTED -> parts += t("SMS-сервер: онлайн", "SMS server: online")
                    RemoteClient.State.CONNECTING, RemoteClient.State.DISCONNECTED -> parts += t("SMS-сервер: переподключение", "SMS server: reconnecting")
                    else -> {}
                }
                // Android 13+: updating the notification requires POST_NOTIFICATIONS.
                val canNotify = android.os.Build.VERSION.SDK_INT < 33 ||
                    androidx.core.content.ContextCompat.checkSelfPermission(this@GatewayService, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (canNotify) runCatching { NotificationManagerCompat.from(this@GatewayService).notify(NOTIF_ID, build(parts.joinToString(" • ").ifEmpty { t("Работает", "Running") })) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        Gateway.onServiceStopped()
        super.onDestroy()
    }

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, GatewayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, SimGateApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(t("iSMS GW — шлюз активен", "iSMS GW — gateway active"))
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, t("Остановить", "Stop"), stop)
            .build()
    }

    companion object {
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "app.simgate.STOP"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (ConfigStore.value.autostart && ConfigStore.value.wasRunning) {
            runCatching { Gateway.startService(context) }
        }
    }
}
