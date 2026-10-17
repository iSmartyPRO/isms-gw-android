package app.simgate.server

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/**
 * Self-signed certificate for HTTPS. Generated on the device when HTTPS is first
 * enabled (RSA-2048, SHA-256, 10 years), without third-party libraries.
 */
object Tls {
    /** Random password from the configuration (encrypted on disk with a Keystore key). */
    private fun pass() = app.simgate.data.ConfigStore.value.tlsPassword.toCharArray()
    private fun file(ctx: Context) = File(ctx.filesDir, "tls.p12")

    fun serverSocketFactory(ctx: Context): SSLServerSocketFactory {
        val ks = loadOrCreate(ctx)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, pass())
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(kmf.keyManagers, null, null)
        return ssl.serverSocketFactory
    }

    fun fingerprint(ctx: Context): String? = runCatching {
        if (!file(ctx).exists()) return null
        val cert = loadOrCreate(ctx).getCertificate("isms-gw")
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    fun regenerate(ctx: Context) { file(ctx).delete(); loadOrCreate(ctx) }

    @Synchronized
    private fun loadOrCreate(ctx: Context): KeyStore {
        val f = file(ctx)
        val ks = KeyStore.getInstance("PKCS12")
        if (f.exists()) {
            runCatching { f.inputStream().use { ks.load(it, pass()) }; return ks }
        }
        ks.load(null, null)
        val kpg = KeyPairGenerator.getInstance("RSA"); kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val cert = selfSigned(kp.public.encoded) { tbs ->
            Signature.getInstance("SHA256withRSA").run { initSign(kp.private); update(tbs); sign() }
        }
        ks.setKeyEntry("isms-gw", kp.private, pass(), arrayOf(cert))
        f.outputStream().use { ks.store(it, pass()) }
        return ks
    }

    // ---------- minimal X.509 DER encoder ----------

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(); out.write(tag)
        val len = content.size
        when {
            len < 0x80 -> out.write(len)
            len < 0x100 -> { out.write(0x81); out.write(len) }
            len < 0x10000 -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xFF) }
            else -> { out.write(0x83); out.write(len shr 16); out.write((len shr 8) and 0xFF); out.write(len and 0xFF) }
        }
        out.write(content); return out.toByteArray()
    }

    private fun seq(vararg items: ByteArray) = der(0x30, items.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg items: ByteArray) = der(0x31, items.fold(ByteArray(0)) { a, b -> a + b })
    private fun int(v: BigInteger) = der(0x02, v.toByteArray())
    private fun oid(vararg arcs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (i in 2 until arcs.size) {
            var v = arcs[i]; val stack = ArrayList<Int>()
            stack += (v and 0x7F).toInt(); v = v shr 7
            while (v > 0) { stack += ((v and 0x7F) or 0x80).toInt(); v = v shr 7 }
            stack.reversed().forEach { out.write(it) }
        }
        return der(0x06, out.toByteArray())
    }
    private fun utf8(s: String) = der(0x0C, s.toByteArray())
    private fun time(d: Date): ByteArray {
        val f = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return der(0x18, f.format(d).toByteArray()) // GeneralizedTime
    }

    private fun selfSigned(spki: ByteArray, sign: (ByteArray) -> ByteArray): X509Certificate {
        val algId = seq(oid(1, 2, 840, 113549, 1, 1, 11), byteArrayOf(0x05, 0x00)) // sha256WithRSAEncryption
        val name = seq(set(seq(oid(2, 5, 4, 3), utf8("iSMS GW SMS Gateway"))), set(seq(oid(2, 5, 4, 10), utf8("iSMS GW"))))
        val now = System.currentTimeMillis()
        val validity = seq(time(Date(now - 86_400_000L)), time(Date(now + 3650L * 86_400_000L)))
        val serial = BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE)
        val version = der(0xA0, int(BigInteger.valueOf(2))) // v3
        val tbs = seq(version, int(serial), algId, name, validity, name, spki)
        val sig = sign(tbs)
        val cert = seq(tbs, algId, der(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(cert.inputStream()) as X509Certificate
    }
}
