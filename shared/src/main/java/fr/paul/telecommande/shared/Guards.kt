package fr.paul.telecommande.shared

/**
 * Anti-devinette du code parent sur la TV.
 * 5 essais libres, puis attente croissante : 1 min, 2, 4, 8… jusqu'à 1 h par essai faux.
 * L'état (nombre d'échecs, fin du blocage) est gardé sur le disque par l'appelant :
 * un redémarrage de la box ne remet pas le compteur à zéro.
 */
class PinThrottle(
    private val load: () -> Pair<Int, Long>,          // (échecs, bloqué jusqu'à)
    private val save: (Int, Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** Temps d'attente restant avant de pouvoir réessayer (0 = libre). */
    fun lockedForMs(): Long {
        val (_, until) = load()
        val left = until - clock()
        // Horloge reculée (ou valeur absurde) : jamais plus que le palier maximum
        return if (left <= 0) 0 else minOf(left, MAX_LOCK_MS)
    }

    /** Vérifie [ok] seulement si aucun blocage n'est en cours, et met à jour le compteur. */
    fun attempt(ok: () -> Boolean): Result {
        val locked = lockedForMs()
        if (locked > 0) return Result.Locked(locked)
        if (ok()) { save(0, 0); return Result.Ok }
        val fails = load().first + 1
        val lock = lockFor(fails)
        save(fails, if (lock > 0) clock() + lock else 0)
        return if (lock > 0) Result.Locked(lock, fails) else Result.Wrong(FREE_TRIES - fails, fails)
    }

    sealed class Result {
        object Ok : Result()
        data class Wrong(val triesLeft: Int, val fails: Int) : Result()
        data class Locked(val ms: Long, val fails: Int = 0) : Result()
    }

    companion object {
        const val FREE_TRIES = 5
        const val MAX_LOCK_MS = 60 * 60_000L

        fun lockFor(fails: Int): Long {
            if (fails < FREE_TRIES) return 0
            val step = (fails - FREE_TRIES).coerceAtMost(6)   // 1, 2, 4, 8, 16, 32, 60 min
            return minOf(60_000L shl step, MAX_LOCK_MS)
        }

        fun message(r: Result): String = when (r) {
            Result.Ok -> ""
            is Result.Wrong -> if (r.triesLeft <= 2) "Code incorrect. Encore ${r.triesLeft} essai${if (r.triesLeft > 1) "s" else ""} avant une pause." else "Code incorrect, réessayez."
            is Result.Locked -> "Trop d'essais. Réessayez dans ${RulesEngine.fmt(r.ms)}, ou validez sur le téléphone du parent."
        }
    }
}

object GraceKey {
    /**
     * Clé de la « fin d'épisode », accordée une seule fois par clé et par jour.
     * - Limite d'une appli : une fois par appli (changer d'appli est permis, la limite ne concerne qu'elle).
     * - Temps total du jour : une seule fois pour toutes les applis, sinon passer de Netflix à YouTube
     *   puis à Disney+ redonnerait 10 minutes à chaque fois.
     */
    fun of(day: Long, profileId: String, pkg: String, kind: String): String =
        if (kind == Verdict.KIND_TOTAL) "$day|$profileId|*total" else "$day|$profileId|$pkg"
}

object ProtectedApps {
    val SETTINGS = setOf("com.android.tv.settings", "com.android.settings", "com.google.android.tv.settings")
    /** Installation / désinstallation d'applis (supprimer l'appli Box, installer une appli par Downloader…). */
    val INSTALLERS = setOf("com.android.packageinstaller", "com.google.android.packageinstaller")

    /** Paramètres du système, y compris ceux d'un fabricant (…settings, …tvsettings). */
    fun isSettings(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p in SETTINGS || p.endsWith(".settings") || p.endsWith(".tvsettings") || p.contains(".tv.settings")
    }

    fun isInstaller(pkg: String): Boolean = pkg.lowercase() in INSTALLERS || pkg.lowercase().endsWith(".packageinstaller")
}

object ProfileSwitch {
    /**
     * Faut-il le code parent pour regarder avec [target] ?
     * - profil parent (sans limite) : toujours, s'il y a un code ;
     * - même profil que la dernière fois, ou tout premier choix : non ;
     * - après le profil parent : non (on passe à des règles plus strictes) ;
     * - sinon (changer d'un enfant à un autre) : oui. Sans ça, un enfant qui a fini son temps
     *   éteint la TV, la rallume et choisit le profil de son frère ou de sa sœur.
     */
    fun needsPin(last: Profile?, target: Profile, hasPin: Boolean): Boolean {
        if (!hasPin) return false
        if (target.unlimited) return true
        if (last == null || last.id == target.id || last.unlimited) return false
        return true
    }
}
