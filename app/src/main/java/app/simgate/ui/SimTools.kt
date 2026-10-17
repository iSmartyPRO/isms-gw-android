package app.simgate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.simgate.data.ConfigStore
import app.simgate.data.User
import app.simgate.data.t
import app.simgate.sms.IccMessage
import app.simgate.sms.SimInfo
import app.simgate.sms.SimMemory
import app.simgate.sms.UssdService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Action buttons on the SIM card. */
@Composable
fun SimActions(sim: SimInfo) {
    var ussd by remember { mutableStateOf<String?>(null) }   // code to run / "" = manual input
    var memory by remember { mutableStateOf(false) }
    val balanceCode = ConfigStore.value.sims.firstOrNull { it.simId == sim.simId }?.balanceCode.orEmpty()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionChip(Icons.Rounded.AccountBalanceWallet, t("Баланс", "Balance"), C.Emerald, Modifier.weight(1f)) { ussd = balanceCode.ifEmpty { "" } }
        ActionChip(Icons.Rounded.Dialpad, "USSD", C.Indigo, Modifier.weight(1f)) { ussd = "" }
        ActionChip(Icons.Rounded.SdCard, t("Память", "Memory"), C.Amber, Modifier.weight(1f)) { memory = true }
    }
    ussd?.let { code -> UssdDialog(sim, code, askSave = code.isEmpty() && balanceCode.isEmpty()) { ussd = null } }
    if (memory) SimMemoryDialog(sim) { memory = false }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f)).clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** USSD request: code input (or immediate run of the saved balance code) and the carrier's response. */
@Composable
fun UssdDialog(sim: SimInfo, initial: String, askSave: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf(initial) }
    var saveAsBalance by remember { mutableStateOf(askSave) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    fun run() {
        if (!UssdService.validCode(code)) { result = false to t("Некорректный код. Пример: *100#", "Invalid code. Example: *100#"); return }
        busy = true; result = null
        if (saveAsBalance) ConfigStore.update { c -> c.copy(sims = c.sims.map { if (it.simId == sim.simId) it.copy(balanceCode = code.trim()) else it }) }
        scope.launch {
            result = try {
                val r = withContext(Dispatchers.IO) { UssdService.run(code, sim, "app", User.ADMIN_ID) }
                (r.status == "ok") to (r.response ?: r.error).orEmpty()
            } catch (e: Exception) { false to (e.message ?: "USSD") }
            busy = false
        }
    }
    LaunchedEffect(Unit) { if (initial.isNotEmpty()) run() }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.Surface,
        title = { Text("USSD • ${sim.label}") },
        text = {
            Column {
                OutlinedTextField(code, { code = it }, singleLine = true, label = { Text(t("Код", "Code")) }, placeholder = { Text("*100#") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), colors = fieldColors(), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                if (askSave) ToggleRow(Icons.Rounded.AccountBalanceWallet, t("Сохранить как код баланса", "Save as balance code"), null, saveAsBalance, C.Emerald) { saveAsBalance = it }
                Spacer(Modifier.height(10.dp))
                when {
                    busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = C.Emerald, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp)); Text(t("Ждём ответ оператора…", "Waiting for the carrier…"), color = C.Muted)
                    }
                    result != null -> Text(
                        result!!.second, fontSize = 14.sp, lineHeight = 20.sp, color = if (result!!.first) C.Text else C.Red,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Surface2).padding(12.dp),
                    )
                    else -> Text(t("Поддерживаются одношаговые запросы: баланс, остатки пакетов, номер SIM.", "Single-step requests are supported: balance, package remainders, SIM number."), color = C.Muted, fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = ::run) { Text(t("Выполнить", "Run"), color = C.Emerald, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Закрыть", "Close"), color = C.Muted) } },
    )
}

/** SIM card memory: SMS list, move to phone, delete. */
@Composable
fun SimMemoryDialog(sim: SimInfo, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<IccMessage>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload) {
        withContext(Dispatchers.IO) {
            try { items = SimMemory.list(sim); error = null } catch (e: Exception) { error = e.message; items = emptyList() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.Surface,
        title = { Text(t("Память SIM • ", "SIM memory • ") + sim.label) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                when {
                    items == null -> CircularProgressIndicator(color = C.Emerald)
                    error != null -> Text(error!!, color = C.Amber, fontSize = 13.sp)
                    items!!.isEmpty() -> Text(t("На SIM-карте нет сохранённых SMS.", "There are no SMS stored on the SIM card."), color = C.Muted)
                    else -> items!!.forEach { m ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(C.Surface2).padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(m.address.ifEmpty { "—" }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("#${m.index} · " + (if (m.date > 0) fmtTime(m.date) else "") + " · " + m.status, color = C.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                                IconButton(onClick = { scope.launch { withContext(Dispatchers.IO) { SimMemory.copyToPhone(sim, m.index, move = false) }; reload++ } }) {
                                    Icon(Icons.Rounded.Download, t("Скопировать в телефон", "Copy to phone"), tint = C.Emerald)
                                }
                                IconButton(onClick = { scope.launch { withContext(Dispatchers.IO) { SimMemory.delete(sim, m.index) }; reload++ } }) {
                                    Icon(Icons.Rounded.DeleteOutline, t("Удалить с SIM", "Delete from SIM"), tint = C.Red)
                                }
                            }
                            Text(m.body, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Закрыть", "Close"), color = C.Muted) } },
    )
}
