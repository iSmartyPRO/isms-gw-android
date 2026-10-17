package app.simgate.ui

import app.simgate.data.t
import android.provider.Telephony
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.simgate.data.Db
import app.simgate.sms.Conversation
import app.simgate.sms.SendException
import app.simgate.sms.SimManager
import app.simgate.sms.SmsRow
import app.simgate.sms.SmsSender
import app.simgate.sms.SmsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun fmtTime(ts: Long): String {
    val now = Calendar.getInstance(); val c = Calendar.getInstance().apply { timeInMillis = ts }
    val pattern = when {
        now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR) -> "HH:mm"
        now.get(Calendar.YEAR) == c.get(Calendar.YEAR) -> "d MMM"
        else -> "dd.MM.yy"
    }
    return SimpleDateFormat(pattern, if (app.simgate.data.I18n.isEn()) Locale.US else Locale("ru")).format(Date(ts))
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.Emerald, unfocusedBorderColor = C.Stroke, focusedContainerColor = C.Surface2,
    unfocusedContainerColor = C.Surface2, cursorColor = C.Emerald, focusedLabelColor = C.Emerald,
)

@Composable
fun ConversationsScreen(onOpen: (String) -> Unit) {
    val rev by Db.revision.collectAsState()
    var query by remember { mutableStateOf("") }
    var newDialog by remember { mutableStateOf(false) }
    val list by produceState<List<Conversation>?>(null, rev) { value = withContext(Dispatchers.IO) { SmsStore.conversations() } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(20.dp))
                Text("Сообщения", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
                    placeholder = { Text("Поиск по номеру или тексту") }, leadingIcon = { Icon(Icons.Rounded.Search, null, tint = C.Muted) },
                    colors = fieldColors(),
                )
                Spacer(Modifier.height(8.dp))
            }
            val items = list.orEmpty().filter { query.isBlank() || it.address.contains(query, true) || it.snippet.contains(query, true) }
            if (list != null && items.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    IconBadge(Icons.Rounded.Forum, C.Indigo, 64)
                    Spacer(Modifier.height(12.dp))
                    Text(if (query.isBlank()) t("Пока нет сообщений", "No messages yet") else t("Ничего не найдено", "Nothing found"), color = C.Muted)
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
                items(items, key = { it.threadId }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(c.address) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(c.address)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.address, fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(fmtTime(c.date), color = if (c.unread > 0) C.Emerald else C.Muted, fontSize = 12.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text((if (c.outgoing) t("Вы: ", "You: ") else "") + c.snippet, color = if (c.unread > 0) C.Text else C.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (c.unread > 0) {
                                    Box(Modifier.padding(start = 8.dp).size(20.dp).clip(CircleShape).background(C.Emerald), contentAlignment = Alignment.Center) {
                                        Text("${c.unread}", color = C.OnAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { newDialog = true }, containerColor = C.Emerald, contentColor = C.OnAccent,
            icon = { Icon(Icons.Rounded.Edit, null) }, text = { Text("Новое", fontWeight = FontWeight.SemiBold) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp), shape = RoundedCornerShape(18.dp),
        )
    }

    if (newDialog) {
        var phone by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newDialog = false }, containerColor = C.Surface,
            title = { Text("Новое сообщение") },
            text = {
                OutlinedTextField(phone, { phone = it }, singleLine = true, label = { Text("Номер получателя") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), colors = fieldColors(), shape = RoundedCornerShape(14.dp))
            },
            confirmButton = {
                TextButton(onClick = { SmsSender.validatePhone(phone)?.let { newDialog = false; onOpen(it) } }) { Text("Открыть", color = C.Emerald) }
            },
            dismissButton = { TextButton(onClick = { newDialog = false }) { Text("Отмена", color = C.Muted) } },
        )
    }
}

@Composable
fun ChatScreen(phone: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val rev by Db.revision.collectAsState()
    val sims = remember { SimManager.active().filter { it.sendEnabled } }
    var sim by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    val rows by produceState<List<SmsRow>>(emptyList(), rev) {
        value = withContext(Dispatchers.IO) { SmsStore.markRead(phone); SmsStore.thread(phone) }
    }
    val pending by produceState<List<app.simgate.data.Message>>(emptyList(), rev) {
        value = withContext(Dispatchers.IO) {
            // New messages are visible via the system outbox; here only old records without a link.
            Db.list(direction = "out", limit = 100).filter { it.status in setOf("queued", "sending") && SmsStore.sameAddress(it.phone, phone) && Db.sysUri(it.id) == null }.reversed()
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(rows.size, pending.size) { if (rows.size + pending.size > 0) listState.scrollToItem(rows.size + pending.size - 1) }

    Column(Modifier.fillMaxSize().background(C.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().background(C.Surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, t("Назад", "Back")) }
            Avatar(phone, 38)
            Spacer(Modifier.width(12.dp))
            Text(phone, style = MaterialTheme.typography.titleMedium)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState) {
            items(rows, key = { "p${it.id}" }) { r -> Bubble(r.body, r.type != Telephony.Sms.MESSAGE_TYPE_INBOX, fmtTime(r.date) + when (r.type) {
                Telephony.Sms.MESSAGE_TYPE_FAILED -> t(" • ошибка", " • failed")
                Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> t(" • отправка…", " • sending…")
                else -> ""
            }, r.type == Telephony.Sms.MESSAGE_TYPE_FAILED) }
            items(pending, key = { "q${it.id}" }) { m -> Bubble(m.text, true, if (m.status == "queued") t("в очереди…", "queued…") else t("отправка…", "sending…"), false, pending = true) }
        }
        val canReply = SmsStore.isNumeric(phone)
        if (!canReply) {
            Row(Modifier.fillMaxWidth().background(C.Surface).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, null, tint = C.Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(t("Это служебный отправитель — ответить на сообщение нельзя.", "This is a service sender — replies are not possible."), color = C.Muted, fontSize = 13.sp)
            }
            return@Column
        }
        if (sims.size > 1) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(0 to t("Авто", "Auto")) + sims.map { it.slot to it.label }).forEach { (slot, label) ->
                    val sel = sim == slot
                    Text(label, fontSize = 12.sp, color = if (sel) C.OnAccent else C.Muted, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) C.Emerald else C.Surface2).clickable { sim = slot }.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text(t("Сообщение", "Message")) }, shape = RoundedCornerShape(22.dp), colors = fieldColors(), maxLines = 5)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(if (text.isNotBlank()) C.Accent else androidx.compose.ui.graphics.SolidColor(C.Surface2))
                    .clickable(enabled = text.isNotBlank()) {
                        try { SmsSender.enqueue(phone, text.trim(), sim, "app", null); text = "" }
                        catch (e: SendException) { Toast.makeText(ctx, e.message, Toast.LENGTH_LONG).show() }
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Rounded.Send, t("Отправить", "Send"), tint = if (text.isNotBlank()) C.OnAccent else C.Muted) }
        }
    }
}

@Composable
private fun Bubble(text: String, out: Boolean, meta: String, failed: Boolean, pending: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (out) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (out) 18.dp else 4.dp, bottomEnd = if (out) 4.dp else 18.dp))
                .background(
                    when {
                        failed -> C.Red.copy(alpha = 0.18f)
                        out && !pending -> C.Emerald.copy(alpha = 0.18f)
                        out -> C.Surface2
                        else -> C.Surface
                    },
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text(text, fontSize = 15.sp, lineHeight = 21.sp)
            Text(meta, fontSize = 11.sp, color = if (failed) C.Red else C.Muted, modifier = Modifier.align(Alignment.End))
        }
    }
}
