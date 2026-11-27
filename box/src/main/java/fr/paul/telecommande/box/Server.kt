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
    /** Connexions ouvertes par adresse : un seul appareil du Wi-Fi ne peut pas occuper toutes les places. */
    private val perIp = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

    /** Connexions acceptées dont la poignée de main TLS n'est pas finie (une connexion muette y reste). */
    private class Pending(val socket: SSLSocket, val ip: String, val since: Long)
    private val handshaking = java.util.concurrent.ConcurrentLinkedQueue<Pending>()

    /**
     * Toutes les places sont prises : on ferme la plus ancienne connexion encore muette (poignée de main
     * commencée depuis plus de [STALE_HANDSHAKE_MS]) pour faire de la place. Un téléphone qui se connecte
     * vraiment finit sa poignée de main en une fraction de seconde : il n'est jamais visé.
     * Avant : 40 connexions muettes (stress test du 27/11) suffisaient à refuser le téléphone du parent.
     */
    private fun evictStale(ip: String?): Boolean {
        val now = SystemClock.elapsedRealtime()
        val victim = handshaking.filter { (ip == null || it.ip == ip) && now - it.since > STALE_HANDSHAKE_MS }
            .minByOrNull { it.since } ?: return false
        if (!handshaking.remove(victim)) return false
        runCatching { victim.socket.close() }
        return true
    }
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
        // Un seul serveur par processus : si le service d'accessibilité est reconnecté sans être détruit,
        // l'ancien serveur (et son fil « box-ping ») est arrêté au lieu de s'empiler.
        synchronized(Server::class.java) {
            current?.takeIf { it !== this }?.stop()
            current = this
        }
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
                        val ip = c.inetAddress?.hostAddress ?: "?"
                        val n = perIp.getOrPut(ip) { AtomicInteger(0) }
                        val ipFull = n.get() >= MAX_PER_IP
                        if ((ipFull || active.get() >= MAX_ACTIVE) && !evictStale(if (ipFull) ip else null)) {
                            runCatching { c.close() }; continue
                        }
                        n.incrementAndGet()
                        val pending = Pending(c, ip, SystemClock.elapsedRealtime())
                        handshaking.add(pending)
                        pool.execute {
                            try { handle(c) { handshaking.remove(pending) } }
                            finally { handshaking.remove(pending); if (n.decrementAndGet() <= 0) perIp.remove(ip, n) }
                        }
                    }
                } catch (e: Throwable) {
                    if (running) SystemClock.sleep(3000)   // port occupé, réseau pas prêt… on réessaie
                } finally {
                    runCatching { socket?.close() }
                }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { pushExec.shutdown() }
        runCatching { socket?.close() }
        subscribers.forEach { runCatching { it.close() } }
    }

    /** Envois aux téléphones abonnés : un seul fil, dans l'ordre, jamais sur le fil principal. */
    private val pushExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "box-push").apply { isDaemon = true } }

    /**
     * Prévient les téléphones (appli à l'écran, demande de temps, code parent…).
     * Avant : appelé depuis le fil principal, l'écriture réseau était refusée par Android
     * (NetworkOnMainThreadException) et le téléphone était retiré des abonnés sans rien recevoir.
     */
    fun push(event: JSONObject) {
        val text = event.toString()
        runCatching { pushExec.execute { send(JSONObject(text)) } }
    }

    private fun send(event: JSONObject) {
        val dead = mutableListOf<OutputStream>()
        subscribers.forEach { out -> synchronized(out) { runCatching { BoxProtocol.write(out, event) }.onFailure { dead += out } } }
        subscribers.removeAll(dead.toSet())
    }

    private fun handle(c: SSLSocket, handshakeDone: () -> Unit = {}) {
        active.incrementAndGet()
        var subscribed: OutputStream? = null
        var countedUntrusted = false
        try {
            // Poignée de main TLS courte : une connexion muette est fermée vite et ne bloque pas le parent.
            c.soTimeout = HANDSHAKE_TIMEOUT_MS
            c.startHandshake()
            handshakeDone()
            c.soTimeout = 30_000
            val peer = c.session.peerCertificates.firstOrNull() as? X509Certificate ?: return
            val clientFp = BoxProtocol.fingerprint(peer)
            if (!store.phones().containsKey(clientFp)) {
                countedUntrusted = true
                if (untrusted.incrementAndGet() > MAX_UNTRUSTED) return
                c.soTimeout = 15_000
            }
            var lines = 0
            var spake: fr.paul.telecommande.shared.Spake2.Session? = null
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
                        cmd == "pair_spake" -> pairSpake(req, clientFp, boxFp).also { spake = it.second }.first
                        cmd == "pair_spake_confirm" -> pairSpakeConfirm(req, spake, clientFp).also { spake = null }
                        // Ancien appairage (preuve HMAC) : le code pouvait être retrouvé hors ligne par une fausse box.
                        cmd == "pair_confirm" -> err("Mettez à jour l'appli Télécommande du téléphone : l'appairage a été rendu plus sûr.")
                        !trusted -> err("Téléphone non appairé")
                        cmd == "subscribe" -> {
                            c.soTimeout = 0
                            subscribers += out; subscribed = out
                            JSONObject().put("ok", true)
                        }
                        else -> guard.command(cmd, req, store.phones()[clientFp] ?: "téléphone", clientFp)
                    }
                } catch (e: Throwable) {
                    // Throwable et pas seulement Exception : une erreur (pile, mémoire) ne doit jamais tuer l'appli Box
                    err(e.message ?: "Erreur")
                }
                synchronized(out) { BoxProtocol.write(out, res.put("id", id)) }
                // Téléphone inconnu : seulement l'appairage, quelques messages, puis on ferme
                if (!store.phones().containsKey(clientFp) && (cmd !in PAIR_CMDS || lines >= 6)) break
                // Téléphone qui vient de se (re)connecter : on lui renvoie la demande de code en cours.
                if (cmd == "subscribe" && subscribed != null) guard.overlays.pendingPin?.let { (pid, title) ->
                    synchronized(out) { runCatching { BoxProtocol.write(out, JSONObject().put("event", "pin_request").put("id", pid).put("title", title)) } }
                }
            }
        } catch (_: SocketException) {
        } catch (_: Throwable) {
            // Message piégé (trop imbriqué, trop long, illisible) : on ferme cette connexion, rien d'autre.
        } finally {
            subscribed?.let { subscribers.remove(it) }
            if (countedUntrusted) untrusted.decrementAndGet()
            runCatching { c.close() }
            active.decrementAndGet()
        }
    }

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg)

    companion object {
        @Volatile private var current: Server? = null
        const val MAX_UNTRUSTED = 3
        const val MAX_ACTIVE = 24
        const val MAX_PER_IP = 6
        const val HANDSHAKE_TIMEOUT_MS = 8_000
        const val STALE_HANDSHAKE_MS = 1_500L
        val PAIR_CMDS = setOf("pair_start", "pair_spake", "pair_spake_confirm", "pair_confirm")
    }

    @Synchronized
    private fun pairStart(req: JSONObject): JSONObject {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPairStart < 5_000) return err("Patientez quelques secondes avant une nouvelle demande")
        lastPairStart = now
        val box = android.os.Build.MODEL ?: "Android TV"
        // Déjà un téléphone de parent + un code parent : un enfant ne doit pas pouvoir appairer son propre téléphone
        // simplement en lisant le code affiché sur la TV. Le parent valide d'abord (code sur la TV ou empreinte sur son téléphone).
        // Même sans code parent : un téléphone déjà appairé doit valider (sinon l'enfant appaire le sien en lisant la TV).
        if (store.phones().isNotEmpty()) {
            val how = if (store.rules().pinHash.isNotBlank()) "avec le code parent sur la TV (ou sur votre téléphone)" else "sur le téléphone du parent déjà appairé"
            pairCode = null
            if (awaitingApproval && now < approvalExpiry) return JSONObject().put("ok", true).put("box", box).put("approval", true)
                .put("message", "Validez d'abord $how").put("spake", true)
            awaitingApproval = true; approvalExpiry = now + 180_000
            guard.approvePairing(onOk = { showCode() }, onCancel = { synchronized(this) { awaitingApproval = false } })
            return JSONObject().put("ok", true).put("box", box).put("approval", true)
                .put("message", "Validez $how, puis saisissez le code affiché").put("spake", true)
        }
        showCode()
        return JSONObject().put("ok", true).put("box", box).put("spake", true)
    }

    @Synchronized
    private fun showCode() {
        val code = BoxProtocol.newPairingCode()
        awaitingApproval = false
        pairCode = code; pairExpiry = SystemClock.elapsedRealtime() + 120_000; pairAttempts = 0
        guard.overlays.showPairingCode(code, 120_000)
    }

    /** Étape 2 (SPAKE2) : chaque essai ne teste qu'un code, et compte parmi les 3 essais du code affiché. */
    @Synchronized
    private fun pairSpake(req: JSONObject, clientFp: String, boxFp: String): Pair<JSONObject, fr.paul.telecommande.shared.Spake2.Session?> {
        val code = pairCode ?: return err(if (awaitingApproval) "Validez d'abord sur la TV ou sur le téléphone du parent, puis saisissez le code affiché" else "Aucun appairage en cours") to null
        if (SystemClock.elapsedRealtime() > pairExpiry) { pairCode = null; guard.overlays.hidePairingCode(); return err("Code expiré, recommencez") to null }
        pairAttempts++
        if (pairAttempts > 3) { pairCode = null; guard.overlays.hidePairingCode(); return err("Trop d'essais, recommencez") to null }
        val s = fr.paul.telecommande.shared.Spake2.box(code)
        return try {
            s.finish(req.optString("x"), clientFp, boxFp)
            JSONObject().put("ok", true).put("y", s.message) to s
        } catch (e: Exception) { err("Échange invalide") to null }
    }

    @Synchronized
    private fun pairSpakeConfirm(req: JSONObject, s: fr.paul.telecommande.shared.Spake2.Session?, clientFp: String): JSONObject {
        if (s == null || pairCode == null) return err("Aucun appairage en cours")
        if (!BoxProtocol.safeEquals(s.phoneConfirm(), req.optString("confirm"))) {
            if (pairAttempts >= 3) { pairCode = null; guard.overlays.hidePairingCode() }
            return err(if (pairAttempts >= 3) "Trop d'essais, recommencez" else "Code incorrect")
        }
        pairCode = null
        guard.overlays.hidePairingCode()
        store.addPhone(clientFp, req.optString("name", "Téléphone"))
        return JSONObject().put("ok", true).put("confirm", s.boxConfirm()).put("box", android.os.Build.MODEL ?: "Android TV")
    }
}
