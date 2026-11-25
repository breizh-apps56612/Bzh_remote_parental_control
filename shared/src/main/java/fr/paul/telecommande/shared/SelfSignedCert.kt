package fr.paul.telecommande.shared

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Génère un certificat X.509 auto-signé (RSA 2048 / SHA-256) en DER, sans BouncyCastle,
 * pour rester compatible avec Android 5.0. Utilisé comme certificat client Android TV Remote v2.
 */
object SelfSignedCert {

    fun generate(commonName: String): Pair<KeyPair, X509Certificate> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 3600 * 1000)
        val notAfter = Date(now + 20L * 365 * 24 * 3600 * 1000)
        val sigAlg = seq(oid(byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)), byteArrayOf(0x05, 0x00))
        val name = seq(set(seq(oid(byteArrayOf(0x55, 0x04, 0x03)), tlv(0x0C, commonName.toByteArray(Charsets.UTF_8)))))

        val tbs = seq(
            tlv(0xA0, integer(BigInteger.valueOf(2))),
            integer(BigInteger(63, SecureRandom()).add(BigInteger.ONE)),
            sigAlg,
            name,
            seq(time(notBefore), time(notAfter)),
            name,
            kp.public.encoded
        )
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(kp.private)
        signer.update(tbs)
        val sig = signer.sign()
        val der = seq(tbs, sigAlg, tlv(0x03, byteArrayOf(0) + sig))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        return kp to cert
    }

    private fun time(d: Date): ByteArray {
        val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, f.format(d).toByteArray(Charsets.US_ASCII))
    }

    private fun integer(v: BigInteger) = tlv(0x02, v.toByteArray())
    private fun oid(body: ByteArray) = tlv(0x06, body)
    private fun seq(vararg parts: ByteArray) = tlv(0x30, concat(parts))
    private fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val o = ByteArrayOutputStream(); parts.forEach { o.write(it) }; return o.toByteArray()
    }

    fun tlv(tag: Int, body: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(tag)
        val len = body.size
        when {
            len < 0x80 -> o.write(len)
            len < 0x100 -> { o.write(0x81); o.write(len) }
            len < 0x10000 -> { o.write(0x82); o.write(len shr 8); o.write(len and 0xFF) }
            else -> { o.write(0x83); o.write(len shr 16); o.write((len shr 8) and 0xFF); o.write(len and 0xFF) }
        }
        o.write(body)
        return o.toByteArray()
    }
}
