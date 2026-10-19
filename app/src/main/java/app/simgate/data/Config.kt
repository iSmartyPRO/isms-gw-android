package app.simgate.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Full gateway configuration. Stored as a single JSON document. */
@Serializable
data class Config(
    val deviceName: String = android.os.Build.MODEL ?: "Android",
    val deviceId: String = "",
    /** UI language: "ru", "en" or "" (follow the system). */
    val language: String = "",
    /** Theme: "system", "light", "dark". */
    val theme: String = "system",
    val autostart: Boolean = true,
    /** The gateway was enabled by the user; restore it after reboot. */
    val wasRunning: Boolean = false,
    val keepAwake: Boolean = true,
    val server: ServerConfig = ServerConfig(),
    val firewall: FirewallConfig = FirewallConfig(),
    val sending: SendingConfig = SendingConfig(),
    val receiving: ReceivingConfig = ReceivingConfig(),
    val sims: List<SimConfig> = emptyList(),
    val users: List<User> = emptyList(),
    val apiKeys: List<ApiKey> = emptyList(),
    val webhooks: List<Webhook> = emptyList(),
    val remote: RemoteConfig = RemoteConfig(),
    val telegram: TelegramConfig = TelegramConfig(),
    val storage: StorageConfig = StorageConfig(),
    val calls: CallsConfig = CallsConfig(),
    /** Random HTTPS certificate keystore password (encrypted on disk). */
    val tlsPassword: String = "",
    val admin: AdminCredentials = AdminCredentials(),
)

@Serializable
data class ServerConfig(
    val enabled: Boolean = true,
    val port: Int = 8080,
    val https: Boolean = false,
    val webAdmin: Boolean = true,
)

@Serializable
data class FirewallConfig(
    /** lan: local network only; whitelist: listed addresses only; all: everyone (dangerous). */
    val mode: String = "lan",
    val whitelist: List<String> = emptyList(),
    val blacklist: List<String> = emptyList(),
    /** Web panel only from the local network, even if the API is exposed more widely. */
    val adminLanOnly: Boolean = true,
    val adminWhitelist: List<String> = emptyList(),
    val rateLimitPerMinute: Int = 120,
    val maxAuthFailures: Int = 5,
    val banMinutes: Int = 30,
)

@Serializable
data class SendingConfig(
    /** auto: default SIM; roundrobin: in turn; balanced: least loaded; slotN: a specific slot. */
    val strategy: String = "auto",
    val delayMs: Long = 2000,
    val maxRetries: Int = 2,
    val deliveryReports: Boolean = true,
    val maxPerMinute: Int = 20,
    val destinations: DestinationFilter = DestinationFilter(),
)

/** Outgoing SMS destination filter (see sms/Destinations.kt). */
@Serializable
data class DestinationFilter(
    val enabled: Boolean = true,
    /** Prefixes that are always blocked, e.g. "+2" (Africa), "+88" (satellite / international services). */
    val blocklist: List<String> = emptyList(),
    /** When on and not empty, only these prefixes / numbers are allowed. */
    val allowlistOnly: Boolean = false,
    val allowlist: List<String> = emptyList(),
    /** Block 3-6 digit short and premium codes. */
    val blockShortCodes: Boolean = false,
    /** Allow numbers written without an international code (when no default country code is set). */
    val allowLocal: Boolean = true,
    /** Country code used to interpret local numbers, e.g. "7" or "44" (empty = do not convert). */
    val defaultCountryCode: String = "",
    /** National trunk prefix stripped from local numbers before adding the country code, e.g. "8" or "0". */
    val trunkPrefix: String = "",
)

/** Data retention periods and auto-cleanup. 0 = unlimited. */
@Serializable
data class StorageConfig(
    val historyDays: Int = 90,
    val maxMessages: Int = 50_000,
    val logDays: Int = 30,
    val ussdDays: Int = 90,
    val callDays: Int = 90,
    /** When a message is deleted in the panel, also delete it from the phone's conversations. */
    val deleteFromPhone: Boolean = false,
)

/** Call tracking (notifications, log). */
@Serializable
data class CallsConfig(
    val enabled: Boolean = true,
    val notifyIncoming: Boolean = false,
    val notifyMissed: Boolean = true,
)

@Serializable
data class ReceivingConfig(
    val store: Boolean = true,
    /** Ignore incoming messages from these senders (substring match). */
    val ignoreSenders: List<String> = emptyList(),
)

/**
 * A known SIM card or eSIM profile. The identifier is the Android subscriptionId:
 * it is stable for a given card/profile and does not change when moved between slots.
 * Removed cards stay in the registry (inactive) together with their settings and permissions.
 */
@Serializable
data class SimConfig(
    val simId: Int = -1,
    /** Last known slot (1-based); 0 = unknown. */
    val slot: Int = 0,
    val label: String = "",
    val color: String = "",
    val sendEnabled: Boolean = true,
    val receiveEnabled: Boolean = true,
    val dailyLimit: Int = 0,
    val carrier: String = "",
    val number: String = "",
    val embedded: Boolean = false,
    /** Last 4 digits of the ICCID (if available), to tell apart cards from the same carrier. */
    val iccTail: String = "",
    /** USSD code for checking the balance of this SIM, e.g. *100#. */
    val balanceCode: String = "",
    val firstSeen: Long = 0,
    val lastSeen: Long = 0,
)

