package fr.paul.telecommande.box

import android.os.SystemClock
import fr.paul.telecommande.shared.BoxProtocol
import org.json.JSONObject
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.SocketException
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Serveur local (réseau de la maison uniquement) qui répond aux téléphones appairés.
 * TLS avec certificat client obligatoire ; seuls les téléphones appairés ont accès aux commandes.
 */
class Server(private val guard: GuardService) {
    private val store = BoxStore.get(guard)
    private val pool = Executors.newCachedThreadPool()
    private val active = AtomicInteger(0)
    /** Connexions de téléphones non appairés : peu nombreuses et courtes, pour qu'elles ne bloquent jamais celui du parent. */
    private val untrusted = AtomicInteger(0)
    private var socket: SSLServerSocket? = null
    @Volatile private var running = false

    /** Connexions « subscribe » ouvertes : on y pousse les événements (demandes de temps…). */
    private val subscribers = CopyOnWriteArrayList<OutputStream>()

    // Appairage : un seul code actif, 2 minutes, 3 essais
    private var pairCode: String? = null
    private var pairExpiry = 0L
    private var pairAttempts = 0
    private var lastPairStart = 0L
    /** Un téléphone est déjà appairé et un code parent existe : le code ne s'affiche qu'après validation du parent. */
    private var awaitingApproval = false
    private var approvalExpiry = 0L

    fun start() {
        if (running) return
        running = true
        // Signal de vie toutes les 20 s : le téléphone repère tout de suite un lien mort et se reconnecte.
        Thread {
            while (running) {
                SystemClock.sleep(20_000)
                if (running && subscribers.isNotEmpty()) push(JSONObject().put("event", "ping"))
            }
        }.apply { isDaemon = true; name = "box-ping" }.start()
        pool.execute {
            while (running) {
                try {
                    val (key, cert) = store.identity()
                    val ss = BoxProtocol.sslContext(key, cert).serverSocketFactory.createServerSocket() as SSLServerSocket
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(BoxProtocol.PORT))
                    ss.needClientAuth = true
                    socket = ss
                    while (running) {
                        val c = ss.accept() as SSLSocket
                        if (active.get() >= 12) { runCatching { c.close() }; continue }
                        pool.execute { handle(c) }
                    }
                } catch (e: Exception) {
                    if (running) SystemClock.sleep(3000)   // port occupé, réseau pas prêt… on réessaie
                } finally {
                    runCatching { socket?.close() }
                }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        subscribers.forEach { runCatching { it.close() } }
    }

    fun push(event: JSONObject) {
        val dead = mutableListOf<OutputStream>()
        subscribers.forEach { out -> synchronized(out) { runCatching { BoxProtocol.write(out, event) }.onFailure { dead += out } } }
        subscribers.removeAll(dead.toSet())
    }

    private fun handle(c: SSLSocket) {
        active.incrementAndGet()
        var subscribed: OutputStream? = null
        var countedUntrusted = false
        try {
            c.soTimeout = 30_000
            c.startHandshake()
            val peer = c.session.peerCertificates.firstOrNull() as? X509Certificate ?: return
            val clientFp = BoxProtocol.fingerprint(peer)
            if (!store.phones().containsKey(clientFp)) {
                countedUntrusted = true
                if (untrusted.incrementAndGet() > MAX_UNTRUSTED) return
                c.soTimeout = 15_000
            }
            var lines = 0
            val boxFp = BoxProtocol.fingerprint(store.identity().second)
            val reader = BoxProtocol.LineReader(c.inputStream)
            val out = c.outputStream
            while (true) {
                val req = reader.next() ?: break
                lines++
                val id = req.optString("id")
                val cmd = req.optString("cmd")
                val trusted = store.phones().containsKey(clientFp)
                val res = try {
                    when {
                        cmd == "pair_start" -> pairStart(req)
                        cmd == "pair_confirm" -> pairConfirm(req, clientFp, boxFp)
                        !trusted -> err("Téléphone non appairé")
                        cmd == "subscribe" -> {
                            c.soTimeout = 0
                            subscribers += out; subscribed = out
                            JSONObject().put("ok", true)
                        }
                        else -> guard.command(cmd, req, store.phones()[clientFp] ?: "téléphone", clientFp)
                    }
                } catch (e: Exception) {
                    err(e.message ?: "Erreur")
                }
                synchronized(out) { BoxProtocol.write(out, res.put("id", id)) }
                // Téléphone inconnu : seulement l'appairage, quelques messages, puis on ferme
                if (!store.phones().containsKey(clientFp) && (cmd != "pair_start" && cmd != "pair_confirm" || lines >= 6)) break
                // Téléphone qui vient de se (re)connecter : on lui renvoie la demande de code en cours.
                if (cmd == "subscribe" && subscribed != null) guard.overlays.pendingPin?.let { (pid, title) ->
                    synchronized(out) { runCatching { BoxProtocol.write(out, JSONObject().put("event", "pin_request").put("id", pid).put("title", title)) } }
                }
            }
        } catch (_: SocketException) {
        } catch (_: Exception) {
        } finally {
            subscribed?.let { subscribers.remove(it) }
            if (countedUntrusted) untrusted.decrementAndGet()
            runCatching { c.close() }
            active.decrementAndGet()
        }
    }

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg)

