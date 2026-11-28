package fr.paul.telecommande.youtube

import fr.paul.telecommande.core.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client du protocole « Lounge » de YouTube : c'est celui qu'utilise l'appli YouTube du téléphone
 * quand on l'associe à une TV. Il permet de suivre la lecture (vidéo, position, publicités)
 * et d'envoyer des commandes (passer l'annonce, aller à un instant, vitesse…).
 * Fonctionne via Internet : le téléphone n'a pas besoin d'être sur le même Wi-Fi.
 */
class LoungeClient(private val deviceId: String, private val name: String = "Entente") {
    var screenId: String? = null
    var loungeToken: String? = null
    var screenName: String? = null
        private set

    private var sid: String? = null
    private var gsession: String? = null
    private var aid: Long = 0
    private var ofs = 1

    val linked get() = screenId != null && loungeToken != null
    val connected get() = sid != null && gsession != null

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Le flux d'événements reste ouvert : YouTube envoie un « noop » au moins toutes les 30 s.
    private val streamHttp = http.newBuilder().readTimeout(75, TimeUnit.SECONDS).build()

    private suspend fun postForm(url: HttpUrl, form: Map<String, String>, client: OkHttpClient = http): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
            try {
                client.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
                    r.code to (r.body?.string().orEmpty())
                }
            } catch (e: Exception) {
                throw RemoteException("YouTube injoignable : ${e.message}", e)
            }
        }

    /** Association avec le code affiché par l'appli YouTube de la TV (Paramètres > Associer avec un code TV). */
    suspend fun pairWithCode(code: String): Boolean {
        val digits = code.filter { it.isDigit() }
        if (digits.length < 9) throw RemoteException("Le code TV doit contenir 12 chiffres.")
        val (status, text) = postForm("$API/pairing/get_screen".toHttpUrl(), mapOf("pairing_code" to digits))
        if (status != 200) throw RemoteException("Code TV refusé par YouTube (HTTP $status).")
        val screen = JSONObject(text).optJSONObject("screen") ?: throw RemoteException("Code TV inconnu ou expiré.")
        screenId = screen.getString("screenId")
        loungeToken = screen.getString("loungeToken")
        screenName = screen.optString("name")
        return linked
    }

    /** Renouvelle le jeton à partir de l'identifiant d'écran mémorisé. */
    suspend fun refreshToken(): Boolean {
        val id = screenId ?: return false
        val (status, text) = postForm("$API/pairing/get_lounge_token_batch".toHttpUrl(), mapOf("screen_ids" to id))
        if (status != 200) return false
        val screen = JSONObject(text).optJSONArray("screens")?.optJSONObject(0) ?: return false
        loungeToken = screen.optString("loungeToken").ifBlank { null }
        return linked
    }

    /** Ouvre une session ; renvoie false si le jeton a expiré. */
    suspend fun connect(onEvent: (String, JSONObject?) -> Unit): Boolean {
        val token = loungeToken ?: return false
        val url = "$API/bc/bind?RID=1&VER=8&CVER=1&auth_failure_option=send_error".toHttpUrl()
        val (status, text) = postForm(url, mapOf(
            "app" to "web", "mdx-version" to "3", "name" to name, "id" to deviceId,
            "device" to "REMOTE_CONTROL", "capabilities" to "que,dsdtr,atp,vsp", "magnaKey" to "cloudPairedDevice",
            "ui" to "false", "deviceContext" to "user_agent=dunno&window_width_points=&window_height_points=&os_name=android&ms=",
            "theme" to "cl", "loungeIdToken" to token
        ))
        if (status == 401) { loungeToken = null; return false }
        if (status != 200) return false
        val parser = LoungeChunkParser()
        text.lines().forEach { line -> parser.feed(line)?.let { handleBatch(it, onEvent) } }
        ofs = 1
        return connected
    }

    private fun common(): Map<String, String> = mapOf(
        "name" to name, "loungeIdToken" to (loungeToken ?: ""), "SID" to (sid ?: ""), "AID" to aid.toString(),
        "gsessionid" to (gsession ?: ""), "device" to "REMOTE_CONTROL", "app" to "youtube-desktop", "VER" to "8", "v" to "2"
    )

    private fun bindUrl(extra: Map<String, String>): HttpUrl =
        "$API/bc/bind".toHttpUrl().newBuilder().apply { (common() + extra).forEach { (k, v) -> addQueryParameter(k, v) } }.build()

    /**
     * Écoute les événements jusqu'à la fermeture du flux (fin de session, coupure réseau, délai).
     * Renvoie normalement ; l'appelant se reconnecte.
     */
    suspend fun subscribe(onEvent: (String, JSONObject?) -> Unit) = withContext(Dispatchers.IO) {
        if (!connected) return@withContext
        val url = bindUrl(mapOf("RID" to "rpc", "CI" to "0", "TYPE" to "xmlhttp"))
        try {
            val call = streamHttp.newCall(Request.Builder().url(url).get().build())
            activeCall = call
            call.execute().use { r ->
                if (r.code == 400 || r.code == 410) { lost(); return@use }
                if (r.code == 401) { lost(); loungeToken = null; return@use }
                if (!r.isSuccessful) { lost(); return@use }
                val source = r.body?.source() ?: return@use
                val parser = LoungeChunkParser()
                while (connected) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    parser.feed(line)?.let { handleBatch(it, onEvent) }
                }
            }
        } catch (e: java.io.IOException) {
            // délai ou coupure : on laisse l'appelant relancer
        } finally {
            activeCall = null
        }
    }

    @Volatile private var activeCall: okhttp3.Call? = null

    /** Coupe immédiatement le flux en cours (arrêt de l'assistant). */
    fun close() { runCatching { activeCall?.cancel() }; lost() }

    private fun lost() { sid = null; gsession = null; aid = 0 }

    private fun handleBatch(events: JSONArray, onEvent: (String, JSONObject?) -> Unit) {
        for (i in 0 until events.length()) {
            val ev = events.optJSONArray(i) ?: continue
            val id = ev.optLong(0)
            val payload = ev.optJSONArray(1) ?: continue
            when (val type = payload.optString(0)) {
                "c" -> sid = payload.optString(1)
                "S" -> gsession = payload.optString(1)
                "loungeStatus" -> {
                    val data = payload.optJSONObject(1)
                    val devices = runCatching { JSONArray(data?.optString("devices") ?: "[]") }.getOrNull()
                    if (devices != null) for (d in 0 until devices.length()) {
                        val dev = devices.optJSONObject(d) ?: continue
                        if (dev.optString("type") == "LOUNGE_SCREEN") screenName = dev.optString("name", screenName ?: "")
                    }
                    onEvent(type, data)
                }
                "loungeScreenDisconnected" -> { lost(); onEvent(type, payload.optJSONObject(1)) }
                "noop" -> onEvent(type, null)
                else -> onEvent(type, payload.optJSONObject(1))
            }
            aid = maxOf(aid, id)
        }
    }

    /** Envoie une commande ; renvoie false si la session est perdue. */
    suspend fun command(cmd: String, params: Map<String, String> = emptyMap()): Boolean {
        if (!connected) return false
        val form = linkedMapOf("count" to "1", "ofs" to ofs.toString(), "req0__sc" to cmd)
        params.forEach { (k, v) -> form["req0_$k"] = v }
        ofs++
        val (status, text) = postForm(bindUrl(mapOf("RID" to ofs.toString())), form)
        if (status == 400 || status == 410 || (status == 401 && text.contains("Expired"))) { lost(); return false }
        return status in 200..299
    }

    companion object {
        const val API = "https://www.youtube.com/api/lounge"
    }
}

/**
 * Le flux Lounge est découpé en blocs : une ligne « taille » suivie d'un tableau JSON
 * pouvant s'étendre sur plusieurs lignes.
 */
class LoungeChunkParser {
    private var remaining = 0
    private val buf = StringBuilder()

    fun feed(line: String): JSONArray? {
        if (buf.isEmpty() && remaining <= 0) {
            remaining = line.trim().toIntOrNull() ?: 0
            return null
        }
        buf.append(line).append('\n')
        remaining -= line.length + 1
        val trimmed = line.trimEnd()
        if (remaining <= 0 || trimmed.endsWith("]")) {
            val parsed = runCatching { JSONArray(buf.toString()) }.getOrNull()
            if (parsed != null || remaining <= 0) {
                buf.setLength(0); remaining = 0
                return parsed
            }
        }
        return null
    }
}
