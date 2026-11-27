package fr.paul.telecommande.shared

import org.json.JSONArray
import org.json.JSONObject

/** Un bonus accordé par le parent, avec son motif (« Devoirs faits »…), gardé pour les bilans. */
data class ContractBonus(val at: Long, val minutes: Int, val reason: String) {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("minutes", minutes).put("reason", reason)

    companion object {
        fun fromJson(o: JSONObject): ContractBonus? {
            val at = o.optLong("at"); val reason = o.optString("reason").trim().take(40)
            if (at <= 0 || reason.isEmpty()) return null
            return ContractBonus(at, o.optInt("minutes").coerceIn(1, 240), reason)
        }
    }
}

/**
 * Contrat parental d'un enfant : ce que le parent a choisi dans l'assistant (phrases à trous), puis affiné à la table de mixage.
 * Les réglages qui comptent pour la TV (temps en semaine et le week-end, extinction le soir, fin du temps) sont recopiés
 * dans le [Profile] ; le contrat garde le reste : réponses, souplesse, rythme des relectures, bonus accordés, pause.
 * L'appli ne juge jamais l'enfant : elle ne fait que rappeler au parent ses propres décisions.
 */
data class Contract(
    /** "active" ou "paused" (pause : aucune limite pour cet enfant, jusqu'à la reprise). */
    var status: String = ACTIVE,
    var since: Long = 0,
    /** Réponses aux phrases à trous : id de la question → indice de la proposition choisie. */
    var answers: MutableMap<String, Int> = mutableMapOf(),
    /** Souplesse : 0 = pas de bonus, 1 = jusqu'à 15 min, 2 = 30 min, 3 = 45 min. */
    var softness: Int = 1,
    /** Relecture proposée tous les N jours (0 = jamais). */
    var reviewDays: Int = 30,
    var nextReview: Long = 0,
    /** Montrer le contrat à l'enfant sur la TV (choix du parent). */
    var showChild: Boolean = false,
    var bonuses: MutableList<ContractBonus> = mutableListOf(),
    /** Dernier bilan vu par le parent : les bonus d'avant ne redéclenchent pas de bilan. */
    var reviewedAt: Long = 0
) {
    val paused get() = status == PAUSED

    /** Bonus proposé d'un geste selon la souplesse (0 = aucun). */
    fun bonusMinutes(): Int = softness.coerceIn(0, 3) * 15

    fun toJson(): JSONObject = JSONObject().put("status", status).put("since", since)
        .put("answers", JSONObject(answers as Map<*, *>)).put("softness", softness).put("reviewDays", reviewDays)
        .put("nextReview", nextReview).put("showChild", showChild)
        .put("bonuses", JSONArray().apply { bonuses.takeLast(MAX_BONUSES).forEach { put(it.toJson()) } })
        .put("reviewedAt", reviewedAt)

    companion object {
        const val ACTIVE = "active"
        const val PAUSED = "paused"
        const val MAX_BONUSES = 120
        val REVIEW_CHOICES = listOf(0, 30, 14)

        fun fromJson(o: JSONObject?): Contract? {
            if (o == null) return null
            val answers = mutableMapOf<String, Int>()
            o.optJSONObject("answers")?.let { a -> a.keys().forEach { k -> if (k.length <= 20) answers[k] = a.optInt(k).coerceIn(0, 9) } }
            val bonuses = o.optJSONArray("bonuses")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(ContractBonus::fromJson) } }
                ?.takeLast(MAX_BONUSES)?.toMutableList() ?: mutableListOf()
            return Contract(
                if (o.optString("status") == PAUSED) PAUSED else ACTIVE, o.optLong("since"), answers,
                o.optInt("softness", 1).coerceIn(0, 3), o.optInt("reviewDays", 30).let { if (it in REVIEW_CHOICES) it else 30 },
                o.optLong("nextReview"), o.optBoolean("showChild"), bonuses, o.optLong("reviewedAt")
            )
        }
    }
}

/** L'assistant du contrat : les phrases à trous, la proposition de réglages, la phrase « concrètement » et les bilans. */
object ContractWizard {

    /** Une phrase à trous : [text] contient « {name} » pour le prénom ; [options] complètent le trou. */
    data class Question(val id: String, val text: String, val options: List<String>)

