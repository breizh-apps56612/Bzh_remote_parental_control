package fr.paul.telecommande.core

import android.content.Context
import android.util.Base64
import fr.paul.telecommande.shared.SelfSignedCert
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/** Clé et certificat propres à ce téléphone (box Android TV et appli Box). */
object PhoneIdentity {
    @Volatile private var cached: Pair<PrivateKey, X509Certificate>? = null

    @Synchronized
    fun get(context: Context): Pair<PrivateKey, X509Certificate> {
        cached?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences("atv_identity", Context.MODE_PRIVATE)
        val k = prefs.getString("key", null)
        val c = prefs.getString("cert", null)
        if (k != null && c != null) {
            runCatching {
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(k, Base64.NO_WRAP)))
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(Base64.decode(c, Base64.NO_WRAP).inputStream()) as X509Certificate
                return (key to cert).also { cached = it }
            }
        }
        val (kp, cert) = SelfSignedCert.generate("atvremote")
        prefs.edit()
            .putString("key", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString("cert", Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
            .apply()
        return (kp.private to cert).also { cached = it }
    }
}
