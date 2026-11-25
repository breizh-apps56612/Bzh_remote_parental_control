package fr.paul.telecommande.youtube

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Titre, chaîne et miniature de la vidéo YouTube en cours (oEmbed public, sans clé d'API). */
object YtMeta {
    data class Info(val title: String, val author: String)

    private val http = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private val infos = LruCache<String, Info>(50)
    private val thumbs = LruCache<String, Bitmap>(12)

    fun cached(videoId: String): Info? = infos.get(videoId)
    fun cachedThumb(videoId: String): Bitmap? = thumbs.get(videoId)

    suspend fun info(videoId: String): Info? {
        infos.get(videoId)?.let { return it }
        return withContext(Dispatchers.IO) {
            val url = "https://www.youtube.com/oembed".toHttpUrl().newBuilder()
                .addQueryParameter("url", "https://www.youtube.com/watch?v=$videoId")
                .addQueryParameter("format", "json").build()
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val o = JSONObject(r.body?.string().orEmpty())
                    Info(o.optString("title"), o.optString("author_name"))
                }
            }.getOrNull()?.also { infos.put(videoId, it) }
        }
    }

    suspend fun thumbnail(videoId: String): Bitmap? {
        thumbs.get(videoId)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url("https://i.ytimg.com/vi/$videoId/mqdefault.jpg").build()).execute().use { r ->
                    if (!r.isSuccessful) null else r.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                }
            }.getOrNull()?.also { thumbs.put(videoId, it) }
        }
    }
}
