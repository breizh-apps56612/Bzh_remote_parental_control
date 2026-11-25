package fr.paul.telecommande.drivers

import android.util.Base64
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.RemoteException
import fr.paul.telecommande.core.RemoteKey
import fr.paul.telecommande.core.RemoteKey.*
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Samsung Tizen (2016+) : WebSocket sécurisé sur 8002, repli sur 8001 pour 2016-2017.
 * Au premier appairage, la TV demande d'autoriser l'appareil ; le jeton reçu est sauvegardé.
 */
class SamsungDriver(device: Device) : Driver(device) {
    private var ws: WsSession? = null
    private var playing = false

    private val keys = mapOf(
        POWER to "KEY_POWER", VOL_UP to "KEY_VOLUP", VOL_DOWN to "KEY_VOLDOWN", MUTE to "KEY_MUTE",
        CH_UP to "KEY_CHUP", CH_DOWN to "KEY_CHDOWN", UP to "KEY_UP", DOWN to "KEY_DOWN",
        LEFT to "KEY_LEFT", RIGHT to "KEY_RIGHT", OK to "KEY_ENTER", BACK to "KEY_RETURN",
        HOME to "KEY_HOME", MENU to "KEY_MENU", INFO to "KEY_INFO", INPUT to "KEY_SOURCE", EXIT to "KEY_EXIT",
        STOP to "KEY_STOP", REWIND to "KEY_REWIND", FAST_FORWARD to "KEY_FF",
        RED to "KEY_RED", GREEN to "KEY_GREEN", YELLOW to "KEY_YELLOW", BLUE to "KEY_CYAN"
    ) + RemoteKey.DIGITS.mapIndexed { i, k -> k to "KEY_$i" }

    override suspend fun connect(ui: PairingUi) {
        if (ws?.closed == false) return
        val name = Base64.encodeToString("Telecommande Android".toByteArray(), Base64.NO_WRAP)
        val enc = URLEncoder.encode(name, "UTF-8")
        val tokenPart = if (device.token.isNotBlank()) "&token=${device.token}" else ""
        val urls = listOf(
            "wss://${device.host}:8002/api/v2/channels/samsung.remote.control?name=$enc$tokenPart",
            "ws://${device.host}:8001/api/v2/channels/samsung.remote.control?name=$enc"
        )
        var last: Exception? = null
        for (url in urls) {
            try {
                val s = WsSession.open(url)
                if (device.token.isBlank()) ui.info("Acceptez la connexion sur l'écran de la TV Samsung…")
                waitForConnect(s)
                ws = s
                return
            } catch (e: Exception) {
                last = e
            }
        }
        throw RemoteException("TV Samsung injoignable. Vérifiez l'IP et que la TV est allumée. (${last?.message})", last)
    }

    private suspend fun waitForConnect(s: WsSession) {
        try {
            while (true) {
                val msg = JSONObject(s.receive(45_000))
                when (msg.optString("event")) {
                    "ms.channel.connect" -> {
                        val token = msg.optJSONObject("data")?.optString("token").orEmpty()
                        if (token.isNotBlank() && token != device.token) { device.token = token; persist() }
                        return
                    }
                    "ms.channel.unauthorized" -> { s.close(); throw RemoteException("Connexion refusée sur la TV.") }
                    "ms.channel.timeOut" -> { s.close(); throw RemoteException("Délai d'autorisation dépassé sur la TV.") }
                }
            }
        } catch (e: TimeoutCancellationException) {
            s.close(); throw RemoteException("Pas de réponse de la TV (autorisation non validée ?)", e)
        }
    }

    override suspend fun send(key: RemoteKey): Boolean {
        val code = when (key) {
            PLAY_PAUSE -> { playing = !playing; if (playing) "KEY_PLAY" else "KEY_PAUSE" }
            else -> keys[key] ?: return false
        }
        val payload = JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
            .put("Cmd", "Click").put("DataOfCmd", code).put("Option", "false").put("TypeOfRemote", "SendRemoteKey"))
        val s = ws
        if (s == null || s.closed || !s.send(payload.toString())) {
            ws = null
            throw RemoteException("Connexion perdue avec la TV Samsung, reconnexion…")
        }
        return true
    }

    override fun close() { ws?.close(); ws = null }
}
