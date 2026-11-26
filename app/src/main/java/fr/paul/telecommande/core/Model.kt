package fr.paul.telecommande.core

import org.json.JSONObject
import java.util.UUID

/** Touches logiques de la télécommande, traduites par chaque pilote. */
enum class RemoteKey {
    POWER, VOL_UP, VOL_DOWN, MUTE, CH_UP, CH_DOWN,
    UP, DOWN, LEFT, RIGHT, OK, BACK, HOME, MENU, INFO, INPUT, EXIT,
    D0, D1, D2, D3, D4, D5, D6, D7, D8, D9,
    PLAY_PAUSE, STOP, REWIND, FAST_FORWARD,
    RED, GREEN, YELLOW, BLUE,
    GUIDE, LAST_CHANNEL;

    companion object {
        val DIGITS = listOf(D0, D1, D2, D3, D4, D5, D6, D7, D8, D9)
        fun digit(n: Int) = DIGITS[n]
    }
}

enum class DeviceType(val label: String, val needsHost: Boolean, val defaultPort: Int) {
    IR("Infrarouge (émetteur IR du téléphone)", false, 0),
    SAMSUNG("Samsung Smart TV (Tizen, 2016+)", true, 8002),
    LG("LG webOS (2014+)", true, 3000),
    ANDROID_TV("Android TV / Google TV (Sony, Philips, TCL, Hisense, Xiaomi, Chromecast…)", true, 6466),
    ROKU("Roku / TV Roku", true, 8060),
    SONY("Sony Bravia (IRCC-IP, code PSK)", true, 80),
    PHILIPS("Philips (JointSpace, avant 2016)", true, 1925),
    PANASONIC("Panasonic Viera (avant 2019)", true, 55000),
    VESTEL("TV Vestel : Edenwood, Toshiba, Telefunken, Hitachi, JVC… (Smart TV non Android)", true, 56789),
    FREEBOX("Freebox Player (code télécommande réseau)", true, 80),
    KODI("Kodi / LibreELEC / OSMC", true, 8080);
}

/** Appareil enregistré par l'utilisateur. Les jetons d'appairage sont stockés ici. */
data class Device(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var type: DeviceType,
    var host: String = "",
    var port: Int = type.defaultPort,
    var mac: String = "",
    var irBrand: String = "",
    var token: String = "",     // Samsung token / LG client-key
    var secret: String = "",    // PSK Sony / code Freebox / mot de passe Kodi
    var user: String = "",      // utilisateur Kodi
    var ytScreenId: String = "",       // écran YouTube associé (box Android TV)
    var ytAutoSkipAds: Boolean = true, // appui automatique sur « Passer l'annonce »
    var ytSkipSponsors: Boolean = true, // saut des segments sponsorisés (SponsorBlock)
    var monitorEnabled: Boolean = false, // suivi du temps d'écran en continu
    var boxFp: String = ""              // empreinte épinglée de l'appli Box appairée
) {
    val hasBoxApp get() = isSmartBox && boxFp.isNotBlank()

    /** Fonctions avancées (assistant contextuel) : réservées aux box Android TV / Google TV. */
    val isSmartBox get() = type == DeviceType.ANDROID_TV

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("type", type.name); put("host", host)
        put("port", port); put("mac", mac); put("irBrand", irBrand); put("token", token)
        put("secret", secret); put("user", user)
        put("ytScreenId", ytScreenId); put("ytAutoSkipAds", ytAutoSkipAds); put("ytSkipSponsors", ytSkipSponsors)
        put("monitorEnabled", monitorEnabled); put("boxFp", boxFp)
    }

    companion object {
        fun fromJson(o: JSONObject): Device? {
            val type = runCatching { DeviceType.valueOf(o.getString("type")) }.getOrNull() ?: return null
            return Device(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", type.label),
                type = type,
                host = o.optString("host"),
                port = o.optInt("port", type.defaultPort),
                mac = o.optString("mac"),
                irBrand = o.optString("irBrand"),
                token = o.optString("token"),
                secret = o.optString("secret"),
                user = o.optString("user"),
                ytScreenId = o.optString("ytScreenId"),
                ytAutoSkipAds = o.optBoolean("ytAutoSkipAds", true),
                ytSkipSponsors = o.optBoolean("ytSkipSponsors", true),
                monitorEnabled = o.optBoolean("monitorEnabled", false),
                boxFp = o.optString("boxFp")
            )
        }
    }
}

class RemoteException(message: String, cause: Throwable? = null) : Exception(message, cause)
