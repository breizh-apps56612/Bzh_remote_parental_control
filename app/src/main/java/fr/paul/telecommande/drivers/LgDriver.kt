package fr.paul.telecommande.drivers

import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.RemoteException
import fr.paul.telecommande.core.RemoteKey
import fr.paul.telecommande.core.RemoteKey.*
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * LG webOS : WebSocket (wss 3001 sur les modèles récents, ws 3000 sur les anciens),
 * enregistrement avec « client-key » puis socket « pointer » pour envoyer les touches.
 */
class LgDriver(device: Device) : Driver(device) {
    private var main: WsSession? = null
    private var pointer: WsSession? = null
    private var reqId = 0
    private var playing = false

    private val buttons = mapOf(
        VOL_UP to "VOLUMEUP", VOL_DOWN to "VOLUMEDOWN", MUTE to "MUTE", CH_UP to "CHANNELUP", CH_DOWN to "CHANNELDOWN",
        UP to "UP", DOWN to "DOWN", LEFT to "LEFT", RIGHT to "RIGHT", OK to "ENTER", BACK to "BACK",
        HOME to "HOME", MENU to "MENU", INFO to "INFO", EXIT to "EXIT",
        STOP to "STOP", REWIND to "REWIND", FAST_FORWARD to "FASTFORWARD",
        RED to "RED", GREEN to "GREEN", YELLOW to "YELLOW", BLUE to "BLUE"
    ) + RemoteKey.DIGITS.mapIndexed { i, k -> k to "$i" }

    override suspend fun connect(ui: PairingUi) {
        if (main?.closed == false && pointer?.closed == false) return
        close()
        val bases = listOf("wss://${device.host}:3001", "ws://${device.host}:3000")
        var last: Exception? = null
        for (base in bases) {
            try {
                val s = WsSession.open(base)
                register(s, ui, signed = true)
                main = s
                openPointer(s)
                return
            } catch (e: Exception) {
                last = e
                main?.close(); main = null
            }
        }
        throw RemoteException("TV LG injoignable : ${last?.message}", last)
    }

