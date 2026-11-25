package fr.paul.telecommande.box

import android.content.Context
import android.content.pm.PackageManager
import android.media.tv.TvContentRating
import android.media.tv.TvContract
import android.os.Build

/**
 * Guide des programmes d'Android TV (facultatif, autorisation « guide des programmes ») : pour les chaînes qui y rangent
 * leur grille, chaque programme peut porter sa classification officielle (signalétique -10, -12, -16…) et son genre.
 * Lecture seule, rien ne sort de la box.
 */
object TvGuide {
    const val PERMISSION = "android.permission.READ_TV_LISTINGS"

    data class Program(val title: String, val episode: String, val channel: String, val ratings: List<String>, val genres: String) {
        /** Âge lu dans la classification (« FR_DVB_12 » → 12, « …_ALL » / « …_U » → 0). */
        val age: Int? get() = ratings.mapNotNull { r ->
            val last = r.substringBefore('/').substringAfterLast('_').uppercase()   // classification principale, sans les sous-classifications
            last.toIntOrNull()?.takeIf { it in 0..21 } ?: if (last in setOf("ALL", "U", "G", "TP", "TV_G", "TV_Y")) 0 else null
        }.maxOrNull()
    }

    fun available() = Build.VERSION.SDK_INT >= 26

    fun allowed(ctx: Context) = available() && ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Programmes en cours sur toutes les chaînes du guide (300 au plus). */
    fun onAirNow(ctx: Context): List<Program> {
        if (!allowed(ctx)) return emptyList()
        val now = System.currentTimeMillis()
        val channels = HashMap<Long, String>()
        runCatching {
            ctx.contentResolver.query(TvContract.Channels.CONTENT_URI, arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) channels[c.getLong(0)] = c.getString(1) ?: ""
            }
        }
        val out = mutableListOf<Program>()
        runCatching {
            ctx.contentResolver.query(TvContract.Programs.CONTENT_URI,
                arrayOf(TvContract.Programs.COLUMN_CHANNEL_ID, TvContract.Programs.COLUMN_TITLE, TvContract.Programs.COLUMN_EPISODE_TITLE,
                    TvContract.Programs.COLUMN_CONTENT_RATING, TvContract.Programs.COLUMN_BROADCAST_GENRE, TvContract.Programs.COLUMN_CANONICAL_GENRE,
                    TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS),
                "${TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS}<=? AND ${TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS}>?",
                arrayOf(now.toString(), now.toString()), null)?.use { c ->
                while (c.moveToNext() && out.size < 300) {
                    val ratings = c.getString(3)?.split(',')?.mapNotNull { f ->
                        runCatching { TvContentRating.unflattenFromString(f.trim()).let { it.mainRating + it.subRatings.joinToString("") { s -> "/$s" } } }.getOrNull()
                    } ?: emptyList()
                    out += Program(c.getString(1) ?: "", c.getString(2) ?: "", channels[c.getLong(0)] ?: "", ratings,
                        listOfNotNull(c.getString(4), c.getString(5)).joinToString(" "))
                }
            }
        }
        return out
    }

    /** Le programme du guide qui porte ce titre, s'il est diffusé en ce moment. */
    fun find(ctx: Context, title: String?): Program? {
        val k = title?.let { fr.paul.telecommande.shared.ProgramFilter.key(it) }?.takeIf { it.isNotEmpty() } ?: return null
        return onAirNow(ctx).firstOrNull { fr.paul.telecommande.shared.ProgramFilter.key(it.title) == k }
    }

    /** Résumé pour le test : chaînes et programmes vus, et combien portent une classification. */
    fun summary(ctx: Context): String {
        if (!available()) return "non proposé (Android trop ancien)"
        if (!allowed(ctx)) return "autorisation non accordée"
        val now = onAirNow(ctx)
        if (now.isEmpty()) return "vide (aucune chaîne ne range sa grille dans le guide d'Android TV)"
        val rated = now.filter { it.ratings.isNotEmpty() }
        return "${now.size} programmes en cours, ${rated.size} avec classification" +
            (rated.take(3).takeIf { it.isNotEmpty() }?.joinToString("; ", " · ex. ") { "${it.channel} : ${it.title} = ${it.ratings.joinToString(",")}" } ?: "")
    }
}
