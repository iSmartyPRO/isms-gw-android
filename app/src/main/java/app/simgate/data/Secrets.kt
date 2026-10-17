package app.simgate.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encryption of secrets (tokens, webhook secrets, certificate password) with an AES-256-GCM key
 * from the Android Keystore. The key is non-extractable: even if the app files are copied,
 * the secrets cannot be decrypted on another device.
 * Format: "enc:v1:" + base64(iv(12) + ciphertext+tag).
 */
object Secrets {
    private const val ALIAS = "isms-gw-secrets"
    private const val PREFIX = "enc:v1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun isEncrypted(v: String) = v.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        if (plain.isEmpty() || isEncrypted(plain)) return plain
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            PREFIX + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        }.getOrElse { plain } // do not lose data if the Keystore fails
    }

    fun decrypt(v: String): String {
        if (!isEncrypted(v)) return v
        return runCatching {
            val raw = Base64.decode(v.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12))
        }.getOrElse { "" }
    }

    /** Copy of the configuration with encrypted secrets, for writing to disk. */
    fun sealConfig(c: Config): Config = c.copy(
        telegram = c.telegram.copy(token = encrypt(c.telegram.token)),
        remote = c.remote.copy(token = encrypt(c.remote.token)),
        webhooks = c.webhooks.map { it.copy(secret = encrypt(it.secret)) },
        admin = c.admin.copy(initialPassword = encrypt(c.admin.initialPassword)),
        tlsPassword = encrypt(c.tlsPassword),
    )

    /** Decryption after reading from disk (secrets are kept in plaintext in memory). */
    fun openConfig(c: Config): Config = c.copy(
        telegram = c.telegram.copy(token = decrypt(c.telegram.token)),
        remote = c.remote.copy(token = decrypt(c.remote.token)),
        webhooks = c.webhooks.map { it.copy(secret = decrypt(it.secret)) },
        admin = c.admin.copy(initialPassword = decrypt(c.admin.initialPassword)),
        tlsPassword = decrypt(c.tlsPassword),
    )
}
