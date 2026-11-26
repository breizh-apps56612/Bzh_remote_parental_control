package fr.paul.telecommande.drivers

import fr.paul.telecommande.core.DiagLog
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.RemoteException
import fr.paul.telecommande.core.RemoteKey
import fr.paul.telecommande.core.RemoteKey.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TV Smart « Linux » fabriquées par Vestel : Edenwood, Toshiba, Telefunken, Hitachi, JVC, Finlux, Techwood…
 * Pas d'appairage. Selon l'âge de la TV, la touche passe par l'un de ces trois chemins :
 *  - « sc »  : POST http://TV:56789/apps/SmartCenter  <remote><key code=1012 /></remote>  (modèles récents)
 *  - « vr »  : POST http://TV:56789/apps/vr/remote    <remote><key code="1012"/></remote> (modèles ~2015-2018)
 *  - « tcp » : texte « 1012 » + retour à la ligne sur le port TCP 4660 (Toshiba / Vestel)
 * Le chemin qui fait réagir la TV est choisi par le test guidé et mémorisé dans [Device.secret].
 */
class VestelDriver(device: Device) : Driver(device) {

    enum class Method(val id: String, val label: String) {
        SMART_CENTER("sc", "SmartCenter (HTTP)"),
        SMART_CENTER_XML("sx", "SmartCenter (HTTP, XML complet)"),
        VIRTUAL_REMOTE("vr", "télécommande virtuelle (HTTP)"),
        TCP("tcp", "port 4660");

        companion object { fun of(id: String) = values().firstOrNull { it.id == id } }
    }

    private val dialPort get() = if (device.port in 1..65535) device.port else DIAL_PORT

    val method: Method? get() = Method.of(device.secret)

    override suspend fun connect(ui: PairingUi) {
        if (method != null) return
        // Pas encore testé : on prend le premier chemin que la TV expose (le test guidé l'affinera).
        val found = available().firstOrNull()
            ?: throw RemoteException("TV Vestel injoignable à ${device.host}. Vérifiez qu'elle est allumée et connectée au même réseau.")
        DiagLog.log("VESTEL", "Chemin provisoire : ${found.id} (${device.host})")
        device.secret = found.id; persist()
    }

    /** Chemins que la TV accepte au niveau réseau (ne prouve pas qu'elle réagit aux touches). */
    suspend fun available(): List<Method> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Method>()
        if (httpStatus("GET", "/apps/SmartCenter") == 200) { list += Method.SMART_CENTER; list += Method.SMART_CENTER_XML }
        if (portOpen(device.host, dialPort)) list += Method.VIRTUAL_REMOTE
        if (portOpen(device.host, TCP_PORT)) list += Method.TCP
        DiagLog.log("VESTEL", "Chemins ouverts sur ${device.host} : ${list.joinToString { it.id }.ifBlank { "aucun" }}")
        list
    }

    override suspend fun send(key: RemoteKey): Boolean {
        val code = CODES[key] ?: return false
        val m = method ?: Method.SMART_CENTER
        sendCode(m, code)
        return true
    }

    /** Envoie un code brut par un chemin donné (utilisé par le test guidé). */
    suspend fun sendCode(m: Method, code: Int) = withContext(Dispatchers.IO) {
        val result = runCatching {
            when (m) {
                // Format des applis Vestel / Telefunken : XML sans guillemets, sans type de contenu.
                Method.SMART_CENTER -> httpStatus("POST", "/apps/SmartCenter", "<remote><key code=$code/></remote>", null)
                Method.SMART_CENTER_XML -> httpStatus("POST", "/apps/SmartCenter", "<?xml version=\"1.0\" ?><remote><key code=\"$code\" /></remote>", "text/xml; charset=utf-8")
                Method.VIRTUAL_REMOTE -> httpStatus("POST", "/apps/vr/remote", "<?xml version=\"1.0\" ?><remote><key code=\"$code\"/></remote>", "text/xml")
                Method.TCP -> { tcpSend(code); 0 }
            }
        }
        val status = result.getOrNull()
        DiagLog.log("VESTEL", "Touche $code par ${m.id} → " + (result.exceptionOrNull()?.let { "erreur ${it.message}" } ?: if (status == 0) "envoyée" else "HTTP $status"))
        if (result.isFailure || (status != null && status >= 400) || status == -1)
            throw RemoteException("La TV n'a pas accepté la touche (${m.label}).", result.exceptionOrNull())
    }

    /** Ouvre Netflix ou YouTube sur la TV (protocole DIAL). */
    suspend fun launchApp(app: String): Boolean = withContext(Dispatchers.IO) {
        val origin = if (app == "YouTube") "https://www.youtube.com" else null
        val status = runCatching { httpStatus("POST", "/apps/$app", "", "text/plain", origin) }.getOrDefault(-1)
        DiagLog.log("VESTEL", "Ouverture $app (DIAL) → HTTP $status")
        status == 200 || status == 201
    }

    /** La TV reste sur le réseau en veille : la touche Marche la rallume ; sinon Wake-on-LAN. */
    override suspend fun powerOn(): Boolean =
        runCatching { send(POWER) }.getOrDefault(false) || super.powerOn()

    private fun tcpSend(code: Int) {
        Socket().use { s ->
            s.connect(InetSocketAddress(device.host, TCP_PORT), 2000)
            s.soTimeout = 1000
            s.getOutputStream().apply { write("$code\r\n".toByteArray()); flush() }
        }
    }

    /** Renvoie le code HTTP, ou -1 si la TV ne répond pas. */
    private fun httpStatus(method: String, path: String, body: String? = null, type: String? = "text/plain", origin: String? = null): Int {
        val req = Request.Builder().url("http://${device.host}:$dialPort$path").header("Connection", "close")
        origin?.let { req.header("Origin", it) }
        when (method) {
            "POST" -> req.post((body ?: "").toRequestBody(type?.toMediaType()))
            else -> req.get()
        }
        return runCatching { Net.client.newCall(req.build()).execute().use { it.code } }.getOrDefault(-1)
    }

    companion object {
        const val DIAL_PORT = 56789
        const val TCP_PORT = 4660

        /** Codes de l'appli Smart Center de Vestel / Toshiba. */
        val CODES: Map<RemoteKey, Int> = mapOf(
            POWER to 1012, MUTE to 1013, VOL_UP to 1016, VOL_DOWN to 1017, INFO to 1018,
            CH_UP to 1032, CH_DOWN to 1033,
            UP to 1020, DOWN to 1019, LEFT to 1021, RIGHT to 1022, OK to 1053,
            BACK to 1010, EXIT to 1037, HOME to 1046, MENU to 1048, INPUT to 1056, GUIDE to 1047,
            PLAY_PAUSE to 1025, STOP to 1024, REWIND to 1027, FAST_FORWARD to 1028,
            RED to 1055, GREEN to 1054, YELLOW to 1050, BLUE to 1052, LAST_CHANNEL to 1034
        ) + RemoteKey.DIGITS.mapIndexed { i, k -> k to 1000 + i }

        fun portOpen(host: String, port: Int, timeoutMs: Int = 600): Boolean =
            runCatching { Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }; true }.getOrDefault(false)
    }
}
