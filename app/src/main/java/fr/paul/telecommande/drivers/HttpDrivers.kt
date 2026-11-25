package fr.paul.telecommande.drivers

import android.util.Base64
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.RemoteException
import fr.paul.telecommande.core.RemoteKey
import fr.paul.telecommande.core.RemoteKey.*
import org.json.JSONObject
import java.net.URLEncoder

/** Roku (ECP, port 8060) – aucune authentification. */
class RokuDriver(device: Device) : Driver(device) {
    private val keys = mapOf(
        POWER to "Power", VOL_UP to "VolumeUp", VOL_DOWN to "VolumeDown", MUTE to "VolumeMute",
        CH_UP to "ChannelUp", CH_DOWN to "ChannelDown", UP to "Up", DOWN to "Down", LEFT to "Left", RIGHT to "Right",
        OK to "Select", BACK to "Back", HOME to "Home", MENU to "Info", INFO to "Info", INPUT to "InputHDMI1",
        EXIT to "Home", PLAY_PAUSE to "Play", STOP to "Back", REWIND to "Rev", FAST_FORWARD to "Fwd"
    ) + RemoteKey.DIGITS.mapIndexed { i, k -> k to "Lit_$i" }

    override suspend fun connect(ui: PairingUi) { Net.get("http://${device.host}:${device.port}/query/device-info") }

    override suspend fun send(key: RemoteKey): Boolean {
        val k = keys[key] ?: return false
        Net.post("http://${device.host}:${device.port}/keypress/$k", "", "text/plain")
        return true
    }

    override suspend fun powerOn(): Boolean = runCatching {
        Net.post("http://${device.host}:${device.port}/keypress/PowerOn", "", "text/plain"); true
    }.getOrElse { super.powerOn() }
}

/** Sony Bravia : IRCC-IP (SOAP) avec clé pré-partagée (Réglages > Réseau > Contrôle IP > Clé pré-partagée). */
class SonyDriver(device: Device) : Driver(device) {
    private fun ircc(cat: Int, code: Int): String {
        val b = if (cat == 1) byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, code.toByte(), 3)
        else byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0x97.toByte(), 0, 0, 0, code.toByte(), 3)
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }

    private val codes: Map<RemoteKey, Pair<Int, Int>> = mapOf(
        POWER to (1 to 0x2F), VOL_UP to (1 to 0x12), VOL_DOWN to (1 to 0x13), MUTE to (1 to 0x14),
        CH_UP to (1 to 0x10), CH_DOWN to (1 to 0x11), UP to (1 to 0x74), DOWN to (1 to 0x75),
        LEFT to (1 to 0x34), RIGHT to (1 to 0x33), OK to (1 to 0x65), HOME to (1 to 0x60), INPUT to (1 to 0x25),
        INFO to (1 to 0x3A), EXIT to (1 to 0x63), BACK to (2 to 0x23), MENU to (2 to 0x36),
        PLAY_PAUSE to (2 to 0x1A), STOP to (2 to 0x18), REWIND to (2 to 0x1B), FAST_FORWARD to (2 to 0x1C),
        RED to (2 to 0x25), GREEN to (2 to 0x26), YELLOW to (2 to 0x27), BLUE to (2 to 0x24),
        D0 to (1 to 0x09)
    ) + (1..9).associate { RemoteKey.digit(it) to (1 to it - 1) }

    override suspend fun connect(ui: PairingUi) {
        if (device.secret.isBlank()) {
            val psk = ui.askCode("Clé Sony (PSK)",
                "Sur la TV : Réglages > Réseau > Configuration du réseau domestique > Contrôle IP > Authentification « Normal et clé pré-partagée ». Saisissez la clé choisie.",
                "ex. 0000") ?: throw RemoteException("Clé PSK requise")
            device.secret = psk.trim(); persist()
        }
    }

    override suspend fun send(key: RemoteKey): Boolean {
        val (cat, code) = codes[key] ?: return false
        val body = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1"><IRCCCode>${ircc(cat, code)}</IRCCCode></u:X_SendIRCC></s:Body></s:Envelope>"""
        try {
            Net.post("http://${device.host}/sony/IRCC", body, "text/xml; charset=UTF-8", mapOf(
                "SOAPACTION" to "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"",
                "X-Auth-PSK" to device.secret
            ))
        } catch (e: RemoteException) {
            if (e.message?.contains("403") == true) { device.secret = ""; persist(); throw RemoteException("Clé PSK refusée, elle sera redemandée.") }
            throw e
        }
        return true
    }
}

