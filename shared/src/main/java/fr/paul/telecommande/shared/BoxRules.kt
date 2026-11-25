package fr.paul.telecommande.shared

import org.json.JSONObject

/**
 * Règle d'une appli.
 * - limitMin : minutes par jour (0 = illimité)
 * - days : jours autorisés, bit 0 = lundi … bit 6 = dimanche (0x7F = tous)
 * - from / to : plage horaire autorisée en minutes depuis minuit (from == to = toute la journée)
 * - blocked : appli interdite
 */
data class AppRule(
    var limitMin: Int = 0,
    var days: Int = ALL_DAYS,
    var from: Int = 0,
    var to: Int = 0,
    var blocked: Boolean = false
) {
    val isDefault get() = limitMin == 0 && days == ALL_DAYS && from == to && !blocked

    fun toJson(): JSONObject = JSONObject().put("limitMin", limitMin).put("days", days).put("from", from).put("to", to).put("blocked", blocked)

    companion object {
        const val ALL_DAYS = 0x7F
        fun fromJson(o: JSONObject) = AppRule(o.optInt("limitMin").coerceIn(0, DAY_MIN), o.optInt("days", ALL_DAYS) and ALL_DAYS,
            minuteOf(o.optInt("from")), minuteOf(o.optInt("to")), o.optBoolean("blocked"))

        const val DAY_MIN = 24 * 60
        /** Minute du jour toujours dans 0..1439 (une valeur hors bornes venue du réseau ne doit rien casser). */
        fun minuteOf(v: Int): Int = ((v % DAY_MIN) + DAY_MIN) % DAY_MIN
    }
}

/**
 * Profil « Qui regarde ? ».
 * - dailyTotalMin : temps de TV par jour pour ce profil (0 = celui des règles générales)
 * - unlimited : profil parent (aucune limite), protégé par le code parent
 */
data class Profile(
    var id: String = "",
    var name: String = "",
    var emoji: String = "🙂",
    var dailyTotalMin: Int = 0,
    var unlimited: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("emoji", emoji)
        .put("dailyTotalMin", dailyTotalMin).put("unlimited", unlimited)

    companion object {
        fun fromJson(o: JSONObject) = Profile(o.optString("id"), o.optString("name"), o.optString("emoji", "🙂"),
            o.optInt("dailyTotalMin").coerceIn(0, AppRule.DAY_MIN), o.optBoolean("unlimited"))
    }
}

