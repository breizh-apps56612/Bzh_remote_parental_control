package fr.paul.telecommande.drivers

import fr.paul.telecommande.core.DiagLog

import fr.paul.telecommande.shared.SelfSignedCert

import android.content.Context
import android.util.Base64
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.RemoteException
import fr.paul.telecommande.core.RemoteKey
import fr.paul.telecommande.core.RemoteKey.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Android TV / Google TV : protocole « Android TV Remote v2 » (celui de l'appli Google TV).
 * Appairage TLS sur le port 6467 avec un code à 6 caractères affiché sur la TV,
 * puis commandes protobuf sur le port 6466.
 * Compatible : Sony Bravia récents, Philips Android TV, TCL, Hisense Android, Xiaomi Mi TV/Box,
 * Nvidia Shield, Chromecast avec Google TV, box opérateurs Android TV (Free Pop, Bbox, SFR…).
 */
class AndroidTvDriver(private val context: Context, device: Device) : Driver(device) {
    private var socket: SSLSocket? = null
    private var scope: CoroutineScope? = null
    private val writeLock = Any()

    /** Paquet de l'appli au premier plan sur la box (ex. com.google.android.youtube.tv). */
    private val _currentApp = MutableStateFlow("")
    val currentApp: StateFlow<String> = _currentApp
    private val _isOn = MutableStateFlow(true)
    val isOn: StateFlow<Boolean> = _isOn
    /** Incrémenté à chaque ouverture d'un champ de saisie sur la TV. */
    private val _imeShown = MutableStateFlow(0)
    val imeShown: StateFlow<Int> = _imeShown
    /** Contenu actuel du champ de saisie de la TV (tel que la TV le signale). */
    private val _fieldText = MutableStateFlow("")
    val fieldText: StateFlow<String> = _fieldText
    /** Un champ de saisie est-il ouvert sur la TV (dernière demande de clavier, même appli) ? */
    @Volatile var imeActive = false
        private set
    private var imeCounter = 0L
    private var imeFieldCounter = 0L
    private val features = 623L  // ping|key|ime|voice|power|volume|app_link
    @Volatile private var voiceBegin: CompletableDeferred<Long>? = null
    /** La box accepte-t-elle la voix (annoncé dans sa configuration) ? */
    var voiceSupported = false
        private set

    private val keycodes = mapOf(
        POWER to 26, VOL_UP to 24, VOL_DOWN to 25, MUTE to 164, CH_UP to 166, CH_DOWN to 167,
        UP to 19, DOWN to 20, LEFT to 21, RIGHT to 22, OK to 23, BACK to 4, HOME to 3, MENU to 82,
        INFO to 165, INPUT to 178, EXIT to 111, PLAY_PAUSE to 85, STOP to 86, REWIND to 89, FAST_FORWARD to 90,
        RED to 183, GREEN to 184, YELLOW to 185, BLUE to 186, GUIDE to 172, LAST_CHANNEL to 229
    ) + RemoteKey.DIGITS.mapIndexed { i, k -> k to 7 + i }

    private val connectLock = kotlinx.coroutines.sync.Mutex()

    // L'écran et le suivi en arrière-plan partagent la même connexion : une seule ouverture à la fois.
    override suspend fun connect(ui: PairingUi) = connectLock.withLock { connectInner(ui) }

    private suspend fun connectInner(ui: PairingUi) = withContext(Dispatchers.IO) {
        if (socket?.isClosed == false) return@withContext
        if (device.token != "paired") pair(ui)
        try {
            openRemote()
        } catch (e: Exception) {
            // Certificat refusé (TV réinitialisée, appairage supprimé) : on réappaire une fois.
            device.token = ""; persist()
            pair(ui)
            openRemote()
        }
    }

    // ---------- Identité client (clé + certificat persistants) ----------

    private fun identity(): Pair<PrivateKey, X509Certificate> = fr.paul.telecommande.core.PhoneIdentity.get(context)

    private fun tlsSocket(port: Int): SSLSocket {
        val (key, cert) = identity()
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("client", key, CharArray(0), arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(Net.trustAll), SecureRandom()) }
        val raw = java.net.Socket()
        try {
            raw.connect(InetSocketAddress(device.host, port), 5000)
        } catch (e: java.net.SocketTimeoutException) {
            runCatching { raw.close() }
            throw RemoteException("${device.host}:$port ne répond pas (adresse IP incorrecte, ou box en veille profonde).", e)
        } catch (e: java.net.ConnectException) {
            runCatching { raw.close() }
            throw RemoteException("${device.host}:$port refuse la connexion (ce n'est pas une box Android TV, ou son service télécommande est arrêté).", e)
        } catch (e: java.io.IOException) {
            runCatching { raw.close() }
            throw RemoteException("${device.host}:$port injoignable : ${e.message}", e)
        }
        val s = ctx.socketFactory.createSocket(raw, device.host, port, true) as SSLSocket
        s.useClientMode = true
        s.soTimeout = 8000
        try {
            s.startHandshake()
        } catch (e: Exception) {
            runCatching { s.close() }
            throw RemoteException("${device.host}:$port : échange sécurisé refusé (${e.javaClass.simpleName}). Réinitialisez l'appairage puis réessayez.", e)
        }
        s.soTimeout = 0
        return s
    }

    // ---------- Appairage (port 6467) ----------

    private fun outer() = ProtoWriter().varint(1, 2).varint(2, 200)

    private suspend fun pair(ui: PairingUi) {
        val s = try { tlsSocket(6467) } catch (e: RemoteException) { throw e } catch (e: Exception) {
            throw RemoteException("Android TV injoignable (${device.host}:6467) : ${e.message}", e)
        }
        s.soTimeout = 15000
        try {
            val out = s.outputStream; val inp = s.inputStream
            fun send(w: ProtoWriter) = Proto.writeDelimited(out, w.toByteArray())
            fun expect(field: Int): ProtoMessage {
                val m = Proto.parse(Proto.readDelimited(inp))
                val status = m.long(2) ?: 200
                if (status != 200L) throw RemoteException("Appairage refusé par la TV (statut $status)")
                if (!m.has(field)) throw RemoteException("Réponse inattendue de la TV pendant l'appairage")
                return m
            }
            send(outer().message(10) { string(1, "atvremote"); string(2, "Telecommande Android") })
            expect(11)
            send(outer().message(20) {
                message(1) { varint(1, 3); varint(2, 6) }  // hexadécimal, 6 symboles
                varint(3, 1)                              // rôle : saisie
            })
            expect(20)
            send(outer().message(30) { message(1) { varint(1, 3); varint(2, 6) }; varint(2, 1) })
            expect(31)

            val code = ui.askCode(
                "Appairage Android TV",
                "Saisissez le code à 6 caractères affiché sur la TV.",
                "ex. 4F2A9C"
            )?.trim()?.uppercase() ?: throw RemoteException("Appairage annulé")

            val (_, clientCert) = identity()
            val serverCert = s.session.peerCertificates[0] as X509Certificate
            val secret = AtvSecret.compute(
                clientCert.publicKey as RSAPublicKey, serverCert.publicKey as RSAPublicKey, code
            ) ?: throw RemoteException("Code incorrect. Relancez l'appairage.")
            send(outer().message(40) { bytes(1, secret) })
            expect(41)
            device.token = "paired"; persist()
        } finally {
            runCatching { s.close() }
        }
    }

    // ---------- Commandes (port 6466) ----------

    private suspend fun openRemote() {
        DiagLog.log("ATV", "Connexion à ${device.host}:6466…")
        val s = tlsSocket(6466)
        socket = s
        val ready = CompletableDeferred<Unit>()
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        sc.launch {
            try {
                val inp = s.inputStream
                while (!s.isClosed) {
                    val raw = Proto.readDelimited(inp)
                    DiagLog.log("ATV←", DiagLog.proto(raw))
                    val m = Proto.parse(raw)
                    when {
                        m.has(1) -> {
                            val f = (m.message(1)?.long(1) ?: features) and features
                            voiceSupported = f and 8L != 0L
                            write(ProtoWriter().message(1) {
                                varint(1, f)
                                message(2) {
                                    string(1, android.os.Build.MODEL ?: "Android"); string(2, android.os.Build.MANUFACTURER ?: "Android")
                                    varint(3, 1); string(4, "1"); string(5, "atvremote"); string(6, "1.0.0")
                                }
                            })
                        }
                        m.has(2) -> { write(ProtoWriter().message(2) { varint(1, features) }); ready.complete(Unit) }
                        m.has(8) -> write(ProtoWriter().message(9) { varint(1, m.message(8)?.long(1) ?: 0L) })
                        m.has(20) -> m.message(20)?.let { inject ->
                            inject.message(1)?.let { app ->
                                app.bytes(12)?.let {
                                    val pkg = String(it, Charsets.UTF_8)
                                    if (pkg != _currentApp.value) imeActive = false
                                    _currentApp.value = pkg
                                }
                                app.long(1)?.let { imeCounter = it }
                            }
                            inject.message(2)?.let { tf ->
                                tf.long(1)?.let { imeFieldCounter = it }
                                tf.bytes(2)?.let { _fieldText.value = String(it, Charsets.UTF_8) }
                            }
                        }
                        m.has(21) -> m.message(21)?.let { b ->
                            if (imeCounter == 0L) imeCounter = b.long(1) ?: 0L
                            if (imeFieldCounter == 0L) imeFieldCounter = b.long(2) ?: 0L
                            // la TV renvoie le contenu du champ après chaque modification
                            b.message(3)?.message(2)?.bytes(3)?.let { _fieldText.value = String(it, Charsets.UTF_8) }
                        }
                        m.has(22) -> m.message(22)?.message(2)?.let { tf ->
                            // un champ de saisie vient de prendre le focus sur la TV (clavier affiché)
                            tf.long(1)?.let { imeFieldCounter = it }
                            _fieldText.value = tf.bytes(2)?.let { String(it, Charsets.UTF_8) } ?: ""
                            imeActive = true
                            _imeShown.value = _imeShown.value + 1
                        }
                        m.has(30) -> m.message(30)?.long(1)?.let { voiceBegin?.complete(it) }
                        m.has(40) -> _isOn.value = (m.message(40)?.long(1) ?: 1L) != 0L
                    }
                }
            } catch (e: Exception) {
                DiagLog.log("ATV", "Connexion coupée : ${e.javaClass.simpleName} ${e.message ?: ""}")
                ready.completeExceptionally(e)
                runCatching { s.close() }
            }
        }
        // La TV envoie configure puis set_active ; on attend au maximum 6 s.
        withTimeoutOrNull(6000) { ready.await() }
        if (s.isClosed) throw RemoteException("La TV a fermé la connexion (appairage à refaire).")
    }

    @Volatile private var lastWrite = 0L
    private val heldKeys = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    /** Relâche toutes les touches maintenues (mode souris) : jamais de touche « coincée » sur la TV. */
    fun releaseAllHeld() {
        val keys = synchronized(heldKeys) { heldKeys.toList().also { heldKeys.clear() } }
        if (keys.isEmpty()) return
        // hors du fil principal (accès réseau)
        Thread { keys.forEach { code -> runCatching { write(ProtoWriter().message(10) { varint(1, code); varint(2, 2) }) } } }.start()
    }

    private fun write(w: ProtoWriter) = synchronized(writeLock) {
        // au plus ~40 commandes par seconde : la box ne doit jamais être submergée
        val wait = 25 - (android.os.SystemClock.uptimeMillis() - lastWrite)
        if (wait > 0) Thread.sleep(wait)
        lastWrite = android.os.SystemClock.uptimeMillis()
        val bytes = w.toByteArray()
        val s = socket ?: run { DiagLog.log("ATV→", "NON ENVOYÉ (pas connecté) " + DiagLog.proto(bytes)); throw RemoteException("Non connecté") }
        DiagLog.log("ATV→", DiagLog.proto(bytes))
        Proto.writeDelimited(s.outputStream, bytes)
    }

    /** Touche maintenue (mode souris) : appui long tenu jusqu'au relâchement, comme une vraie télécommande. */
    suspend fun hold(key: RemoteKey, down: Boolean): Boolean = withContext(Dispatchers.IO) {
        val code = keycodes[key] ?: return@withContext false
        try {
            if (down) heldKeys += code else heldKeys -= code
            write(ProtoWriter().message(10) { varint(1, code); varint(2, if (down) 1 else 2) })  // START_LONG / END_LONG
        } catch (e: Exception) {
            close()
            throw RemoteException("Connexion perdue avec l'Android TV, reconnexion…", e)
        }
        true
    }

    @Volatile private var powerAllowedUntil = 0L

    /** À appeler UNIQUEMENT juste après un appui de l'utilisateur sur le bouton marche/arrêt. */
    fun allowPowerOnce() { powerAllowedUntil = android.os.SystemClock.uptimeMillis() + 3_000 }

    override suspend fun send(key: RemoteKey): Boolean = withContext(Dispatchers.IO) {
        if (key == POWER) {
            // Verrou : MARCHE/ARRÊT n'est jamais envoyé sans appui explicite de l'utilisateur.
            if (android.os.SystemClock.uptimeMillis() > powerAllowedUntil) {
                DiagLog.log("BLOQUÉ", "Touche MARCHE/ARRÊT refusée (pas d'appui utilisateur). Origine :\n" +
                    Throwable().stackTrace.take(14).joinToString("\n") { "    at $it" })
                return@withContext false
            }
            powerAllowedUntil = 0L
            DiagLog.log("ATV", "MARCHE/ARRÊT demandé par l'utilisateur")
        }
        val code = keycodes[key] ?: return@withContext false
        try {
            write(ProtoWriter().message(10) { varint(1, code); varint(2, 3) })  // direction SHORT
        } catch (e: Exception) {
            close()
            throw RemoteException("Connexion perdue avec l'Android TV, reconnexion…", e)
        }
        true
    }

    /** Lance une appli par son lien (ex. https://www.youtube.com, https://www.netflix.com/title). */
    suspend fun launchApp(link: String) = withContext(Dispatchers.IO) {
        try { write(ProtoWriter().message(90) { string(1, link) }) } catch (e: Exception) {
            close(); throw RemoteException("Connexion perdue avec l'Android TV", e)
        }
    }

    /** Tape du texte dans le champ actif de la TV (recherche, identifiants…). */
    suspend fun sendText(text: String) = withContext(Dispatchers.IO) {
        try {
            write(ProtoWriter().message(21) {
                varint(1, imeCounter); varint(2, imeFieldCounter)
                message(3) { varint(1, 1); message(2) { varint(1, text.length); varint(2, text.length); string(3, text) } }
            })
        } catch (e: Exception) {
            close(); throw RemoteException("Connexion perdue avec l'Android TV", e)
        }
    }

    val isConnected get() = socket?.isClosed == false

    /**
     * Équivalent de « tout sélectionner + coller » : le champ de la TV prend exactement [text].
     * 1) remplacement direct (la TV synchronise tout le champ) ; 2) si la TV n'a pas suivi,
     * effacement caractère par caractère puis saisie. Renvoie true si la TV confirme le contenu.
     */
    suspend fun setFieldText(text: String): Boolean {
        sendText(text)
        _fieldText.value.let { if (it == text) return true }
        val confirmed = withTimeoutOrNull(900) { fieldText.first { it == text } } != null
        if (confirmed) return true
        replaceFieldText(text, validate = false)
        return withTimeoutOrNull(900) { fieldText.first { it == text } } != null || _fieldText.value.isEmpty()
    }

    /**
     * Saisie touche par touche, comme un clavier physique, pour les box qui n'acceptent pas
     * la saisie de texte directe. Majuscules et « : » via MAJ maintenue puis toujours relâchée.
     * Caractères pris en charge : lettres, chiffres, . / : - _ ? = & et espace.
     */
    suspend fun typeKeys(text: String, onProgress: (Int) -> Unit = {}) {
        DiagLog.log("ATV", "Saisie touche par touche de ${text.length} caractères")
        text.forEachIndexed { i, ch ->
            val (code, shift) = when {
                ch in 'a'..'z' -> 29 + (ch - 'a') to false
                ch in 'A'..'Z' -> 29 + (ch - 'A') to true
                ch in '0'..'9' -> 7 + (ch - '0') to false
                ch == '.' -> 56 to false
                ch == '/' -> 76 to false
                ch == ':' -> 74 to true
                ch == '-' -> 69 to false
                ch == '_' -> 69 to true
                ch == '?' -> 76 to true
                ch == '=' -> 70 to false
                ch == '&' -> 14 to true
                ch == ' ' -> 62 to false
                else -> return@forEachIndexed
            }
            if (shift) {
                withContext(Dispatchers.IO) { synchronized(heldKeys) { heldKeys += 59 }; write(ProtoWriter().message(10) { varint(1, 59); varint(2, 1) }) }
                kotlinx.coroutines.delay(60)
                sendKeyCode(code)
                kotlinx.coroutines.delay(60)
                withContext(Dispatchers.IO) { write(ProtoWriter().message(10) { varint(1, 59); varint(2, 2) }); synchronized(heldKeys) { heldKeys -= 59 } }
            } else sendKeyCode(code)
            onProgress(i + 1)
            kotlinx.coroutines.delay(90)
        }
    }

    /** Valide la saisie (touche Entrée du clavier de la TV). */
    suspend fun submitField() { sendKeyCode(66); imeActive = false }

    /**
     * Remplace le contenu du champ de saisie actif par [text] : fin du champ, effacement
     * caractère par caractère (longueur signalée par la TV), saisie, puis validation.
     * [onStep] reçoit 1 = effacé, 2 = saisi, 3 = validé.
     */
    suspend fun replaceFieldText(text: String, validate: Boolean, onStep: (Int) -> Unit = {}) {
        // Sécurité : on n'efface QUE si un champ de saisie est ouvert sur la TV, et seulement
        // le nombre de caractères qu'elle a signalé (au plus 120), à un rythme raisonnable.
        if (!imeActive) { onStep(0); return }
        val n = _fieldText.value.length.coerceAtMost(120)
        if (n > 0) {
            sendKeyCode(123)                                  // KEYCODE_MOVE_END
            kotlinx.coroutines.delay(120)
            repeat(n) { sendKeyCode(67); kotlinx.coroutines.delay(35) }   // KEYCODE_DEL
            kotlinx.coroutines.delay(250)
        }
        onStep(1)
        sendText(text)
        kotlinx.coroutines.delay(400)
        onStep(2)
        if (validate) { sendKeyCode(66); onStep(3) }
    }

    /** Envoie un code de touche Android brut (ex. 84 = recherche). */
    suspend fun sendKeyCode(code: Int) = withContext(Dispatchers.IO) {
        try { write(ProtoWriter().message(10) { varint(1, code); varint(2, 3) }) } catch (e: Exception) {
            close(); throw RemoteException("Connexion perdue avec l'Android TV", e)
        }
    }

    // ---------- Voix : le micro du téléphone remplace celui de la télécommande ----------

    /** Ouvre une session vocale (la TV affiche « Parlez maintenant ») ; null si la box refuse. */
    suspend fun startVoice(): Long? = withContext(Dispatchers.IO) {
        val waiter = CompletableDeferred<Long>()
        voiceBegin = waiter
        try {
            write(ProtoWriter().message(10) { varint(1, 84); varint(2, 3) })   // KEYCODE_SEARCH
            val id = withTimeoutOrNull(3000) { waiter.await() } ?: return@withContext null
            write(ProtoWriter().message(30) { varint(1, id) })
            id
        } catch (e: Exception) {
            close(); throw RemoteException("Connexion perdue avec l'Android TV", e)
        } finally {
            voiceBegin = null
        }
    }

    /** Envoie du PCM 16 bits mono 8 kHz (blocs de 8 à 20 Ko, exigence des box). */
    suspend fun sendVoice(sessionId: Long, pcm: ByteArray) = withContext(Dispatchers.IO) {
        var i = 0
        while (i < pcm.size) {
            var part = pcm.copyOfRange(i, minOf(pcm.size, i + 20 * 1024))
            if (part.size < 8 * 1024) part = part.copyOf(8 * 1024)
            write(ProtoWriter().message(31) { varint(1, sessionId); bytes(2, part) })
            i += 20 * 1024
        }
    }

    suspend fun endVoice(sessionId: Long) = withContext(Dispatchers.IO) {
        runCatching { write(ProtoWriter().message(32) { varint(1, sessionId) }) }
    }

    /** Mise en veille seule (KEYCODE_SLEEP) : contrairement à POWER, ne rallume jamais la box. */
    suspend fun sleep() = sendKeyCode(223)

    override suspend fun powerOn(): Boolean =
        runCatching { send(POWER) }.getOrDefault(false) || super.powerOn()

    override fun close() {
        DiagLog.log("ATV", "Fermeture de la connexion par le téléphone")
        if (socket?.isClosed == false) releaseAllHeld()
        scope?.cancel(); scope = null
        runCatching { socket?.close() }; socket = null
    }
}

/** Calcul du secret d'appairage (SHA-256 des clés publiques + code), testable hors Android. */
object AtvSecret {
    private fun unsigned(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    fun compute(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray? =
        compute(client.modulus, client.publicExponent, server.modulus, server.publicExponent, code)

    fun compute(cm: BigInteger, ce: BigInteger, sm: BigInteger, se: BigInteger, code: String): ByteArray? {
        if (code.length != 6 || !code.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return null
        val md = MessageDigest.getInstance("SHA-256")
        md.update(unsigned(cm)); md.update(unsigned(ce)); md.update(unsigned(sm)); md.update(unsigned(se))
        md.update(ByteArray(2) { code.substring(2 + it * 2, 4 + it * 2).toInt(16).toByte() })
        val hash = md.digest()
        return if ((hash[0].toInt() and 0xFF) == code.substring(0, 2).toInt(16)) hash else null
    }
}