/** Philips JointSpace v1 (TV non-Android, port 1925). Les Philips Android TV utilisent le type « Android TV ». */
class PhilipsDriver(device: Device) : Driver(device) {
    private val keys = mapOf(
        POWER to "Standby", VOL_UP to "VolumeUp", VOL_DOWN to "VolumeDown", MUTE to "Mute",
        CH_UP to "ChannelStepUp", CH_DOWN to "ChannelStepDown", UP to "CursorUp", DOWN to "CursorDown",
        LEFT to "CursorLeft", RIGHT to "CursorRight", OK to "Confirm", BACK to "Back", HOME to "Home",
        MENU to "Options", INFO to "Info", INPUT to "Source", EXIT to "Back", PLAY_PAUSE to "PlayPause",
        STOP to "Stop", REWIND to "Rewind", FAST_FORWARD to "FastForward",
        RED to "RedColour", GREEN to "GreenColour", YELLOW to "YellowColour", BLUE to "BlueColour"
    ) + RemoteKey.DIGITS.mapIndexed { i, k -> k to "Digit$i" }

    override suspend fun connect(ui: PairingUi) { Net.get("http://${device.host}:${device.port}/1/system") }

    override suspend fun send(key: RemoteKey): Boolean {
        val k = keys[key] ?: return false
        Net.post("http://${device.host}:${device.port}/1/input/key", JSONObject().put("key", k).toString(), "application/json")
        return true
    }
}

/** Panasonic Viera (2011-2018, port 55000, SOAP non chiffré). */
class PanasonicDriver(device: Device) : Driver(device) {
    private val keys = mapOf(
        POWER to "NRC_POWER", VOL_UP to "NRC_VOLUP", VOL_DOWN to "NRC_VOLDOWN", MUTE to "NRC_MUTE",
        CH_UP to "NRC_CH_UP", CH_DOWN to "NRC_CH_DOWN", UP to "NRC_UP", DOWN to "NRC_DOWN",
        LEFT to "NRC_LEFT", RIGHT to "NRC_RIGHT", OK to "NRC_ENTER", BACK to "NRC_RETURN", HOME to "NRC_HOME",
        MENU to "NRC_MENU", INFO to "NRC_INFO", INPUT to "NRC_CHG_INPUT", EXIT to "NRC_CANCEL",
        PLAY_PAUSE to "NRC_PLAY", STOP to "NRC_STOP", REWIND to "NRC_REW", FAST_FORWARD to "NRC_FF",
        RED to "NRC_RED", GREEN to "NRC_GREEN", YELLOW to "NRC_YELLOW", BLUE to "NRC_BLUE"
    ) + RemoteKey.DIGITS.mapIndexed { i, k -> k to "NRC_D$i" }