/** Règles complètes d'une box, synchronisées téléphone → box. */
data class BoxRules(
    var enabled: Boolean = false,
    var dailyTotalMin: Int = 0,
    var bedtimeEnabled: Boolean = false,
    var bedtimeStart: Int = 21 * 60,
    var bedtimeEnd: Int = 7 * 60,
    var apps: MutableMap<String, AppRule> = mutableMapOf(),
    var protectSettings: Boolean = true,
    var pinHash: String = "",
    var requestMinutes: Int = 15,
    var version: Long = 0,
    var profiles: MutableList<Profile> = mutableListOf(),
    /** Fin d'épisode : minutes de grâce max quand une vidéo est en cours (0 = coupure immédiate). */
    var graceMin: Int = 10,
    /** Sablier discret dans un coin quand il reste moins de 15 min. */
    var showTimer: Boolean = true,
    /** « Vous êtes toujours là ? » après ce nombre de minutes sans aucune touche (0 = jamais). */
    var stillThereMin: Int = 180,
    /**
     * Quand les règles s'appliquent : de activeFrom à activeTo (minutes depuis minuit ; égaux = toute la journée)
     * et les jours activeDays (bit 0 = lundi). En dehors : temps libre, plus aucune limite (le couvre-feu reste actif).
     */
    var activeFrom: Int = 0,
    var activeTo: Int = 0,
    var activeDays: Int = AppRule.ALL_DAYS
) {
    /** Règles effectives pour un profil (parent = aucune limite). */
    fun forProfile(p: Profile?): BoxRules {
        if (p == null) return this
        if (p.unlimited) return copy(enabled = false)
        return if (p.dailyTotalMin > 0) copy(dailyTotalMin = p.dailyTotalMin) else this
    }

    fun toJson(): JSONObject {
        val a = JSONObject()
        apps.filterValues { !it.isDefault }.forEach { (k, v) -> a.put(k, v.toJson()) }
        return JSONObject().put("enabled", enabled).put("dailyTotalMin", dailyTotalMin)
            .put("bedtimeEnabled", bedtimeEnabled).put("bedtimeStart", bedtimeStart).put("bedtimeEnd", bedtimeEnd)
            .put("apps", a).put("protectSettings", protectSettings).put("pinHash", pinHash)
            .put("requestMinutes", requestMinutes).put("version", version)
            .put("profiles", org.json.JSONArray().apply { profiles.forEach { put(it.toJson()) } })
            .put("graceMin", graceMin).put("showTimer", showTimer).put("stillThereMin", stillThereMin)
            .put("activeFrom", activeFrom).put("activeTo", activeTo).put("activeDays", activeDays)
    }

    companion object {
        fun fromJson(o: JSONObject?): BoxRules {
            if (o == null) return BoxRules()
            val apps = mutableMapOf<String, AppRule>()
            o.optJSONObject("apps")?.let { a -> a.keys().forEach { k -> a.optJSONObject(k)?.let { apps[k] = AppRule.fromJson(it) } } }
            return BoxRules(
                o.optBoolean("enabled"), o.optInt("dailyTotalMin").coerceIn(0, AppRule.DAY_MIN), o.optBoolean("bedtimeEnabled"),
                AppRule.minuteOf(o.optInt("bedtimeStart", 21 * 60)), AppRule.minuteOf(o.optInt("bedtimeEnd", 7 * 60)), apps,
                o.optBoolean("protectSettings", true), o.optString("pinHash"), o.optInt("requestMinutes", 15).coerceIn(5, 120),
                o.optLong("version"),
                o.optJSONArray("profiles")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Profile::fromJson) }.toMutableList() } ?: mutableListOf(),
                o.optInt("graceMin", 10).let { if (it == 20) 10 else it }.coerceIn(0, 30), o.optBoolean("showTimer", true), o.optInt("stillThereMin", 180).coerceIn(0, 600),
                AppRule.minuteOf(o.optInt("activeFrom")), AppRule.minuteOf(o.optInt("activeTo")), o.optInt("activeDays", AppRule.ALL_DAYS) and AppRule.ALL_DAYS
            )
        }
    }
}

sealed class Verdict {
    object Allowed : Verdict()
    /** Moins de 5 minutes restantes. */
    data class Warn(val remainingMs: Long, val message: String) : Verdict()
    /** L'appli doit être fermée. canRequest : l'enfant peut demander du temps. */
    data class Block(val title: String, val reason: String, val canRequest: Boolean, val kind: String = KIND_APP) : Verdict()

    companion object {
        /** Ce qu'une demande de temps acceptée doit lever. */
        const val KIND_BLOCKED = "blocked"
        const val KIND_BEDTIME = "bedtime"    // dérogation globale temporaire
        const val KIND_SCHEDULE = "schedule"  // dérogation temporaire pour l'appli
        const val KIND_TOTAL = "total"        // bonus sur le temps total
        const val KIND_APP = "app"            // bonus sur l'appli
    }
}

/** Contexte d'évaluation : temps du jour et bonus accordés (ms). */
data class UsageContext(
    val minuteOfDay: Int,
    val dayOfWeek: Int,              // 0 = lundi … 6 = dimanche
    val usedMs: Long,                // temps du jour sur cette appli
    val totalMs: Long,               // temps du jour toutes applis (hors accueil)
    val bonusMs: Long = 0,           // bonus du jour sur cette appli
    val totalBonusMs: Long = 0,      // bonus du jour sur le temps total
    val scheduleOverride: Boolean = false, // dérogation horaire accordée pour cette appli
    val bedtimeOverride: Boolean = false   // dérogation au couvre-feu accordée
)

object RulesEngine {
    const val WARN_MS = 5 * 60_000L

    fun inWindow(minute: Int, start: Int, end: Int): Boolean =
        if (start == end) true else if (start < end) minute in start until end else minute >= start || minute < end

    fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

    fun fmt(ms: Long): String {
        val min = ((ms + 59_999) / 60_000).toInt()
        return if (min < 60) "$min min" else if (min % 60 == 0) "${min / 60} h" else "${min / 60} h ${"%02d".format(min % 60)}"
    }

    private val DAY_NAMES = listOf("lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim.")
    fun daysLabel(days: Int): String = when (days and AppRule.ALL_DAYS) {
        AppRule.ALL_DAYS -> "tous les jours"
        0x1F -> "en semaine"
        0x60 -> "le week-end"
        0 -> "jamais"
        else -> (0..6).filter { days and (1 shl it) != 0 }.joinToString(" ") { DAY_NAMES[it] }
    }

