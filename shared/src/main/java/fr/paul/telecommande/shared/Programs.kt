package fr.paul.telecommande.shared

import java.text.Normalizer

/**
 * Filtrage par programme : titres, chaînes YouTube ou mots interdits.
 * Comparaison sans majuscules ni accents, et par mots entiers : « chat » ne bloque pas « château ».
 */
object ProgramFilter {
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Le mot ou l'expression interdit trouvé dans le titre, la chaîne ou le sous-titre ; null si rien. */
    fun match(blocked: List<String>, vararg fields: String?): String? {
        val hay = " " + fields.filterNotNull().joinToString(" ") { normalize(it) } + " "
        if (hay.isBlank()) return null
        return blocked.firstOrNull { w ->
            val n = normalize(w)
            n.isNotEmpty() && hay.contains(" $n ")
        }
    }

    /** Clé d'un programme pour l'autoriser exceptionnellement (le titre normalisé). */
    fun key(title: String) = normalize(title)
}

/** Classification lue à l'écran : âge minimum conseillé et contenus signalés (violence, peur…). */
data class Rating(val age: Int?, val contents: Set<String>)

/**
 * Lit la classification d'un programme dans les textes affichés par l'appli (fiche, début de lecture) :
 * « 16+ », « -12 », « Déconseillé aux moins de 10 ans », « TV-MA », « PEGI 18 », « Tous publics »…
 * et les contenus signalés à côté (« violence, langage grossier »).
 */
object RatingReader {
    /** Contenus reconnus : clé → mots qui les annoncent (normalisés, sans accents). */
    val CONTENTS = linkedMapOf(
        "violence" to listOf("violence", "violent", "violente", "gore", "sang"),
        "peur" to listOf("peur", "effrayant", "effrayante", "horreur", "epouvante", "frightening", "horror", "scary"),
        "sexe" to listOf("sexe", "sexuel", "sexuelle", "sexualite", "nudite", "nudity", "sex", "sexual"),
        "langage" to listOf("langage grossier", "grossierete", "grossieretes", "language", "langage"),
        "drogues" to listOf("drogue", "drogues", "drug", "drugs", "alcool", "tabac", "substances"),
        "suicide" to listOf("suicide", "automutilation", "self harm")
    )
    val CONTENT_LABELS = mapOf("violence" to "Violence", "peur" to "Peur, horreur", "sexe" to "Sexe, nudité",
        "langage" to "Langage grossier", "drogues" to "Drogues, alcool", "suicide" to "Suicide")

    private val AGES = setOf(3, 6, 7, 10, 12, 13, 14, 16, 17, 18)
    private val PLUS = Regex("(?<![\\d-])(\\d{1,2})\\s?\\+(?!\\d)")
    private val MINUS = Regex("(?<![\\w\\d])-\\s?(10|12|16|18)(?!\\d)")
    private val MOINS = Regex("moins de (\\d{1,2}) ans")
    private val PEGI = Regex("pegi (\\d{1,2})")
    private val TV = mapOf("tv ma" to 17, "tv 14" to 14, "tv pg" to 10, "tv y7" to 7, "tv y" to 0, "tv g" to 0)

    /** Texte de classification affiché par un lecteur au début d'une lecture (« CLASSÉ 7+ », « Rated TV-14 »…). */
    fun isPlayerCue(raw: String): Boolean {
        val n = " " + ProgramFilter.normalize(raw) + " "
        return CUE.containsMatchIn(n)
    }

    /**
     * Sans titre connu : seule la classification annoncée par le lecteur compte (« Classé 7+ » et les 2 lignes qui suivent,
     * « violence légère »), pas les badges des pages de navigation (« Série · 2026 · 7+ » sous un titre en vedette).
     */
    fun playerTexts(texts: List<String>): List<String> {
        val out = mutableListOf<String>()
        texts.forEachIndexed { i, t -> if (isPlayerCue(t)) out += texts.subList(i, minOf(i + 3, texts.size)) }
        return out.distinct()
    }
    private val CUE = Regex(" (classe (tous publics|\\d)|rated|deconseille aux moins|tv (ma|14|pg|y7|y|g)) ")

