package app.simgate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.simgate.data.ConfigStore
import app.simgate.data.RemoteConfig
import app.simgate.data.t
import app.simgate.net.ConnectLink
import app.simgate.net.ConnectPayload
import app.simgate.net.RemoteClient
import app.simgate.service.Gateway

/** Server host (with port) shown prominently so the user can recognise where the phone will connect. */
fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').ifBlank { url }

/** Masks the secret part of a token: smsgw_AbCd1234_•••• (never shows the full token after a scan). */
fun maskToken(token: String): String {
    if (ConnectLink.TOKEN_REGEX.matches(token)) {
        val mid = token.split('_').getOrNull(1).orEmpty()
        return "smsgw_${mid}_••••"
    }
    return (if (token.length <= 6) token else token.take(6)) + "••••"
}

/**
 * Confirmation screen for a scanned / pasted / deep-linked connection. Safety-critical: a QR could
 * point the phone at someone else's server, so nothing connects without this explicit confirmation.
 */
@Composable
fun ConnectConfirmDialog(payload: ConnectPayload, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cfg by ConfigStore.flow.collectAsState()
    val state by RemoteClient.state.collectAsState()
    val lastError by RemoteClient.lastError.collectAsState()
    val running by Gateway.serviceRunning.collectAsState()

    var connecting by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(payload.name != null) }
    val replaces = cfg.remote.url.isNotBlank() && cfg.remote.url != payload.url

    fun connect() {
        connecting = true
        ConfigStore.update {
            it.copy(
                remote = RemoteConfig(enabled = true, url = payload.url, token = payload.token),
                deviceName = if (rename && payload.name != null) payload.name else it.deviceName,
            )
        }
        if (running) Gateway.restartRemote() // if the gateway is off, the user starts it from the button below
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text(if (connecting) t("Подключение к SMS-серверу", "Connecting to the SMS server") else t("Подключить этот телефон?", "Connect this phone?")) },
        text = {
            Column {
                if (!connecting) {
                    Text(t("Сервер", "Server"), color = C.Muted, fontSize = 12.sp)
                    Text(hostOf(payload.url), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = C.Text)
                    Spacer(Modifier.height(10.dp))
                    Text(t("Токен", "Token"), color = C.Muted, fontSize = 12.sp)
                    Text(maskToken(payload.token), fontFamily = FontFamily.Monospace, color = C.Text)

                    payload.warnings().forEach { w -> Callout(w, C.Amber) }
                    if (replaces) Callout(t("Текущее подключение к «${hostOf(cfg.remote.url)}» будет заменено.", "The current connection to \"${hostOf(cfg.remote.url)}\" will be replaced."), C.Amber)

                    if (payload.name != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(rename, { rename = it }, colors = CheckboxDefaults.colors(checkedColor = C.Emerald))
                            Text(t("Назвать устройство «${payload.name}»", "Name this device \"${payload.name}\""), fontSize = 13.sp, color = C.Text)
                        }
                    }
                } else {
                    val status = connectionStatus(state, lastError)
                    Text(hostOf(payload.url), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = C.Text)
                    Spacer(Modifier.height(10.dp))
                    Text(status.first, color = status.second, fontWeight = FontWeight.Medium)
                    if (!running) {
                        Callout(t("Шлюз не запущен — подключение начнётся после запуска.", "The gateway is not running — the connection will start once you turn it on."), C.Amber)
                        Spacer(Modifier.height(4.dp))
                        Btn(t("Запустить шлюз", "Start the gateway"), { Gateway.startService(ctx) })
                    }
                }
            }
        },
        confirmButton = {
            if (!connecting) TextButton(onClick = { connect() }) { Text(t("Подключить", "Connect"), color = C.Emerald) }
            else TextButton(onClick = onDismiss) { Text(t("Готово", "Done"), color = C.Emerald) }
        },
        dismissButton = {
            if (!connecting) TextButton(onClick = onDismiss) { Text(t("Отмена", "Cancel"), color = C.Muted) }
        },
    )
}

/** Live connection status, with the two server rejections spelled out for the user. */
private fun connectionStatus(state: RemoteClient.State, lastError: String): Pair<String, androidx.compose.ui.graphics.Color> = when (state) {
    RemoteClient.State.CONNECTED -> t("Подключено", "Connected") to C.Emerald
    RemoteClient.State.CONNECTING -> t("Подключение…", "Connecting…") to C.Amber
    else -> when {
        lastError.contains("401") -> t("Неверный токен", "Invalid token") to C.Red
        lastError.contains("403") -> t("Телефон отключён на сервере", "The phone is disabled on the server") to C.Red
        lastError.isNotBlank() -> lastError to C.Red
        else -> t("Нет связи, переподключение…", "No connection, reconnecting…") to C.Amber
    }
}

@Composable
private fun Callout(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(10.dp),
    ) { Text(text, color = color, fontSize = 12.sp) }
}
