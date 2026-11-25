package fr.paul.telecommande.core

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/** Règles de contrôle parental d'une box. Les durées sont en minutes, les heures en minutes depuis minuit. */
data class ParentalRules(
    var enabled: Boolean = false,
    var dailyTotalMin: Int = 0,                         // 0 = pas de limite
    var perAppMin: MutableMap<String, Int> = mutableMapOf(),
    var bedtimeEnabled: Boolean = false,
    var bedtimeStart: Int = 21 * 60,
    var bedtimeEnd: Int = 7 * 60,
    /** Quand les règles s'appliquent (égaux = toute la journée) ; en dehors : temps libre. */
    var activeFrom: Int = 0,
    var activeTo: Int = 0,
    var activeDays: Int = 0x7F
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled); put("dailyTotalMin", dailyTotalMin)
        put("perApp", JSONObject(perAppMin as Map<*, *>))
        put("bedtimeEnabled", bedtimeEnabled); put("bedtimeStart", bedtimeStart); put("bedtimeEnd", bedtimeEnd)
        put("activeFrom", activeFrom); put("activeTo", activeTo); put("activeDays", activeDays)
    }

    companion object {
        fun fromJson(o: JSONObject?): ParentalRules {
            if (o == null) return ParentalRules()
            val per = mutableMapOf<String, Int>()
            o.optJSONObject("perApp")?.let { p -> p.keys().forEach { k -> per[k] = p.optInt(k) } }
            return ParentalRules(
                o.optBoolean("enabled"), o.optInt("dailyTotalMin"), per,
                o.optBoolean("bedtimeEnabled"), o.optInt("bedtimeStart", 21 * 60), o.optInt("bedtimeEnd", 7 * 60),
                o.optInt("activeFrom"), o.optInt("activeTo"), o.optInt("activeDays", 0x7F)
            )
        }
    }
}

/** Ce que le moniteur doit faire maintenant. */
sealed class Decision {
    object None : Decision()
    /** Ramener la box à l'accueil (appli au-delà de sa limite). */
    data class GoHome(val pkg: String, val reason: String) : Decision()
    /** Mettre la box en veille (couvre-feu ou limite totale). */
    data class Standby(val reason: String) : Decision()
}

data class Warning(val key: String, val message: String)

object ParentalLogic {
    fun inWindow(minute: Int, start: Int, end: Int): Boolean =
        if (start == end) false else if (start < end) minute in start until end else minute >= start || minute < end

    /**
     * @param usage temps du jour par appli (ms), session en cours comprise
     * @param homePkgs paquets « accueil » (non décomptés dans les limites par appli)
     */
    fun evaluate(
        rules: ParentalRules, minuteOfDay: Int, usage: Map<String, Long>, currentPkg: String,
        isOn: Boolean, totalMs: Long
    ): Pair<Decision, List<Warning>> {
        if (!rules.enabled || !isOn) return Decision.None to emptyList()
        val warnings = mutableListOf<Warning>()
        if (rules.bedtimeEnabled && inWindow(minuteOfDay, rules.bedtimeStart, rules.bedtimeEnd)) {
            return Decision.Standby("Couvre-feu (${hhmm(rules.bedtimeStart)} – ${hhmm(rules.bedtimeEnd)})") to warnings
        }
        // Temps libre : en dehors des heures/jours choisis, plus aucune limite (le couvre-feu reste actif)
        val dow = (java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
        val free = rules.activeDays and (1 shl dow) == 0 ||
            (rules.activeFrom != rules.activeTo && !inWindow(minuteOfDay, rules.activeFrom, rules.activeTo))
        if (free) return Decision.None to warnings
        if (rules.bedtimeEnabled) {
            val until = (rules.bedtimeStart - minuteOfDay + 1440) % 1440
            if (until in 1..5) warnings += Warning("bed", "Couvre-feu dans $until min : la TV se mettra en veille.")
        }
        if (rules.dailyTotalMin > 0) {
            val limit = rules.dailyTotalMin * 60_000L
            if (totalMs >= limit) return Decision.Standby("Temps de TV du jour atteint (${Usage.format(limit)})") to warnings
            if (limit - totalMs <= 5 * 60_000L) warnings += Warning("total", "Plus que ${Usage.format(limit - totalMs)} de TV aujourd'hui.")
        }
        val appLimit = rules.perAppMin[currentPkg] ?: 0
        if (appLimit > 0) {
            val used = usage[currentPkg] ?: 0L
            val limit = appLimit * 60_000L
            if (used >= limit) return Decision.GoHome(currentPkg, "Limite atteinte pour ${AppCatalog.name(currentPkg)} (${Usage.format(limit)})") to warnings
            if (limit - used <= 5 * 60_000L) warnings += Warning("app:$currentPkg", "${AppCatalog.name(currentPkg)} : plus que ${Usage.format(limit - used)} aujourd'hui.")
        }
        return Decision.None to warnings
    }

    fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
}

/** Stockage des règles et du code PIN parent (haché). */
class ParentalStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("parental", Context.MODE_PRIVATE)