    private suspend fun register(s: WsSession, ui: PairingUi, signed: Boolean) {
        val payload = LgPairing.payload(signed)
        if (device.token.isNotBlank()) payload.put("client-key", device.token)
        s.send(JSONObject().put("type", "register").put("id", "register_0").put("payload", payload).toString())
        try {
            while (true) {
                val msg = JSONObject(s.receive(60_000))
                when (msg.optString("type")) {
                    "registered" -> {
                        val key = msg.optJSONObject("payload")?.optString("client-key").orEmpty()
                        if (key.isNotBlank() && key != device.token) { device.token = key; persist() }
                        return
                    }
                    "response" -> if (msg.optJSONObject("payload")?.optString("pairingType") == "PROMPT") {
                        ui.info("Acceptez la demande sur l'écran de la TV LG…")
                    }
                    "error" -> {
                        val err = msg.optString("error")
                        if (signed && err.contains("blacklisted", true)) return register(s, ui, signed = false)
                        if (device.token.isNotBlank() && err.contains("401")) {
                            device.token = ""; persist(); return register(s, ui, signed)
                        }
                        throw RemoteException("La TV LG a refusé : $err")
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw RemoteException("Demande non acceptée sur la TV LG.", e)
        }
    }

    private suspend fun openPointer(s: WsSession) {
        val id = "pointer_${++reqId}"
        s.send(JSONObject().put("type", "request").put("id", id)
            .put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket").toString())
        while (true) {
            val msg = JSONObject(s.receive(8_000))
            if (msg.optString("id") != id) continue
            val path = msg.optJSONObject("payload")?.optString("socketPath").orEmpty()
            if (path.isBlank()) throw RemoteException("La TV LG n'a pas ouvert le canal des touches.")
            pointer = WsSession.open(path)
            return
        }
    }

    private fun request(uri: String, payload: JSONObject? = null): Boolean {
        val m = JSONObject().put("type", "request").put("id", "req_${++reqId}").put("uri", uri)
        if (payload != null) m.put("payload", payload)
        return main?.send(m.toString()) == true
    }

    override suspend fun send(key: RemoteKey): Boolean {
        val ok = when (key) {
            POWER -> request("ssap://system/turnOff")
            INPUT -> request("ssap://system.launcher/launch", JSONObject().put("id", "com.webos.app.inputpicker"))
            PLAY_PAUSE -> {
                playing = !playing
                request(if (playing) "ssap://media.controls/play" else "ssap://media.controls/pause")
            }
            else -> {
                val name = buttons[key] ?: return false
                pointer?.send("type:button\nname:$name\n\n") == true
            }
        }
        if (!ok) { close(); throw RemoteException("Connexion perdue avec la TV LG, reconnexion…") }
        return true
    }

    override fun close() {
        pointer?.close(); pointer = null
        main?.close(); main = null
    }
}

/** Manifeste d'appairage webOS (permissions standard des télécommandes réseau). */
object LgPairing {
    private val PERMISSIONS = listOf(
        "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "TEST_OPEN", "TEST_PROTECTED", "CONTROL_AUDIO",
        "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_MEDIA_PLAYBACK",
        "CONTROL_INPUT_TV", "CONTROL_POWER", "READ_APP_STATUS", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST",
        "READ_NETWORK_STATE", "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST",
        "READ_POWER_STATE", "READ_COUNTRY_INFO", "READ_SETTINGS", "CONTROL_TV_SCREEN", "CONTROL_TV_STANBY",
        "CONTROL_FAVORITE_GROUP", "CONTROL_USER_INFO", "CHECK_BLUETOOTH_DEVICE", "CONTROL_BLUETOOTH",
        "CONTROL_TIMER_INFO", "STB_INTERNAL_CONNECTION", "CONTROL_RECORDING", "READ_RECORDING_STATE",
        "WRITE_RECORDING_LIST", "READ_RECORDING_LIST", "READ_RECORDING_SCHEDULE", "WRITE_RECORDING_SCHEDULE",
        "READ_STORAGE_DEVICE_LIST", "READ_TV_PROGRAM_INFO", "CONTROL_BOX_CHANNEL", "READ_TV_ACR_AUTH_TOKEN",
        "READ_TV_CONTENT_STATE", "READ_TV_CURRENT_TIME", "ADD_LAUNCHER_CHANNEL", "SET_CHANNEL_SKIP",
        "RELEASE_CHANNEL_SKIP", "CONTROL_CHANNEL_BLOCK", "DELETE_SELECT_CHANNEL", "CONTROL_CHANNEL_GROUP",
        "SCAN_TV_CHANNELS", "CONTROL_TV_POWER", "CONTROL_WOL"
    )
    private val SIGNED_PERMISSIONS = listOf(
        "TEST_SECURE", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS", "READ_LGE_SDX",
        "READ_NOTIFICATIONS", "SEARCH", "WRITE_SETTINGS", "WRITE_NOTIFICATION_ALERT", "CONTROL_POWER",
        "READ_CURRENT_CHANNEL", "READ_RUNNING_APPS", "READ_UPDATE_INFO", "UPDATE_FROM_REMOTE_APP",
        "READ_LGE_TV_INPUT_EVENTS", "READ_TV_CURRENT_TIME"
    )
    private const val SIGNATURE =
        "eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbmctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9." +
        "hrVRgjCwXVvE2OOSpDZ58hR+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRyaMOv5zWSrthlf7G128q" +
        "vIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1" +
        "n50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM2DXzdKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAw" +
        "aCViZNCP8iC9aO0q9fQojoa7NQnAtw=="

    fun payload(signed: Boolean): JSONObject {
        val manifest = JSONObject().put("manifestVersion", 1)
        if (signed) {
            manifest.put("appVersion", "1.1")
            manifest.put("signed", JSONObject()
                .put("appId", "com.lge.test").put("created", "20140509")
                .put("localizedAppNames", JSONObject().put("", "LG Remote App"))
                .put("localizedVendorNames", JSONObject().put("", "LG Electronics"))
                .put("permissions", JSONArray(SIGNED_PERMISSIONS))
                .put("serial", "2f930e2d2cfe083771f68e4fe7bb07").put("vendorId", "com.lge"))
            manifest.put("signatures", JSONArray().put(JSONObject().put("signature", SIGNATURE).put("signatureVersion", 1)))
            manifest.put("permissions", JSONArray(PERMISSIONS))
        } else {
            manifest.put("appVersion", "1.0")
            manifest.put("permissions", JSONArray(PERMISSIONS + listOf("CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD")))
        }
        return JSONObject().put("forcePairing", false).put("pairingType", "PROMPT").put("manifest", manifest)
    }
}
