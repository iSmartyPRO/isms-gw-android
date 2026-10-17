package app.simgate.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private val rnd = SecureRandom()
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    private const val ITERATIONS = 60_000

    fun randomToken(len: Int): String = buildString { repeat(len) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) } }

    fun randomPassword(len: Int) = randomToken(len)

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun sha256Hex(s: String): String = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))

    fun hmacSha256Hex(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return hex(mac.doFinal(data.toByteArray()))
    }

    fun constantEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun algo() = if (android.os.Build.VERSION.SDK_INT >= 26) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"

    private fun pbkdf2(password: String, salt: String, algo: String, iter: Int): String {
        val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(), iter, 256)
        return hex(SecretKeyFactory.getInstance(algo).generateSecret(spec).encoded)
    }

    fun newAdmin(user: String, password: String): AdminCredentials {
        val salt = randomToken(16)
        val a = algo()
        return AdminCredentials(user = user, salt = salt, hash = "$a\$$ITERATIONS\$" + pbkdf2(password, salt, a, ITERATIONS))
    }

    fun verifyAdmin(c: AdminCredentials, password: String): Boolean {
        val parts = c.hash.split("$")
        if (parts.size != 3) return false
        val calc = pbkdf2(password, c.salt, parts[0], parts[1].toIntOrNull() ?: return false)
        return constantEquals(calc, parts[2])
    }

    /** Password hash: (hash, salt). */
    fun hashPassword(password: String): Pair<String, String> = newAdmin("", password).let { it.hash to it.salt }

    fun verifyPassword(hash: String, salt: String, password: String) =
        hash.isNotEmpty() && verifyAdmin(AdminCredentials(hash = hash, salt = salt), password)

    /** Returns a pair (plaintext key shown once, object to store). */
    fun newApiKey(name: String, scopes: List<String>, allowedIps: List<String>, ownerId: String = User.ADMIN_ID, sims: List<Int> = emptyList()): Pair<String, ApiKey> {
        val token = "sg_" + randomToken(32)
        return token to ApiKey(
            id = randomToken(8), name = name, hash = sha256Hex(token),
            prefix = token.take(9), scopes = scopes, allowedIps = allowedIps, ownerId = ownerId, sims = sims,
        )
    }
}