    val QUESTIONS = listOf(
        Question("tv", "Pour moi, la télévision, c'est…", listOf("un moment de détente", "une récompense", "un outil pour apprendre", "un peu de tout")),
        Question("school", "Les jours d'école, je préfère que {name} regarde…", listOf("très peu", "un peu", "raisonnablement", "sans trop compter")),
        Question("weekend", "Le week-end, c'est…", listOf("pareil que la semaine", "un peu plus", "beaucoup plus")),
        Question("evening", "Le soir, la TV s'éteint…", listOf("tôt", "à une heure raisonnable", "plus tard")),
        Question("end", "Quand le temps est fini, je préfère…", listOf("couper à l'heure pile", "laisser 5 minutes", "laisser finir l'épisode"))
    )

    /** Réponses par défaut (le milieu raisonnable), avant que le parent choisisse. */
    val DEFAULT_ANSWERS = mapOf("tv" to 3, "school" to 2, "weekend" to 1, "evening" to 1, "end" to 2)

    /** Les curseurs de la table de mixage. */
    data class Mix(var weekMin: Int, var weekendMin: Int, var bedtime: Int, var graceMin: Int, var softness: Int, var reviewDays: Int)

    val GRACE_CHOICES = listOf(0, 5, 10)
    const val BEDTIME_MIN = 18 * 60
    const val BEDTIME_MAX = 23 * 60

    private fun round15(v: Int) = ((v + 7) / 15) * 15

    /** Réglages proposés d'après l'âge (s'il est connu) et les réponses aux phrases à trous. */
    fun propose(age: Int?, answers: Map<String, Int>): Mix {
        val a = DEFAULT_ANSWERS + answers
        val preset = RulesAdvisor.presetFor(age ?: 8)
        val week = round15((preset.dailyMin * listOf(0.5, 0.75, 1.0, 1.5)[a.getValue("school").coerceIn(0, 3)]).toInt()).coerceIn(15, 240)
        val weekend = when (a.getValue("weekend")) { 0 -> week; 1 -> week + 30; else -> week * 2 }.coerceIn(15, 300)
        val bedtime = (preset.bedtime + listOf(-30, 0, 30)[a.getValue("evening").coerceIn(0, 2)]).coerceIn(BEDTIME_MIN, BEDTIME_MAX)
        val grace = GRACE_CHOICES[a.getValue("end").coerceIn(0, 2)]
        val softness = if (a.getValue("tv") == 1) 2 else 1
        return Mix(week, weekend, bedtime, grace, softness, 30)
    }

    /** Les curseurs d'un profil qui a déjà un contrat (pour les ajuster). */
    fun mixOf(rules: BoxRules, p: Profile): Mix {
        val c = p.contract ?: Contract()
        val week = if (p.dailyTotalMin > 0) p.dailyTotalMin else rules.dailyTotalMin.takeIf { it > 0 } ?: 60
        return Mix(week, if (p.weekendMin > 0) p.weekendMin else week,
            if (p.bedtime >= 0) p.bedtime else if (rules.bedtimeEnabled) rules.bedtimeStart.coerceIn(BEDTIME_MIN, BEDTIME_MAX) else 20 * 60,
            if (p.graceMin >= 0) p.graceMin else rules.graceMin.coerceIn(0, 10).let { g -> GRACE_CHOICES.minByOrNull { kotlin.math.abs(it - g) } ?: 10 },
            c.softness, c.reviewDays)
    }

    /** Recopie le contrat dans le profil : ce que la TV applique, et ce que le contrat garde. */
    fun apply(p: Profile, mix: Mix, answers: Map<String, Int>, showChild: Boolean, now: Long) {
        p.dailyTotalMin = mix.weekMin.coerceIn(15, AppRule.DAY_MIN)
        p.weekendMin = if (mix.weekendMin == mix.weekMin) 0 else mix.weekendMin.coerceIn(15, AppRule.DAY_MIN)
        p.bedtime = mix.bedtime.coerceIn(BEDTIME_MIN, BEDTIME_MAX)
        p.graceMin = mix.graceMin.coerceIn(0, 30)
        val c = p.contract ?: Contract(since = now)
        if (c.since == 0L) c.since = now
        c.status = Contract.ACTIVE
        c.answers = answers.toMutableMap()
        c.softness = mix.softness.coerceIn(0, 3)
        c.reviewDays = mix.reviewDays.takeIf { it in Contract.REVIEW_CHOICES } ?: 30
        c.nextReview = if (c.reviewDays > 0) now + c.reviewDays * DAY_MS else 0
        c.reviewedAt = now
        c.showChild = showChild
        p.contract = c
    }

    /** Arrêter le contrat : on garde les limites actuelles telles quelles (mode simple), sans le reste. */
    fun stop(p: Profile) {
        p.contract = null
        p.weekendMin = 0; p.bedtime = -1; p.graceMin = -1
    }

