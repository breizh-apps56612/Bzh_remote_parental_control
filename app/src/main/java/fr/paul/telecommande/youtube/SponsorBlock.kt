package fr.paul.telecommande.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class Segment(val start: Double, val end: Double, val category: String, val uuid: String)

/**
 * SponsorBlock : base collaborative des passages sponsorisés dans les vidéos YouTube.
 * La requête n'envoie que les 4 premiers caractères du hachage de l'identifiant de la vidéo
 * (confidentialité : le serveur ne sait pas quelle vidéo est regardée).
 */
object SponsorBlock {
    private const val API = "https://sponsor.ajay.app/api/skipSegments/"
    val CATEGORIES = listOf("sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "music_offtopic")

    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private val cache = object : LinkedHashMap<String, List<Segment>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Segment>>?) = size > 50
    }

    fun hashPrefix(videoId: String): String =
        MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray()).joinToString("") { "%02x".format(it) }.substring(0, 4)

    suspend fun segments(videoId: String): List<Segment> {
        synchronized(cache) { cache[videoId]?.let { return it } }
        val result = withContext(Dispatchers.IO) {
            val url = (API + hashPrefix(videoId)).toHttpUrl().newBuilder().apply {
                CATEGORIES.forEach { addQueryParameter("category", it) }
                addQueryParameter("actionType", "skip")
                addQueryParameter("service", "YouTube")
            }.build()
            runCatching {
                http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
                    if (r.code == 404) emptyList() else parse(r.body?.string().orEmpty(), videoId)
                }
            }.getOrDefault(emptyList())
        }
        synchronized(cache) { cache[videoId] = result }
        return result
    }

    fun parse(json: String, videoId: String): List<Segment> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Segment>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            if (v.optString("videoID") != videoId) continue
            val segs = v.optJSONArray("segments") ?: continue
            for (j in 0 until segs.length()) {
                val s = segs.optJSONObject(j) ?: continue
                val range = s.optJSONArray("segment") ?: continue
                val start = range.optDouble(0); val end = range.optDouble(1)
                if (end - start >= 1.0) out += Segment(start, end, s.optString("category"), s.optString("UUID"))
            }
        }
        return out.sortedBy { it.start }
    }

    /**
     * Prochain segment à sauter depuis la position donnée : celui dans lequel on se trouve
     * (en laissant 1 s de marge en fin de segment) ou le prochain à venir.
     */
    fun next(segments: List<Segment>, position: Double): Segment? =
        segments.firstOrNull { (position >= it.start && position < it.end - 1.0) || it.start > position }
}
