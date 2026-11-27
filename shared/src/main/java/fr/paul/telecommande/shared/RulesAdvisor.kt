package fr.paul.telecommande.shared

/**
 * L'assistant « intelligent » du contrôle parental, calculé sur place (aucune IA en ligne) :
 *  - des réglages de départ selon l'âge de l'enfant ;
 *  - la détection des règles qui se contredisent ou ne servent à rien, avec une correction en un geste.
 * Les valeurs par âge sont des propositions de départ, toujours modifiables par le parent.
 */
object RulesAdvisor {

    const val YOUTUBE = "com.google.android.youtube.tv"

    /** Réglages proposés pour un âge : temps de TV par jour et début du couvre-feu (minutes depuis minuit). */
    data class AgePreset(val label: String, val dailyMin: Int, val bedtime: Int)

    fun presetFor(age: Int): AgePreset = when {
        age <= 5 -> AgePreset("3-5 ans", 45, 19 * 60 + 30)
        age <= 8 -> AgePreset("6-8 ans", 60, 20 * 60)
        age <= 11 -> AgePreset("9-11 ans", 90, 20 * 60 + 30)
        age <= 14 -> AgePreset("12-14 ans", 120, 21 * 60 + 30)
        else -> AgePreset("15 ans et +", 150, 22 * 60 + 30)
    }

    /** Fin du couvre-feu proposée (le matin). */
    const val BEDTIME_END = 7 * 60

    /**
     * Applique les réglages de l'âge à un profil enfant : son temps par jour ; et, si la TV n'a pas encore de couvre-feu,
     * celui du plus jeune enfant (le couvre-feu est commun à toute la TV). Renvoie le préréglage utilisé, ou null.
     */
    fun applyPreset(rules: BoxRules, profile: Profile): AgePreset? {
        if (profile.unlimited) return null
        val age = profile.age() ?: return null
        val p = presetFor(age)
        profile.dailyTotalMin = p.dailyMin
        bedtimeIfNone(rules, profile)
        return p
    }

    /** Âge du plus jeune enfant (profils + [extra]), ou null. */
    fun youngestAge(rules: BoxRules, extra: Profile? = null): Int? =
        (rules.profiles + listOfNotNull(extra)).filter { !it.unlimited }.mapNotNull { it.age() }.minOrNull()

    /** Couvre-feu conseillé pour la TV : celui du plus jeune enfant, ou null sans âge connu. */
    fun suggestedBedtime(rules: BoxRules, extra: Profile? = null): Int? = youngestAge(rules, extra)?.let { presetFor(it).bedtime }

    /** Sans couvre-feu, met celui du plus jeune enfant. Renvoie true s'il a été ajouté. */
    fun bedtimeIfNone(rules: BoxRules, extra: Profile? = null): Boolean {
        if (rules.bedtimeEnabled) return false
        val b = suggestedBedtime(rules, extra) ?: return false
        rules.bedtimeEnabled = true; rules.bedtimeStart = b; rules.bedtimeEnd = BEDTIME_END
        return true
    }

    enum class Level { CONFLICT, WARNING, INFO }

    /** Une correction proposée : son libellé et ce qu'elle change dans les règles. */
    class Fix(val label: String, private val block: (BoxRules) -> Unit) {
        fun applyTo(r: BoxRules) = block(r)
    }

    data class Advice(val id: String, val level: Level, val title: String, val message: String, val fixes: List<Fix> = emptyList())

    private fun hm(m: Int) = RulesEngine.hhmm(m)
    private fun dur(min: Int) = RulesEngine.fmt(min * 60_000L)

    private fun activeAt(r: BoxRules, m: Int) = RulesEngine.inWindow(m, r.activeFrom, r.activeTo)
    private fun bedAt(r: BoxRules, m: Int) = r.bedtimeEnabled && r.bedtimeStart != r.bedtimeEnd && RulesEngine.inWindow(m, r.bedtimeStart, r.bedtimeEnd)

    /** Minutes par jour pendant lesquelles les règles s'appliquent ET la TV est permise (hors couvre-feu). */
    fun controlledMinutes(r: BoxRules): Int = (0 until AppRule.DAY_MIN).count { activeAt(r, it) && !bedAt(r, it) }