    override suspend fun send(key: RemoteKey): Boolean {
        val k = keys[key] ?: return false
        val body = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:X_SendKey xmlns:u="urn:panasonic-com:service:p00NetworkControl:1"><X_KeyEvent>$k-ONOFF</X_KeyEvent></u:X_SendKey></s:Body></s:Envelope>"""
        Net.post("http://${device.host}:${device.port}/nrc/control_0", body, "text/xml; charset=\"utf-8\"",
            mapOf("SOAPACTION" to "\"urn:panasonic-com:service:p00NetworkControl:1#X_SendKey\""))
        return true
    }
}

/**
 * Freebox Player (Révolution, Mini 4K, Delta, Pop en mode Player) : télécommande réseau HTTP.
 * Code à 8 chiffres : Freebox Player > Réglages > Système > Informations Freebox Player et Server.
 * Hôte par défaut : hd1.freebox.fr (résolu uniquement depuis le réseau Free).
 */
class FreeboxDriver(device: Device) : Driver(device) {
    private val keys = mapOf(
        POWER to "power", VOL_UP to "vol_inc", VOL_DOWN to "vol_dec", MUTE to "mute",
        CH_UP to "prgm_inc", CH_DOWN to "prgm_dec", UP to "up", DOWN to "down", LEFT to "left", RIGHT to "right",
        OK to "ok", BACK to "red", HOME to "home", MENU to "options", INFO to "info", INPUT to "tv", EXIT to "home",
        PLAY_PAUSE to "play", REWIND to "bwd", FAST_FORWARD to "fwd",
        RED to "red", GREEN to "green", YELLOW to "yellow", BLUE to "blue"
    ) + RemoteKey.DIGITS.mapIndexed { i, k -> k to "$i" }

    private val host get() = device.host.ifBlank { "hd1.freebox.fr" }

    override suspend fun connect(ui: PairingUi) {
        if (device.secret.isBlank()) {
            val code = ui.askCode("Code télécommande Freebox",
                "Sur le Player : Réglages > Système > Informations Freebox Player et Server > « Code télécommande réseau ».",
                "8 chiffres") ?: throw RemoteException("Code requis")
            device.secret = code.trim(); persist()
        }
    }

    override suspend fun send(key: RemoteKey): Boolean {
        val k = keys[key] ?: return false
        Net.get("http://$host/pub/remote_control?code=${URLEncoder.encode(device.secret, "UTF-8")}&key=$k")
        return true
    }

    override suspend fun powerOn(): Boolean = runCatching { send(POWER) }.getOrDefault(false)
}

/** Kodi (JSON-RPC HTTP, port 8080 par défaut) – « Autoriser le contrôle à distance via HTTP » à activer. */
class KodiDriver(device: Device) : Driver(device) {
    private var id = 0

    private val actions = mapOf(
        VOL_UP to "volumeup", VOL_DOWN to "volumedown", MUTE to "mute", CH_UP to "pageup", CH_DOWN to "pagedown",
        UP to "up", DOWN to "down", LEFT to "left", RIGHT to "right", OK to "select", BACK to "back",
        MENU to "contextmenu", INFO to "info", EXIT to "back", PLAY_PAUSE to "playpause", STOP to "stop",
        REWIND to "rewind", FAST_FORWARD to "fastforward", RED to "red", GREEN to "green", YELLOW to "yellow", BLUE to "blue"
    ) + RemoteKey.DIGITS.mapIndexed { i, k -> k to "number$i" }

    private suspend fun rpc(method: String, params: JSONObject? = null) {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", ++id).put("method", method)
        if (params != null) body.put("params", params)
        val headers = if (device.user.isNotBlank()) mapOf("Authorization" to "Basic " +
            Base64.encodeToString("${device.user}:${device.secret}".toByteArray(), Base64.NO_WRAP)) else emptyMap()
        val res = try {
            Net.post("http://${device.host}:${device.port}/jsonrpc", body.toString(), "application/json", headers)
        } catch (e: RemoteException) {
            if (e.message?.contains("401") == true) throw RemoteException("Kodi : identifiant ou mot de passe incorrect.")
            throw e
        }
        JSONObject(res).optJSONObject("error")?.let { throw RemoteException("Kodi : ${it.optString("message")}") }
    }

    override suspend fun connect(ui: PairingUi) = rpc("JSONRPC.Ping")

    override suspend fun send(key: RemoteKey): Boolean {
        when (key) {
            HOME -> rpc("Input.Home")
            POWER -> rpc("System.Shutdown")
            INPUT -> rpc("GUI.ActivateWindow", JSONObject().put("window", "videos"))
            else -> rpc("Input.ExecuteAction", JSONObject().put("action", actions[key] ?: return false))
        }
        return true
    }
}
