package app.simgate.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.simgate.data.t
import app.simgate.sms.DeviceIds

/** IMEI rows of the "Device" section: masked by default, reveal and copy per slot; *#06# where the OS hides it. */
@Composable
fun ImeiRows() {
    val ctx = LocalContext.current
    val rev = MainActivity.permissionsRevision.intValue
    val state = remember(rev) { DeviceIds.read(ctx) }
    var shown by remember { mutableStateOf(false) }
    when (state) {
        is DeviceIds.State.Available -> state.slots.forEach { s ->
            val title = if (state.slots.size > 1) "IMEI ${s.slot}" else "IMEI"
            SettingRow(Icons.Rounded.Fingerprint, title, if (shown) s.imei else DeviceIds.mask(s.imei), C.Indigo, onClick = { shown = !shown }) {
                IconButton(onClick = { shown = !shown }) {
                    Icon(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (shown) t("Скрыть", "Hide") else t("Показать", "Show"), tint = C.Muted)
                }
                IconButton(onClick = { copySensitive(ctx, title, s.imei) }) {
                    Icon(Icons.Rounded.ContentCopy, t("Копировать", "Copy"), tint = C.Muted)
                }
            }
        }
        DeviceIds.State.Restricted -> SettingRow(Icons.Rounded.Fingerprint, "IMEI",
            t("Android 10+ не выдаёт IMEI приложениям. Нажмите — система покажет его (${DeviceIds.DIAL_CODE})",
                "Android 10+ does not give apps the IMEI. Tap and the system will show it (${DeviceIds.DIAL_CODE})"), C.Muted, onClick = { dialImei(ctx) })
        // READ_PHONE_STATE is a required permission: without it the app shows the permission screen anyway.
        DeviceIds.State.NoPermission -> SettingRow(Icons.Rounded.Fingerprint, "IMEI", t("Нет разрешения «Телефон»", "No Phone permission"), C.Amber)
        DeviceIds.State.None -> Unit
    }
}

private fun dialImei(ctx: Context) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(DeviceIds.DIAL_CODE)))) }
}

private fun copySensitive(ctx: Context, label: String, value: String) {
    val clip = ClipData.newPlainText(label, value)
    // Android 13+: keep the value out of the clipboard preview.
    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, t("Скопировано", "Copied"), Toast.LENGTH_SHORT).show()
}