    /**
     * Analyse les règles. [appName] donne le nom d'une appli ; [knownApps] : les applis de la TV (null = inconnues).
     * Les conseils sont triés : contradictions d'abord, puis avertissements, puis informations.
     */
    fun check(r: BoxRules, appName: (String) -> String = { it }, knownApps: Collection<String>? = null): List<Advice> {
        val out = mutableListOf<Advice>()
        val children = r.profiles.filter { !it.unlimited }

        // 1. Aucun jour choisi : les règles ne s'appliquent jamais
        if (r.activeDays and AppRule.ALL_DAYS == 0) out += Advice("no_days", Level.CONFLICT, "Les règles ne s'appliquent aucun jour",
            "Aucun jour n'est coché : la TV est en temps libre toute la semaine.", listOf(Fix("Tous les jours") { it.activeDays = AppRule.ALL_DAYS }))

        val window = r.activeFrom != r.activeTo
        val controlled = controlledMinutes(r)

        // 2. Le couvre-feu commence avant la fin des règles : la fin de la plage et le temps libre du soir ne servent pas
        if (window && r.bedtimeEnabled && r.bedtimeStart != r.bedtimeEnd && r.bedtimeStart != r.activeFrom &&
            activeAt(r, r.bedtimeStart) && r.bedtimeStart != r.activeTo) {
            val bs = r.bedtimeStart; val end = r.activeTo
            out += Advice("bedtime_before_end", Level.CONFLICT, "Deux règles se contredisent",
                "Couvre-feu à ${hm(bs)}, mais TV permise jusqu'à ${hm(end)}. Dans les faits, la TV sera coupée dès ${hm(bs)}.",
                listOf(Fix("Garder ${hm(bs)}") { it.activeTo = bs }, Fix("Passer à ${hm(end)}") { it.bedtimeStart = end }))
        }

        // 3. Le couvre-feu recouvre toutes les heures de règles : la TV n'est jamais permise pendant les règles
        if (window && r.bedtimeEnabled && controlled == 0) out += Advice("bedtime_all", Level.CONFLICT, "La TV n'est jamais permise",
            "Le couvre-feu (${hm(r.bedtimeStart)} – ${hm(r.bedtimeEnd)}) couvre toutes les heures où les règles s'appliquent.",
            listOf(Fix("Retirer le couvre-feu") { it.bedtimeEnabled = false }))

        // 4. Une limite par jour plus longue que le temps où elle peut servir
        if (controlled > 0 && (window || r.bedtimeEnabled)) {
            val limits = mutableListOf<Pair<Profile?, Int>>()
            if (r.dailyTotalMin > 0 && (children.isEmpty() || children.any { it.dailyTotalMin == 0 })) limits += null to r.dailyTotalMin
            children.filter { it.dailyTotalMin > 0 }.forEach { limits += it to it.dailyTotalMin }
            limits.filter { it.second >= controlled }.forEach { (p, min) ->
                val who = p?.name ?: "La TV"
                val target = ((controlled - 15) / 15 * 15).coerceAtLeast(15)
                val fixes = mutableListOf(Fix("Limiter à ${dur(target)}") { rr ->
                    if (p == null) { rr.dailyTotalMin = target } else { rr.profiles.firstOrNull { it.id == p.id }?.dailyTotalMin = target }
                })
                if (window) fixes += Fix("Règles toute la journée") { rr -> rr.activeFrom = 0; rr.activeTo = 0 }
                out += Advice("limit_useless_${p?.id ?: "all"}", Level.WARNING, if (p == null) "La limite par jour ne sert pas" else "Une règle de ${p.name} ne tient pas",
                    "$who a droit à ${dur(min)}, mais la TV n'est contrôlée que ${dur(controlled)} par jour" +
                        (if (window) " (de ${hm(r.activeFrom)} à ${hm(r.activeTo)})" else "") + " : cette limite ne sera jamais atteinte.", fixes)
            }
        }

        // 5. Programmes plus permissifs que l'âge de l'enfant
        children.forEach { p ->
            val age = p.age() ?: return@forEach
            if (p.maxAge > age) out += Advice("age_${p.id}", Level.WARNING, "${p.name} : programmes au-dessus de son âge",
                "${p.name} a $age ans mais peut voir des programmes jusqu'à ${p.maxAge} ans.",
                listOf(Fix("Selon son âge") { rr -> rr.profiles.firstOrNull { it.id == p.id }?.maxAge = 0 }))
        }

        // 6. Une limite d'appli plus longue que le temps de TV total : elle ne sert à rien
        val maxDaily = (listOf(r.dailyTotalMin) + children.map { it.dailyTotalMin }).let { all ->
            if (all.any { it == 0 } && r.dailyTotalMin == 0) 0 else all.filter { it > 0 }.maxOrNull() ?: 0
        }
        if (maxDaily > 0) r.apps.filter { (_, a) -> !a.blocked && a.limitMin > 0 && a.limitMin >= maxDaily }.forEach { (pkg, a) ->
            out += Advice("app_over_${pkg}", Level.INFO, "La limite de ${appName(pkg)} ne sert pas",
                "${appName(pkg)} a droit à ${dur(a.limitMin)}, mais le temps de TV total est de ${dur(maxDaily)} par jour.",
                listOf(Fix("Limiter à ${dur((maxDaily / 2 / 15 * 15).coerceAtLeast(15))}") { rr -> rr.apps[pkg]?.limitMin = (maxDaily / 2 / 15 * 15).coerceAtLeast(15) }))
        }

        // 7. YouTube n'a pas de classement par âge : la classification des programmes ne le protège pas
        val young = children.filter { (it.age() ?: 99) < 13 }
        val yt = r.apps[YOUTUBE]
        if (young.isNotEmpty() && yt?.blocked != true && (knownApps == null || YOUTUBE in knownApps)) {
            val names = young.joinToString(" et ") { it.name }
            out += Advice("youtube_rating", Level.INFO, "YouTube n'a pas de classement par âge",
                "La classification des programmes ne s'applique pas aux vidéos YouTube : $names pourra y voir des vidéos non adaptées. YouTube Kids reste possible.",
                listOf(Fix("Bloquer YouTube") { rr -> rr.apps[YOUTUBE] = (rr.apps[YOUTUBE] ?: AppRule()).copy(blocked = true) }))
        }

        // 8. Couvre-feu commun alors que les enfants ont des âges éloignés
        val ages = children.mapNotNull { p -> p.age()?.let { p to it } }
        if (r.bedtimeEnabled && ages.size >= 2) {
            val (y, ya) = ages.minByOrNull { it.second }!!; val (o, oa) = ages.maxByOrNull { it.second }!!
            if (presetFor(oa).bedtime - presetFor(ya).bedtime >= 60) out += Advice("shared_bedtime", Level.INFO, "Un seul couvre-feu pour toute la TV",
                "${y.name} ($ya ans) et ${o.name} ($oa ans) ont le même couvre-feu (${hm(r.bedtimeStart)}). Pour ${o.name}, vous pourrez accepter ses demandes de temps depuis le téléphone.")
        }

        return out.sortedBy { it.level.ordinal }
    }
}
