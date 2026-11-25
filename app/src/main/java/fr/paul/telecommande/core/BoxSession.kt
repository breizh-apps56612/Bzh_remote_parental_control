package fr.paul.telecommande.core

import android.content.Context
import fr.paul.telecommande.shared.BoxProtocol
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Connexion permanente et chiffrée vers l'appli Box, pour ce qui doit être instantané :
 * souris (mouvements regroupés ~60 fois par seconde), saisie de texte en direct, clics.
 * Les commandes partent dans l'ordre ; la connexion se rouvre seule si elle tombe.
 */
class BoxSession private constructor(private val context: Context, private val device: Device) {
    private val queue = LinkedBlockingQueue<JSONObject>(200)
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var closed = false
    private var pdx = 0f; private var pdy = 0f
    private val lock = Object()
    @Volatile var lastError: String? = null; private set

    init {
        thread(name = "box-session", isDaemon = true) { loop() }
        thread(name = "box-pointer", isDaemon = true) { pointerPump() }
    }

    private fun loop() {
        while (!closed) {
            val msg = runCatching { queue.poll(30, TimeUnit.SECONDS) }.getOrNull() ?: continue
            for (attempt in 0..1) {
                try {
                    val s = socket ?: openSocket()
                    BoxProtocol.write(s.outputStream, msg)
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e.message
                    runCatching { socket?.close() }; socket = null
                    if (attempt == 1) DiagLog.log("BOX", "Commande ${msg.optString("cmd")} non envoyée : ${e.message}")
                }
            }
        }
        runCatching { socket?.close() }
    }

    private fun openSocket(): SSLSocket {
        val (s, _) = BoxClient.of(context, device).open(4000)
        s.soTimeout = 0
        socket = s
        // lecteur des réponses : on note les refus (champ introuvable…) pour les afficher
        thread(isDaemon = true) {
            val r = BoxProtocol.LineReader(s.inputStream)
            while (true) {
                val res = runCatching { r.next() }.getOrNull() ?: break
                if (!res.optBoolean("ok", true)) { lastError = res.optString("error"); onError?.invoke(lastError ?: "") }
            }
        }
        return s
    }

    var onError: ((String) -> Unit)? = null

    fun send(cmd: String, params: JSONObject = JSONObject()) {
        queue.offer(JSONObject(params.toString()).put("cmd", cmd).put("id", "s"))
    }

    /** Déplacement de la flèche (pixels d'écran TV) — regroupé pour ne pas inonder la box. */
    fun move(dx: Float, dy: Float) = synchronized(lock) { pdx += dx; pdy += dy; lock.notifyAll() }

    private fun pointerPump() {
        while (!closed) {
            var dx: Float; var dy: Float
            synchronized(lock) {
                while (pdx == 0f && pdy == 0f && !closed) lock.wait(1000)
                dx = pdx; dy = pdy; pdx = 0f; pdy = 0f
            }
            if (dx != 0f || dy != 0f) send("pointer", JSONObject().put("dx", dx.toDouble()).put("dy", dy.toDouble()))
            Thread.sleep(16)
        }
    }

    fun click(long: Boolean = false) = send("pointer_click", JSONObject().put("long", long))
    fun scroll(dy: Float) = send("pointer_scroll", JSONObject().put("dy", dy.toDouble()))
    fun typeText(text: String, submit: Boolean) = send("type_text", JSONObject().put("text", text).put("submit", submit))

    fun close() { closed = true; synchronized(lock) { lock.notifyAll() }; runCatching { socket?.close() } }

    companion object {
        private val sessions = HashMap<String, BoxSession>()
        @Synchronized fun of(c: Context, d: Device): BoxSession = sessions.getOrPut(d.id) { BoxSession(c.applicationContext, d) }
        @Synchronized fun release(id: String) { sessions.remove(id)?.close() }
    }
}