    const val DAY_MS = 24 * 3600_000L

    fun fmtMin(min: Int): String = RulesEngine.fmt(min * 60_000L)

    fun clock(min: Int): String = "${min / 60} h ${"%02d".format(min % 60)}".replace(" h 00", " h")

    /** « Naël aura 1 h 15 les jours d'école et 2 h le week-end, TV éteinte à 20 h. Bonus jusqu'à 15 min, sur votre accord. » */
    fun sentence(name: String, m: Mix): String {
        val days = if (m.weekendMin == m.weekMin) "${fmtMin(m.weekMin)} par jour" else "${fmtMin(m.weekMin)} les jours d'école et ${fmtMin(m.weekendMin)} le week-end"
        val end = when (m.graceMin) { 0 -> " La TV coupe à l'heure pile."; 5 -> " 5 minutes de rab quand le temps est fini."; else -> " Il peut finir son épisode." }
        val bonus = if (m.softness == 0) " Pas de bonus." else " Bonus jusqu'à ${m.softness * 15} min, sur votre accord."
        val review = when (m.reviewDays) { 0 -> ""; 14 -> " On vous proposera de relire le contrat dans deux semaines."; else -> " On vous proposera de relire le contrat dans un mois." }
        return "$name aura $days, TV éteinte à ${clock(m.bedtime)}.$end$bonus$review"
    }

    /** Motifs de bonus proposés (le parent peut en écrire un autre). */
    val REASONS = listOf("Devoirs faits", "Bon comportement", "Aide à la maison")

    /** Bilan proposé au parent : son texte, et s'il est dû (date de relecture passée ou bonus répétés). */
    data class Review(val title: String, val text: String)

    fun review(name: String, c: Contract?, now: Long): Review? {
        if (c == null || c.paused) return null
        val recent = c.bonuses.filter { it.at > maxOf(now - 60 * DAY_MS, c.reviewedAt) }
        val top = recent.groupBy { it.reason }.maxByOrNull { it.value.size }
        val due = c.nextReview in 1..now
        val many = top != null && top.value.size >= 5
        if (!due && !many) return null
        val span = ((now - (recent.minOfOrNull { it.at } ?: now)) / DAY_MS).toInt()
        val period = when { span >= 45 -> "En 2 mois"; span >= 20 -> "En un mois"; span >= 10 -> "En deux semaines"; else -> "Ces derniers jours" }
        val text = if (top != null && top.value.size >= 2)
            "$period, vous avez accordé ${top.value.size} bonus « ${top.key} » à $name. Souhaitez-vous revoir le contrat ?"
        else "Le contrat de $name a ${((now - c.since) / DAY_MS).coerceAtLeast(1)} jours. Souhaitez-vous le relire ?"
        return Review("Bilan du contrat", text)
    }

    /** « Garder tel quel » : on en reparle à la prochaine échéance (un mois si les relectures sont coupées). */
    fun postpone(c: Contract, now: Long) {
        c.nextReview = if (c.reviewDays > 0) now + c.reviewDays * DAY_MS else 0
        c.reviewedAt = now
    }

    /** Le contrat expliqué à l'enfant sur la TV : (icône, phrase, précision). */
    fun childLines(rules: BoxRules, p: Profile): List<Triple<String, String, String>> {
        val m = mixOf(rules, p)
        val out = mutableListOf<Triple<String, String, String>>()
        out += if (m.weekendMin == m.weekMin) Triple("📺", "${fmtMin(m.weekMin)} de TV par jour", "")
            else Triple("📺", "${fmtMin(m.weekMin)} les jours d'école", "${fmtMin(m.weekendMin)} le samedi et le dimanche")
        out += Triple("🌙", "TV éteinte à ${clock(m.bedtime)}", "On te prévient un peu avant")
        out += Triple("⏱️", "Quand le temps est fini", when (m.graceMin) { 0 -> "La TV s'arrête à l'heure pile"; 5 -> "Tu as 5 minutes pour finir"; else -> "Tu peux finir ton épisode" })
        if (m.softness > 0) out += Triple("⭐", "Des bonus sont possibles", "Ce sont tes parents qui décident")
        return out
    }

    /** Empreinte de ce que l'enfant a vu : s'il change, la TV lui remontre le contrat. */
    fun signature(p: Profile): String =
        listOf(p.dailyTotalMin, p.weekendMin, p.bedtime, p.graceMin, p.contract?.softness ?: 0).joinToString("|")
}
