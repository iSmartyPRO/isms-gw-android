package app.simgate.service

import android.annotation.SuppressLint
import app.simgate.data.t
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import app.simgate.SimGateApp
import app.simgate.data.ConfigStore
import app.simgate.data.Log
import app.simgate.net.RemoteClient
import app.simgate.net.TelegramBot
import app.simgate.server.GatewayServer
import app.simgate.server.Tls
import app.simgate.sms.SmsSender
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/** Manages gateway components: HTTP server, SMS server connection, wake locks. */
object Gateway {
    data class ServerState(val running: Boolean = false, val error: String = "", val urls: List<String> = emptyList())

    val serviceRunning = MutableStateFlow(false)
    val server = MutableStateFlow(ServerState())
    val startedAt = MutableStateFlow(0L)

    private lateinit var ctx: Context
    private var http: GatewayServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun init(context: Context) { ctx = context.applicationContext }

    fun ensureQueue() = SmsSender.start(SimGateApp.scope)

    /** Start/stop the foreground service from the UI. */
    fun startService(context: Context) {
        if (!ConfigStore.value.wasRunning) ConfigStore.update { it.copy(wasRunning = true) }
        ContextCompat.startForegroundService(context, Intent(context, GatewayService::class.java))
    }

    fun stopService(context: Context) {
        ConfigStore.update { it.copy(wasRunning = false) }
        context.stopService(Intent(context, GatewayService::class.java))
    }

    internal fun onServiceStarted() {
        serviceRunning.value = true
        startedAt.value = System.currentTimeMillis()
        ensureQueue()
        startServer()
        RemoteClient.start()
        TelegramBot.start()
        app.simgate.sms.CallService.start()
        applyLocks()
        Log.i("service", t("Шлюз запущен", "Gateway started"))
    }

    internal fun onServiceStopped() {
        stopServer()
        RemoteClient.stop()
        TelegramBot.stop()
        app.simgate.sms.CallService.stop()
        releaseLocks()
        serviceRunning.value = false
        Log.i("service", t("Шлюз остановлен", "Gateway stopped"))
    }

    @Synchronized
    fun startServer() {
        stopServer()
        val cfg = ConfigStore.value.server
        if (!cfg.enabled) { server.value = ServerState(); return }
        try {
            val s = GatewayServer(ctx, cfg.port)
            if (cfg.https) s.makeSecure(Tls.serverSocketFactory(ctx), null)
            s.start(15_000, false)
            http = s
            server.value = ServerState(true, "", urls())
            Log.i("server", (if (cfg.https) "HTTPS" else "HTTP") + t("-сервер слушает порт ${cfg.port}", " server listening on port ${cfg.port}"))
        } catch (e: Exception) {
            server.value = ServerState(false, e.message ?: e.javaClass.simpleName)
            Log.e("server", t("Не удалось запустить сервер: ${e.message}", "Failed to start server: ${e.message}"))
        }
    }

    @Synchronized
    fun stopServer() {
        http?.stop(); http = null
        server.value = ServerState()
    }

    /** Restarts the server after a short delay so the HTTP response can be sent. */
    fun restartServerLater() {
        if (!serviceRunning.value) return
        SimGateApp.scope.launch { delay(700); startServer() }
    }

    fun restartRemote() { if (serviceRunning.value) RemoteClient.start() }

    fun refreshUrls() { if (server.value.running) server.value = server.value.copy(urls = urls()) }

    fun urls(): List<String> {
        val cfg = ConfigStore.value.server
        val scheme = if (cfg.https) "https" else "http"
        return localIps().map { "$scheme://$it:${cfg.port}" }
    }

    fun localIps(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("ap") || it.name.startsWith("swlan")) 1 else 2 }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.map { it.hostAddress ?: "" } }
            .filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    @SuppressLint("WakelockTimeout")
    fun applyLocks() {
        releaseLocks()
        if (!ConfigStore.value.keepAwake || !serviceRunning.value) return
        val pm = ctx.getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iSMSGW:gateway").apply { setReferenceCounted(false); acquire() }
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "iSMSGW:wifi").apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }; wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }; wifiLock = null
    }

    fun battery(): Pair<Int, Boolean> {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = if (Build.VERSION.SDK_INT >= 23) bm.isCharging else false
        return level to charging
    }

    fun isIgnoringBatteryOptimizations(): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
}
