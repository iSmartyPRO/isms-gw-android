package app.simgate.data

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backup: settings (users, keys, SIMs, integrations) and, optionally, message
 * history. The file is encrypted with AES-256-GCM using a password-derived key (PBKDF2-SHA256, 150,000 iterations),
 * so it can be stored anywhere and moved to another phone.
 */
object Backup {
    const val FORMAT = "isms-gw-backup"
    private const val ITER = 150_000

    @Serializable
    data class File(
        val format: String = FORMAT,
        val version: Int = 1,
        val app: String = "",
        val createdAt: Long = 0,
        val device: String = "",
        val salt: String,
        val iv: String,
        val iterations: Int = ITER,
        val data: String,
    )

    @Serializable
    private data class Payload(val config: Config, val messages: List<Message> = emptyList())

    private fun key(password: String, salt: ByteArray, iter: Int): SecretKeySpec {
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iter, 256)).encoded
        return SecretKeySpec(raw, "AES")
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    fun export(password: String, includeHistory: Boolean): String {
        val cfg = ConfigStore.value
        val payload = Payload(cfg, if (includeHistory) Db.exportMessages() else emptyList())
        val plain = ConfigStore.json.encodeToString(payload).toByteArray()
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt, ITER), GCMParameterSpec(128, iv))
        val file = File(
            app = app.simgate.BuildConfig.VERSION_NAME, createdAt = System.currentTimeMillis(), device = cfg.deviceName,
            salt = b64(salt), iv = b64(iv), data = b64(c.doFinal(plain)),
        )
        return ConfigStore.json.encodeToString(file)
    }

    class BadBackup(msg: String) : Exception(msg)

    /** Restore. Returns the number of imported messages. */
    fun restore(fileJson: String, password: String, includeHistory: Boolean): Int {
        val f = runCatching { ConfigStore.json.decodeFromString<File>(fileJson) }.getOrNull()
            ?: throw BadBackup(t("Это не файл резервной копии iSMS GW", "This is not an iSMS GW backup file"))
        if (f.format != FORMAT) throw BadBackup(t("Неизвестный формат файла", "Unknown file format"))
        val plain = runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(password, unb64(f.salt), f.iterations), GCMParameterSpec(128, unb64(f.iv)))
            c.doFinal(unb64(f.data))
        }.getOrElse { throw BadBackup(t("Неверный пароль или файл повреждён", "Wrong password or the file is corrupted")) }
        val p = ConfigStore.json.decodeFromString<Payload>(String(plain))
        val current = ConfigStore.value
        // Device ID and certificate password are kept from the current phone.
        ConfigStore.update { p.config.copy(deviceId = current.deviceId, tlsPassword = current.tlsPassword, wasRunning = current.wasRunning) }
        I18n.apply(ConfigStore.value.language)
        var n = 0
        if (includeHistory && p.messages.isNotEmpty()) n = Db.importMessages(p.messages)
        Log.sec(t("Восстановлена резервная копия от ${f.device} (сообщений: $n)", "Backup from ${f.device} restored (messages: $n)"))
        return n
    }
}