    fun rules(deviceId: String): ParentalRules =
        ParentalRules.fromJson(runCatching { JSONObject(prefs.getString("rules_$deviceId", "{}") ?: "{}") }.getOrNull())

    fun save(deviceId: String, r: ParentalRules) = prefs.edit().putString("rules_$deviceId", r.toJson().toString()).apply()

    val hasPin get() = prefs.contains("pin")
    fun setPin(pin: String) {
        val boxHash = fr.paul.telecommande.shared.BoxProtocol.pinHash(pin)
        prefs.edit().putString("pin", hash(pin)).putString("boxPin", boxHash).apply()
        // Les box déjà reliées recevront le nouveau code à la prochaine synchronisation
        prefs.all.keys.filter { it.startsWith("box_") }.forEach { k ->
            val id = k.removePrefix("box_")
            val r = boxRules(id)
            if (r.version > 0) { r.pinHash = boxHash; r.version = System.currentTimeMillis(); saveBoxRules(id, r) }
        }
    }
    fun checkPin(pin: String): Boolean {
        val ok = prefs.getString("pin", null) == hash(pin)
        // mémorise l'empreinte attendue par l'appli Box (codes créés avant la v1.5)
        if (ok && !prefs.contains("boxPin")) prefs.edit().putString("boxPin", fr.paul.telecommande.shared.BoxProtocol.pinHash(pin)).apply()
        return ok
    }

    /** Empreinte du code parent envoyée à l'appli Box (jamais le code lui-même). */
    /** Anti-devinette du code parent sur le téléphone, comme sur la TV (5 essais, puis pauses croissantes). */
    private val pinThrottle = fr.paul.telecommande.shared.PinThrottle(
        load = { prefs.getInt("pinFails", 0) to prefs.getLong("pinLockUntil", 0L) },
        save = { f, u -> prefs.edit().putInt("pinFails", f).putLong("pinLockUntil", u).apply() }
    )

    fun tryPin(pin: String): fr.paul.telecommande.shared.PinThrottle.Result = pinThrottle.attempt { checkPin(pin) }

    /** Empreinte du parent réussie : la pause des codes faux est levée. */
    fun clearPinPause() = pinThrottle.clear()

    val boxPinHash: String get() = prefs.getString("boxPin", "") ?: ""

    /** Règles complètes de l'appli Box, mémorisées aussi sur le téléphone. */
    fun boxRules(deviceId: String): fr.paul.telecommande.shared.BoxRules =
        fr.paul.telecommande.shared.BoxRules.fromJson(runCatching { JSONObject(prefs.getString("box_$deviceId", "{}") ?: "{}") }.getOrNull())

    fun saveBoxRules(deviceId: String, r: fr.paul.telecommande.shared.BoxRules) =
        prefs.edit().putString("box_$deviceId", r.toJson().toString()).apply()

    private fun hash(pin: String) = MessageDigest.getInstance("SHA-256")
        .digest(("telecommande:" + pin).toByteArray()).joinToString("") { "%02x".format(it) }
}
