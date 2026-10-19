package app.simgate.net

import app.simgate.data.t
import java.net.URLDecoder

/**
 * Parsing of "connect to SMS server" payloads produced by the server's add-device wizard.
 *
 * One scan / one paste should be enough to configure the "SMS server" mode, with no manual typing.
 * Three input shapes are understood:
 *   1. the connect link    ismsgw://connect?v=1&url=<url-encoded>&token=<token>&name=<optional>
 *   2. an App Links URL     https://<host>/connect#url=…&token=…&name=…   (the part after "#" never
 *      leaves the browser, so the token is not sent to the server)
 *   3. the legacy QR        two lines: "URL\ntoken"
 *
 * Pure Kotlin, no Android classes (android.net.Uri is unavailable in plain unit tests), so it can be
 * covered by [app.simgate.net.ConnectLinkTest]. The UI turns the result into a confirmation screen.
 */
object ConnectLink {
    /** Highest connect-link version this build understands. */
    const val VERSION = 1

    /** Standard device token issued by the server: smsgw_<8>_<32>. Other tokens are accepted with a warning. */
    val TOKEN_REGEX = Regex("^smsgw_[A-Za-z0-9]{8}_[A-Za-z0-9]{32}$")
    private val URL_REGEX = Regex("^(wss|ws|https|http)://.+", RegexOption.IGNORE_CASE)
}

/** A successfully parsed connection. [insecure] = ws:// or http://; [knownToken] = matches the standard format. */
data class ConnectPayload(
    val url: String,
    val token: String,
    val name: String? = null,
    val insecure: Boolean = false,
    val knownToken: Boolean = true,
) {
    /** Non-fatal warnings to show on the confirmation screen (never blocks connecting). */
    fun warnings(): List<String> = buildList {
        if (insecure) add(t("Соединение не шифруется — используйте wss:// или https://", "The connection is not encrypted — use wss:// or https://"))
        if (!knownToken) add(t("Токен необычного вида — проверьте, что он от вашего сервера", "The token has an unusual format — make sure it is from your server"))
    }
}

/** Why a payload could not be parsed. [message] is localized for display; tests assert on the kind. */
data class ConnectError(val kind: Kind, val message: String) {
    enum class Kind { EMPTY, UNKNOWN_VERSION, BAD_URL, BAD_FORMAT }
}

sealed interface ConnectResult
@JvmInline value class Parsed(val payload: ConnectPayload) : ConnectResult
@JvmInline value class Failed(val error: ConnectError) : ConnectResult

/** Parses any supported payload. Returns [Parsed] or [Failed]; never throws. */
fun parseConnectPayload(text: String): ConnectResult {
    val s = text.trim()
    if (s.isEmpty()) return Failed(ConnectError(ConnectError.Kind.EMPTY, t("Пустая ссылка", "Empty link")))

    return when {
        s.startsWith("ismsgw://", ignoreCase = true) -> fromQuery(s.substringAfter('?', ""))
        looksLikeAppLink(s) -> fromQuery(s.substringAfter('#', ""))
        else -> fromLegacyLines(s)
    }
}

/** https://host/connect#url=…&token=… — the connect data lives in the URL fragment. */
private fun looksLikeAppLink(s: String): Boolean {
    if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) return false
    val frag = s.substringAfter('#', "")
    return frag.contains("url=", true) || frag.contains("token=", true)
}

private fun fromQuery(query: String): ConnectResult {
    val params = parseParams(query)
    params["v"]?.let { v ->
        if (v.toIntOrNull() != ConnectLink.VERSION)
            return Failed(ConnectError(ConnectError.Kind.UNKNOWN_VERSION, t("Обновите приложение", "Please update the app")))
    }
    return build(params["url"], params["token"], params["name"])
}

private fun fromLegacyLines(s: String): ConnectResult {
    // "URL\ntoken"; tolerate extra blank lines or a single space-separated line.
    val parts = s.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.size < 2) return Failed(ConnectError(ConnectError.Kind.BAD_FORMAT, t("Не удалось распознать данные подключения", "Could not read the connection data")))
    return build(parts[0], parts[1], null)
}

private fun build(rawUrl: String?, rawToken: String?, rawName: String?): ConnectResult {
    val url = rawUrl?.trim().orEmpty()
    val token = rawToken?.trim().orEmpty()
    if (url.isEmpty() || token.isEmpty())
        return Failed(ConnectError(ConnectError.Kind.BAD_FORMAT, t("Не удалось распознать данные подключения", "Could not read the connection data")))
    if (!Regex("^(wss|ws|https|http)://.+", RegexOption.IGNORE_CASE).matches(url))
        return Failed(ConnectError(ConnectError.Kind.BAD_URL, t("Неверный адрес сервера", "Invalid server address")))
    val scheme = url.substringBefore("://").lowercase()
    val name = rawName?.trim()?.take(120)?.takeIf { it.isNotEmpty() }
    return Parsed(ConnectPayload(
        url = url,
        token = token,
        name = name,
        insecure = scheme == "ws" || scheme == "http",
        knownToken = ConnectLink.TOKEN_REGEX.matches(token),
    ))
}

/** Splits "a=1&b=2" into a map, url-decoding keys and values. Last value wins. */
private fun parseParams(query: String): Map<String, String> {
    if (query.isBlank()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (pair in query.split('&')) {
        if (pair.isBlank()) continue
        val k = dec(pair.substringBefore('='))
        val v = if (pair.contains('=')) dec(pair.substringAfter('=')) else ""
        if (k.isNotEmpty()) out[k] = v
    }
    return out
}

private fun dec(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
