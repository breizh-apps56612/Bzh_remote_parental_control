package fr.paul.telecommande.shared

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Protocole téléphone ↔ appli Box.
 *
 * Sécurité :
 *  - TLS avec certificat des deux côtés (chaque appareil a sa propre clé RSA 2048).
 *  - Appairage : la box affiche un code à 6 chiffres sur la TV ; le téléphone prouve qu'il le
 *    connaît avec HMAC-SHA256(code, empreinte téléphone ‖ empreinte box). Une interception (MITM)
 *    change l'empreinte vue par le téléphone et fait échouer la preuve. 3 essais par code.
 *  - Ensuite : la box n'accepte que les empreintes appairées, le téléphone n'accepte que
 *    l'empreinte de la box mémorisée (épinglage).
 *  - Messages : une ligne JSON par message, 256 Ko maximum.
 */
object BoxProtocol {
    const val PORT = 7363
    const val MAX_LINE = 256 * 1024

    fun fingerprint(cert: X509Certificate): String = sha256Hex(cert.encoded)

    fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun proof(code: String, phoneFp: String, boxFp: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal((phoneFp + boxFp).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Comparaison en temps constant (évite les attaques par mesure du temps). */
    fun safeEquals(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    fun newPairingCode(): String = "%06d".format(SecureRandom().nextInt(1_000_000))

    fun pinHash(pin: String): String = sha256Hex(("telecommande-pin:" + pin).toByteArray())

    /** Accepte tout certificat au niveau TLS : la vérification se fait par empreinte, juste après. */
    val acceptAll: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    fun sslContext(key: PrivateKey, cert: X509Certificate): SSLContext {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("me", key, CharArray(0), arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(acceptAll), SecureRandom()) }
    }

    fun write(out: OutputStream, msg: JSONObject) {
        out.write((msg.toString() + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Réponses d'une box déjà appairée (liste d'applis avec icônes…) : jusqu'à 12 Mo. */
    const val MAX_TRUSTED_LINE = 12 * 1024 * 1024

    class LineReader(input: InputStream, private val maxLen: Int = MAX_LINE) {
        private val r = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 8192)
        fun next(): JSONObject? {
            val sb = StringBuilder()
            while (true) {
                val c = r.read()
                if (c < 0) return if (sb.isEmpty()) null else parse(sb)
                if (c == '\n'.code) break
                sb.append(c.toChar())
                if (sb.length > maxLen) throw IllegalStateException("Message trop long")
            }
            return parse(sb)
        }

        private fun parse(sb: StringBuilder): JSONObject {
            checkDepth(sb)
            return JSONObject(sb.toString())
        }
    }

    /** Profondeur d'imbrication maximale d'un message (les vrais messages en ont moins de 10). */
    const val MAX_DEPTH = 32

    /**
     * Refuse un message trop imbriqué AVANT de l'analyser : org.json est récursif, et un message
     * « [[[[… » de quelques Ko suffisait à faire déborder la pile (StackOverflowError) et à tuer l'appli Box.
     */
    fun checkDepth(s: CharSequence, max: Int = MAX_DEPTH) {
        var depth = 0
        var inString = false
        var escaped = false
        for (ch in s) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{', '[' -> if (++depth > max) throw IllegalStateException("Message trop imbriqué")
                '}', ']' -> depth--
            }
        }
    }
}