    /** Les règles s'appliquent-elles maintenant ? (sinon : temps libre) */
    fun rulesActive(rules: BoxRules, minuteOfDay: Int, dayOfWeek: Int): Boolean =
        rules.activeDays and (1 shl dayOfWeek) != 0 && inWindow(minuteOfDay, rules.activeFrom, rules.activeTo)

    /** Phrase courte : « Règles de 07:00 à 21:00, en semaine » / « Règles toute la journée ». */
    fun activeLabel(rules: BoxRules): String {
        val h = if (rules.activeFrom == rules.activeTo) "toute la journée" else "de ${hhmm(rules.activeFrom)} à ${hhmm(rules.activeTo)}"
        val d = if (rules.activeDays and AppRule.ALL_DAYS == AppRule.ALL_DAYS) "" else ", ${daysLabel(rules.activeDays)}"
        return "Règles $h$d"
    }

    /** Temps restant avant blocage (le plus court entre total du jour et limite de l'appli), ou null si illimité. */
    fun remaining(rules: BoxRules, rule: AppRule?, c: UsageContext): Long? {
        if (!rules.enabled || rule?.blocked == true) return null
        if (!rulesActive(rules, c.minuteOfDay, c.dayOfWeek)) return null
        var r: Long? = null
        if (rules.dailyTotalMin > 0) r = rules.dailyTotalMin * 60_000L + c.totalBonusMs - c.totalMs
        if (rule != null && rule.limitMin > 0) {
            val a = rule.limitMin * 60_000L + c.bonusMs - c.usedMs
            r = if (r == null) a else minOf(r, a)
        }
        return r?.coerceAtLeast(0)
    }

    fun evaluate(rules: BoxRules, label: String, rule: AppRule?, c: UsageContext): Verdict {
        if (!rules.enabled) return Verdict.Allowed
        // Le couvre-feu reste prioritaire, même pendant le temps libre
        if (!c.bedtimeOverride && rules.bedtimeEnabled && rules.bedtimeStart != rules.bedtimeEnd &&
            inWindow(c.minuteOfDay, rules.bedtimeStart, rules.bedtimeEnd)) {
            return Verdict.Block("C'est l'heure de dormir", "La TV est disponible à partir de ${hhmm(rules.bedtimeEnd)}.", true, Verdict.KIND_BEDTIME)
        }
        // Temps libre : plus aucune limite
        if (!rulesActive(rules, c.minuteOfDay, c.dayOfWeek)) return Verdict.Allowed
        if (rule?.blocked == true) return Verdict.Block("$label est bloquée", "Cette appli n'est pas autorisée.", false, Verdict.KIND_BLOCKED)
        if (rule != null && !c.scheduleOverride) {
            if (rule.days and (1 shl c.dayOfWeek) == 0) {
                return Verdict.Block("$label n'est pas disponible aujourd'hui", "Autorisée ${daysLabel(rule.days)}.", true, Verdict.KIND_SCHEDULE)
            }
            if (!inWindow(c.minuteOfDay, rule.from, rule.to)) {
                return Verdict.Block("$label n'est pas disponible maintenant", "Autorisée de ${hhmm(rule.from)} à ${hhmm(rule.to)}.", true, Verdict.KIND_SCHEDULE)
            }
        }
        var warn: Verdict.Warn? = null
        if (rules.dailyTotalMin > 0) {
            val limit = rules.dailyTotalMin * 60_000L + c.totalBonusMs
            if (c.totalMs >= limit) return Verdict.Block("Temps de TV terminé", "Le temps d'écran du jour (${fmt(limit)}) est écoulé.", true, Verdict.KIND_TOTAL)
            if (limit - c.totalMs <= WARN_MS) warn = Verdict.Warn(limit - c.totalMs, "Plus que ${fmt(limit - c.totalMs)} de TV aujourd'hui")
        }
        if (rule != null && rule.limitMin > 0) {
            val limit = rule.limitMin * 60_000L + c.bonusMs
            if (c.usedMs >= limit) return Verdict.Block("Temps écoulé pour $label", "Limite du jour : ${fmt(limit)}.", true, Verdict.KIND_APP)
            val left = limit - c.usedMs
            if (left <= WARN_MS && (warn == null || left < warn.remainingMs)) warn = Verdict.Warn(left, "Plus que ${fmt(left)} sur $label")
        }
        return warn ?: Verdict.Allowed
    }
}