/** Web panel user and owner of API keys. */
@Serializable
data class User(
    val id: String,
    val username: String,
    val displayName: String = "",
    /** admin: full access; user: only assigned SIMs and permissions. */
    val role: String = ROLE_USER,
    val hash: String = "",
    val salt: String = "",
    val enabled: Boolean = true,
    val canSend: Boolean = true,
    val canRead: Boolean = true,
    val canUseApi: Boolean = true,
    val canWeb: Boolean = true,
    /** USSD requests (balance, etc.) via own SIMs. */
    val canUssd: Boolean = false,
    /** Click-to-call and call log for own SIMs. */
    val canCall: Boolean = false,
    /** Access to all SIMs, including future ones. */
    val allSims: Boolean = false,
    /** subscriptionIds of allowed SIMs. */
    val sims: List<Int> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastLoginAt: Long = 0,
    val lastLoginIp: String = "",
) {
    val isAdmin get() = role == ROLE_ADMIN

    companion object {
        const val ROLE_ADMIN = "admin"
        const val ROLE_USER = "user"
        const val ADMIN_ID = "admin"
    }
}

@Serializable
data class ApiKey(
    val id: String,
    val name: String,
    val hash: String,
    val prefix: String,
    val scopes: List<String> = listOf("send", "read"),
    val allowedIps: List<String> = emptyList(),
    /** Key owner: key permissions never exceed the owner's permissions. */
    val ownerId: String = User.ADMIN_ID,
    /** Subset of the owner's SIMs; empty = all of the owner's SIMs. */
    val sims: List<Int> = emptyList(),
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0,
    val lastIp: String = "",
)

@Serializable
data class Webhook(
    val id: String,
    val url: String,
    val events: List<String> = listOf("sms.received"),
    val secret: String = "",
    val enabled: Boolean = true,
)

@Serializable
data class RemoteConfig(
    val enabled: Boolean = false,
    val url: String = "",
    val token: String = "",
)

@Serializable
data class TelegramChat(val id: Long, val name: String, val pairedAt: Long = System.currentTimeMillis())

@Serializable
data class TelegramConfig(
    val enabled: Boolean = false,
    val token: String = "",
    /** One-time chat pairing code: /start <code>. Changes after each pairing. */
    val pairingCode: String = "",
    val chats: List<TelegramChat> = emptyList(),
    val notifyIncoming: Boolean = true,
    val notifyFailed: Boolean = true,
    val notifyDelivered: Boolean = false,
    /** Allow sending SMS via bot commands. */
    val allowSend: Boolean = true,
)

@Serializable
data class AdminCredentials(
    val user: String = "admin",
    val hash: String = "",
    val salt: String = "",
    /** Shown in the app until the user changes the password. */
    val initialPassword: String = "",
)

object ConfigStore {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private lateinit var ctx: Context
    private val _flow = MutableStateFlow(Config())
    val flow: StateFlow<Config> get() = _flow
    val value: Config get() = _flow.value

    fun init(context: Context) {
        ctx = context.applicationContext
        val raw = prefs().getString("config", null)
        var cfg = raw?.let { runCatching { json.decodeFromString<Config>(it) }.getOrNull() }?.let { Secrets.openConfig(it) } ?: Config()
        if (cfg.tlsPassword.isEmpty()) cfg = cfg.copy(tlsPassword = Crypto.randomToken(24))
        if (cfg.deviceId.isEmpty()) cfg = cfg.copy(deviceId = Crypto.randomToken(12))
        if (cfg.telegram.pairingCode.isEmpty()) cfg = cfg.copy(telegram = cfg.telegram.copy(pairingCode = Crypto.randomToken(8)))
        if (cfg.admin.hash.isEmpty() && cfg.users.none { it.isAdmin }) {
            val pwd = Crypto.randomPassword(10)
            cfg = cfg.copy(admin = Crypto.newAdmin(cfg.admin.user, pwd).copy(initialPassword = pwd))
        }
        if (cfg.users.none { it.id == User.ADMIN_ID }) {
            cfg = cfg.copy(users = listOf(
                User(User.ADMIN_ID, cfg.admin.user.ifBlank { "admin" }, "Administrator", User.ROLE_ADMIN,
                    hash = cfg.admin.hash, salt = cfg.admin.salt, allSims = true),
            ) + cfg.users)
        }
        _flow.value = cfg
        persist(cfg)
        I18n.apply(cfg.language)
    }

    @Synchronized
    fun update(block: (Config) -> Config): Config {
        val next = block(_flow.value)
        _flow.value = next
        persist(next)
        return next
    }

    private fun persist(cfg: Config) {
        prefs().edit().putString("config", json.encodeToString(Secrets.sealConfig(cfg))).apply()
    }

    private fun prefs() = ctx.getSharedPreferences("isms-gw", Context.MODE_PRIVATE)
}
