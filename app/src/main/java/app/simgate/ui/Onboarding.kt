package app.simgate.ui

import app.simgate.data.t
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Onboarding(isDefault: Boolean, missing: List<String>, onRequestRole: () -> Unit, onRequestPermissions: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(C.Bg).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ThemeSwitcher(compact = true)
            LanguageSwitcher()
        }
        Spacer(Modifier.height(20.dp))
        // App logo: the two vector layers of the adaptive launcher icon.
        Box(Modifier.size(80.dp).clip(RoundedCornerShape(24.dp))) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(app.simgate.R.drawable.ic_launcher_bg), null, Modifier.fillMaxSize())
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(app.simgate.R.drawable.ic_launcher_fg), null, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(24.dp))
        Text("Добро пожаловать в iSMS GW", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            t("SMS-приложение со встроенным шлюзом: отправка и приём SMS через веб-панель, REST API, вебхуки и постоянное подключение к вашему серверу. Поддерживаются обе SIM-карты.", "An SMS app with a built-in gateway: send and receive SMS via the web panel, REST API, webhooks, Telegram and a persistent connection to your server. Both SIM cards are supported."),
            color = C.Muted, style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(28.dp))

        Step(1, t("SMS-приложение по умолчанию", "Default SMS app"), t("iSMS GW заменит стандартное приложение сообщений: будет показывать диалоги, уведомлять о новых SMS и отправлять их.", "iSMS GW will replace the stock messaging app: it shows conversations, notifies about new SMS and sends them."), isDefault, Icons.Rounded.Sms) {
            if (!isDefault) Btn(t("Назначить", "Set as default"), onRequestRole)
        }
        Spacer(Modifier.height(12.dp))
        Step(2, t("Разрешения", "Permissions"), t("Доступ к SMS, сведениям о SIM-картах (для работы с двумя SIM) и уведомлениям.", "Access to SMS, SIM card info (for dual-SIM support) and notifications."), isDefault && missing.isEmpty(), Icons.Rounded.Verified) {
            if (isDefault && missing.isNotEmpty()) Btn(t("Разрешить", "Allow"), onRequestPermissions)
        }
        Spacer(Modifier.height(24.dp))

        Card {
            Row(verticalAlignment = Alignment.Top) {
                IconBadge(Icons.Rounded.Shield, C.Emerald, 36)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Ваши данные остаются у вас", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t("iSMS GW не имеет облака и не передаёт SMS разработчику. Сообщения уходят только на адреса, которые вы сами укажете (вебхуки, ваш SMS-сервер, ваш Telegram-бот), или читаются клиентами с вашим API-ключом. Шлюз выключен, пока вы его не запустите.", "iSMS GW has no cloud and never sends SMS to the developer. Messages go only to destinations you configure (webhooks, your SMS server, your Telegram bot) or are read by clients with your API key. The gateway stays off until you start it."),
                        color = C.Muted, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                }
            }
        }
        BrandFooter()
    }
}

@Composable
private fun Step(n: Int, title: String, text: String, done: Boolean, icon: ImageVector, action: @Composable () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(if (done) C.Emerald else C.Surface2),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Icon(Icons.Rounded.Check, null, tint = C.OnAccent, modifier = Modifier.size(20.dp))
                else Text("$n", fontWeight = FontWeight.Bold, color = C.Text)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(text, color = C.Muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) { action() }
    }
}

@Composable
fun Btn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = C.Emerald) {
    Button(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = C.OnAccent),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}