    companion object { const val MAX_UNTRUSTED = 3 }

    @Synchronized
    private fun pairStart(req: JSONObject): JSONObject {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPairStart < 5_000) return err("Patientez quelques secondes avant une nouvelle demande")
        lastPairStart = now
        val box = android.os.Build.MODEL ?: "Android TV"
        // Déjà un téléphone de parent + un code parent : un enfant ne doit pas pouvoir appairer son propre téléphone
        // simplement en lisant le code affiché sur la TV. Le parent valide d'abord (code sur la TV ou empreinte sur son téléphone).
        if (store.phones().isNotEmpty() && store.rules().pinHash.isNotBlank()) {
            pairCode = null
            if (awaitingApproval && now < approvalExpiry) return JSONObject().put("ok", true).put("box", box).put("approval", true)
                .put("message", "Validez d'abord avec le code parent sur la TV")
            awaitingApproval = true; approvalExpiry = now + 180_000
            guard.approvePairing(onOk = { showCode() }, onCancel = { synchronized(this) { awaitingApproval = false } })
            return JSONObject().put("ok", true).put("box", box).put("approval", true)
                .put("message", "Validez avec le code parent sur la TV (ou sur votre téléphone), puis saisissez le code affiché")
        }
        showCode()
        return JSONObject().put("ok", true).put("box", box)
    }

    @Synchronized
    private fun showCode() {
        val code = BoxProtocol.newPairingCode()
        awaitingApproval = false
        pairCode = code; pairExpiry = SystemClock.elapsedRealtime() + 120_000; pairAttempts = 0
        guard.overlays.showPairingCode(code, 120_000)
    }

    @Synchronized
    private fun pairConfirm(req: JSONObject, clientFp: String, boxFp: String): JSONObject {
        val code = pairCode ?: return err(if (awaitingApproval) "Validez d'abord avec le code parent sur la TV, puis saisissez le code affiché" else "Aucun appairage en cours")
        if (SystemClock.elapsedRealtime() > pairExpiry) { pairCode = null; guard.overlays.hidePairingCode(); return err("Code expiré, recommencez") }
        pairAttempts++
        val ok = BoxProtocol.safeEquals(BoxProtocol.proof(code, clientFp, boxFp), req.optString("proof"))
        if (!ok) {
            if (pairAttempts >= 3) { pairCode = null; guard.overlays.hidePairingCode() }
            return err(if (pairAttempts >= 3) "Trop d'essais, recommencez" else "Code incorrect")
        }
        pairCode = null
        guard.overlays.hidePairingCode()
        store.addPhone(clientFp, req.optString("name", "Téléphone"))
        return JSONObject().put("ok", true).put("box", android.os.Build.MODEL ?: "Android TV")
    }
}
