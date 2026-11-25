package fr.paul.telecommande.core

import android.content.Context
import android.os.Build
import fr.paul.telecommande.shared.BoxProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLSocket

/**
 * Client de l'appli Box (installée sur la TV). Chaque appel ouvre une connexion TLS mutuelle ;
 * l'empreinte de la box est épinglée après l'appairage : une autre machine est refusée.
 */
class BoxClient(private val context: Context, private val host: String, private val pinnedFp: String?) {

    internal fun open(timeoutMs: Int = 6000): Pair<SSLSocket, String> {
        val (key, cert) = PhoneIdentity.get(context)
        val raw = java.net.Socket()
        try {
            raw.connect(InetSocketAddress(host, BoxProtocol.PORT), timeoutMs)
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw RemoteException("Appli Box injoignable sur $host (TV éteinte, ou appli non installée / protection non activée).", e)
        }
        val s = BoxProtocol.sslContext(key, cert).socketFactory.createSocket(raw, host, BoxProtocol.PORT, true) as SSLSocket
        s.soTimeout = 30_000
        s.startHandshake()
        val boxFp = BoxProtocol.fingerprint(s.session.peerCertificates[0] as X509Certificate)
        if (pinnedFp != null && !BoxProtocol.safeEquals(pinnedFp, boxFp)) {
            runCatching { s.close() }
            throw RemoteException("Sécurité : la box ne présente pas l'identité attendue. Connexion refusée (réappairez si l'appli Box a été réinstallée).")
        }
        return s to boxFp
    }

    private fun exchange(s: SSLSocket, cmd: String, params: JSONObject): JSONObject {
        val id = UUID.randomUUID().toString().take(8)
        val msg = JSONObject(params.toString()).put("id", id).put("cmd", cmd)
        BoxProtocol.write(s.outputStream, msg)
        val res = BoxProtocol.LineReader(s.inputStream, BoxProtocol.MAX_TRUSTED_LINE).next() ?: throw RemoteException("La box a fermé la connexion")
        if (!res.optBoolean("ok")) throw RemoteException(res.optString("error", "Refusé par la box"))
        return res
    }

    suspend fun call(cmd: String, params: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        val (s, _) = open()
        s.use { exchange(it, cmd, params) }
    }

    /** Étape 1 : la box affiche un code sur la TV. */
    suspend fun pairStart(): String = withContext(Dispatchers.IO) {
        val (s, _) = open()
        s.use { exchange(it, "pair_start", JSONObject()).optString("box") }
    }

    /** Étape 2 : preuve de connaissance du code ; renvoie l'empreinte de la box à épingler. */
    suspend fun pairConfirm(code: String): String = withContext(Dispatchers.IO) {
        val (s, boxFp) = open()
        val phoneFp = BoxProtocol.fingerprint(PhoneIdentity.get(context).second)
        s.use {
            exchange(it, "pair_confirm", JSONObject()
                .put("proof", BoxProtocol.proof(code.filter { c -> c.isDigit() }, phoneFp, boxFp))
                .put("name", (Build.MANUFACTURER ?: "") + " " + (Build.MODEL ?: "Téléphone")))
        }
        boxFp
    }

    /** Écoute des événements (demandes de temps…) ; bloque jusqu'à la coupure. */
    fun subscribe(onEvent: (JSONObject) -> Unit) {
        val (s, _) = open()
        s.use {
            exchange(it, "subscribe", JSONObject())
            it.soTimeout = 0
            val r = BoxProtocol.LineReader(it.inputStream)
            while (true) {
                val ev = r.next() ?: break
                if (ev.optString("event") == "ping") {
                    // Box récente : elle parle toutes les 20 s. 60 s de silence = lien mort → on se reconnecte.
                    it.soTimeout = 60_000
                    continue
                }
                if (ev.has("event")) onEvent(ev)
            }
        }
    }

    companion object {
        fun of(context: Context, d: Device) = BoxClient(context, d.host, d.boxFp.ifBlank { null })
    }
}