    fun read(texts: List<String>): Rating {
        var age: Int? = null
        fun take(a: Int) { if (a == 0 || a in AGES) age = maxOf(age ?: 0, a) }
        val contents = mutableSetOf<String>()
        for (raw in texts) {
            if (raw.isBlank()) continue
            PLUS.findAll(raw).forEach { take(it.groupValues[1].toInt()) }
            MINUS.findAll(raw).forEach { take(it.groupValues[1].toInt()) }
            val n = ProgramFilter.normalize(raw)
            MOINS.findAll(n).forEach { take(it.groupValues[1].toInt()) }
            PEGI.findAll(n).forEach { take(it.groupValues[1].toInt()) }
            val padded = " $n "
            TV.forEach { (k, v) -> if (padded.contains(" $k ")) take(v) }
            if (padded.contains(" tous publics ") || padded.contains(" tout public ")) take(0)
            CONTENTS.forEach { (key, words) -> if (words.any { padded.contains(" $it ") }) contents += key }
        }
        return Rating(age, contents)
    }
}

/** Pourquoi un programme est interdit pour ce profil, ou null. */
object ProgramRules {
    /** Le parent a « toujours autorisé » ce titre, pour cet enfant ou pour toute la famille. */
    fun alwaysAllowed(rules: BoxRules, profile: Profile?, title: String?): Boolean {
        val k = title?.let { ProgramFilter.key(it) }?.takeIf { it.isNotEmpty() } ?: return false
        return rules.allowedPrograms.any { it.key == k && (it.profile.isEmpty() || it.profile == profile?.id) }
    }

    /** Film ou série dont la classification reste introuvable : null = rien à faire, sinon "ask" ou "block". */
    fun unknownAction(rules: BoxRules, profile: Profile?, pkg: String, title: String?, rating: Rating?): String? {
        if (rating?.age != null || profile?.unlimited == true || !FilmApps.isFilmApp(pkg)) return null
        if (alwaysAllowed(rules, profile, title)) return null
        return rules.unknownPolicy.takeIf { it != "allow" }
    }

    fun blockReason(rules: BoxRules, profile: Profile?, title: String?, subtitle: String?, rating: Rating?): String? {
        if (alwaysAllowed(rules, profile, title)) return null
        ProgramFilter.match(rules.blockedPrograms, title, subtitle)?.let { return "« $it » fait partie des programmes interdits." }
        val maxAge = profile?.programMaxAge()?.takeIf { it > 0 } ?: rules.maxAge
        val age = rating?.age
        if (maxAge > 0 && age != null && age > maxAge) return "Programme déconseillé aux moins de $age ans (autorisé jusqu'à $maxAge+)."
        val banned = rating?.contents.orEmpty().intersect(rules.blockedContents.toSet())
        if (banned.isNotEmpty()) return "Contenu signalé : " + banned.joinToString(", ") { RatingReader.CONTENT_LABELS[it] ?: it }.lowercase() + "."
        return null
    }
}

/** Titre « toujours autorisé » : [key] = titre normalisé ; [profile] vide = toute la famille. */
data class AllowedProgram(val key: String, val title: String, val profile: String = "") {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().put("key", key).put("title", title).put("profile", profile)
    companion object {
        fun fromJson(o: org.json.JSONObject) = o.optString("key").takeIf { it.isNotBlank() }?.let { AllowedProgram(it, o.optString("title"), o.optString("profile")) }
    }
}

/** Applis de films et séries : la règle « sans classification » ne s'applique qu'à elles (pas à YouTube ni à la TV en direct). */
object FilmApps {
    private val HINTS = listOf("netflix", "disney", "amazonvideo", "primevideo", "canal", "appletv", "atve", "hbo", "wbd", "paramount",
        "ocs", "rakuten", "crunchyroll", "starz", "mubi", "universcine", "filmo", "adn")
    fun isFilmApp(pkg: String): Boolean = pkg.lowercase().let { p -> HINTS.any { p.contains(it) } }
}

/** Mots des boutons qui lancent une lecture (Netflix, Disney+, Prime Video… en français et en anglais). */
object PlayButtons {
    /** Noms d'applis qui commencent par « Play » (Play Store, Play Jeux…) : jamais un bouton de lecture. */
    private val NOT_PLAY = Regex("^play (store|games|jeux|films|movies|musique|music|livres|books)\\b")
    private val WORDS = Regex("^(lecture|lire|regarder|reprendre|reprendre la lecture|commencer|lancer|play|resume|watch|watch now|start|continuer)\\b")
    fun isPlay(label: String?): Boolean {
        val n = ProgramFilter.normalize(label ?: "")
        if (NOT_PLAY.containsMatchIn(n)) return false
        return n.isNotEmpty() && n.length <= 60 && (WORDS.containsMatchIn(n) || Regex("\\b(episode|ep) \\d+ (lecture|play)\\b").containsMatchIn(n))
    }
}
