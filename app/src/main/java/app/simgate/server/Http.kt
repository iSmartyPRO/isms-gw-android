package app.simgate.server

import app.simgate.data.ConfigStore
import app.simgate.data.t
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class ApiError(val code: Int, message: String) : Exception(message)

class Req(val s: NanoHTTPD.IHTTPSession, val ip: String) {
    val method: String = s.method.name
    val path: String = s.uri.trimEnd('/').ifEmpty { "/" }
    val seg: List<String> = path.split('/').filter { it.isNotEmpty() }

    fun q(name: String): String? = s.parameters[name]?.firstOrNull()
    fun qi(name: String, def: Int) = q(name)?.toIntOrNull() ?: def
    fun header(name: String): String? = s.headers[name.lowercase()]

    val body: JsonObject by lazy {
        val len = header("content-length")?.toIntOrNull() ?: 0
        if (len <= 0) return@lazy JsonObject(emptyMap())
        // Restoring a backup with history can be tens of MB.
        val max = if (path == "/admin/api/restore") 64 * 1024 * 1024 else 256 * 1024
        if (len > max) throw ApiError(413, t("Слишком большой запрос", "Request too large"))
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = s.inputStream.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        val text = String(buf, 0, read, Charsets.UTF_8)
        runCatching { ConfigStore.json.parseToJsonElement(text).jsonObject }.getOrElse { throw ApiError(400, t("Некорректный JSON", "Invalid JSON")) }
    }

    fun str(name: String): String? = body[name]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
    fun bool(name: String): Boolean? = body[name]?.takeIf { it !is JsonNull }?.jsonPrimitive?.booleanOrNull
    fun requireStr(name: String) = str(name)?.takeIf { it.isNotBlank() } ?: throw ApiError(400, t("Поле '$name' обязательно", "Field '$name' is required"))
}

object Http {
    const val JSON_MIME = "application/json; charset=utf-8"

    fun json(el: JsonElement, status: Response.Status = Response.Status.OK): Response =
        NanoHTTPD.newFixedLengthResponse(status, JSON_MIME, el.toString())

    fun ok(vararg pairs: Pair<String, Any?>): Response = json(buildJsonObject {
        put("ok", true)
        pairs.forEach { (k, v) ->
            when (v) {
                null -> put(k, JsonNull)
                is JsonElement -> put(k, v)
                is Number -> put(k, v)
                is Boolean -> put(k, v)
                else -> put(k, v.toString())
            }
        }
    })

    fun error(code: Int, message: String): Response {
        val st = Response.Status.lookup(code) ?: Response.Status.INTERNAL_ERROR
        return json(buildJsonObject { put("ok", false); put("error", message) }, st)
    }
}

/** Web panel sessions (in memory, reset on restart). */
object Sessions {
    private class S(val ip: String, val userId: String, var expires: Long)
    private val map = ConcurrentHashMap<String, S>()
    private val rnd = SecureRandom()
    private const val TTL = 12 * 3600_000L

    fun create(ip: String, userId: String): String {
        val b = ByteArray(32).also { rnd.nextBytes(it) }
        val token = b.joinToString("") { "%02x".format(it) }
        map[token] = S(ip, userId, System.currentTimeMillis() + TTL)
        return token
    }

    /** Session user id. The session is bound to the IP, so a stolen cookie will not work from another address. */
    fun userId(token: String?, ip: String): String? {
        if (token.isNullOrEmpty()) return null
        val s = map[token] ?: return null
        val now = System.currentTimeMillis()
        if (s.expires < now || s.ip != ip) { map.remove(token); return null }
        s.expires = now + TTL
        return s.userId
    }

    fun drop(token: String?) { if (token != null) map.remove(token) }
    fun dropUser(userId: String) { map.values.removeAll { it.userId == userId } }
    fun dropAll() = map.clear()
}
