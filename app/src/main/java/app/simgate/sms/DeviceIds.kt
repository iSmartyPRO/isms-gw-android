package app.simgate.sms

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * IMEI of each modem slot, shown only in the app settings and to the administrator of the web panel.
 * Never sent through the public REST API, webhooks, Telegram or the SMS server, never logged or backed up:
 * it is read on demand and not stored anywhere.
 *
 * Android 8–9 allow reading it with READ_PHONE_STATE. Since Android 10 it requires the privileged
 * READ_PRIVILEGED_PHONE_STATE (system apps, carrier privileges, device owner), so a regular app gets
 * [State.Restricted] and the UI offers the system *#06# screen instead.
 */
object DeviceIds {
    data class Slot(val slot: Int, val imei: String)

    sealed interface State {
        data class Available(val slots: List<Slot>) : State
        /** READ_PHONE_STATE is not granted. */
        data object NoPermission : State
        /** The OS does not give the IMEI to regular apps (Android 10+). */
        data object Restricted : State
        /** No telephony hardware or no IMEI (e.g. CDMA-only). */
        data object None : State
    }

    /** The system dialer code that shows the IMEI on every Android version. */
    const val DIAL_CODE = "*#06#"

    // Lint wants READ_PRIVILEGED_PHONE_STATE (Android 10+ rule); on 8–9 READ_PHONE_STATE (checked above the call)
    // is enough, and on 10+ the SecurityException is caught and reported as Restricted.
    @SuppressLint("MissingPermission", "HardwareIds")
    fun read(ctx: Context): State {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            return State.NoPermission
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return State.None
        @Suppress("DEPRECATION")
        val count = (if (Build.VERSION.SDK_INT >= 30) tm.activeModemCount else tm.phoneCount).coerceAtLeast(1)
        var restricted = false
        val slots = (0 until count).mapNotNull { i ->
            try {
                tm.getImei(i)?.takeIf { it.isNotBlank() }?.let { Slot(i + 1, it) }
            } catch (_: SecurityException) {
                restricted = true; null
            } catch (_: Exception) {
                null
            }
        }
        return when {
            slots.isNotEmpty() -> State.Available(slots)
            restricted -> State.Restricted
            else -> State.None
        }
    }

    /** "35•••••••••1234": enough to tell the slots apart without exposing the number. */
    fun mask(imei: String) = if (imei.length <= 6) "•".repeat(imei.length) else imei.take(2) + "•".repeat(imei.length - 6) + imei.takeLast(4)
}
