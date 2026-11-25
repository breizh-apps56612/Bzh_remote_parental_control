package fr.paul.telecommande.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Vraies icônes des applis installées sur la TV (fournies par l'appli Box, telles que la TV les affiche).
 * Gardées sur le téléphone, rafraîchies au plus toutes les 12 h.
 */
object AppIcons {
    data class App(val pkg: String, val label: String, val wide: Boolean)

    private val mem = HashMap<String, Bitmap?>()

    private fun dir(c: Context, deviceId: String) = File(c.filesDir, "appicons/$deviceId").apply { mkdirs() }

    fun apps(c: Context, deviceId: String): List<App> = runCatching {
        val a = JSONArray(File(dir(c, deviceId), "apps.json").readText())
        (0 until a.length()).map { a.getJSONObject(it).let { o -> App(o.getString("pkg"), o.getString("label"), o.optBoolean("wide")) } }
    }.getOrDefault(emptyList())

    fun icon(c: Context, deviceId: String, pkg: String): Bitmap? = mem.getOrPut("$deviceId/$pkg") {
        File(dir(c, deviceId), "$pkg.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    }

    fun stale(c: Context, deviceId: String) =
        System.currentTimeMillis() - File(dir(c, deviceId), "apps.json").lastModified() > 12 * 3600_000L

    /** Télécharge la liste et les icônes depuis l'appli Box. */
    suspend fun refresh(c: Context, d: Device): Boolean {
        if (!d.hasBoxApp) return false
        val res = runCatching { BoxClient.of(c, d).call("apps", JSONObject().put("icons", true)) }.getOrNull() ?: return false
        val arr = res.optJSONArray("apps") ?: return false
        val out = JSONArray()
        val folder = dir(c, d.id)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pkg = o.optString("pkg"); var wide = false
            o.optString("icon").takeIf { it.isNotEmpty() }?.let { b64 ->
                runCatching {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    File(folder, "$pkg.png").writeBytes(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp -> wide = bmp.width > bmp.height * 1.3; mem["${d.id}/$pkg"] = bmp }
                }
            }
            out.put(JSONObject().put("pkg", pkg).put("label", o.optString("label")).put("wide", wide))
        }
        File(folder, "apps.json").writeText(out.toString())
        return true
    }

    /** Applis à mettre en avant : les grandes plateformes vidéo d'abord, puis les autres. */
    private val FAVORITES = listOf("com.google.android.youtube.tv", "com.netflix.ninja", "com.disney.disneyplus",
        "com.amazon.amazonvideo.livingroom", "net.oqee.androidtv", "com.canal.android.canal", "fr.francetv.pluzz",
        "fr.m6.m6replay.by", "fr.tf1.mytf1", "com.google.android.youtube.tvkids", "com.spotify.tv.android", "tv.twitch.android.app")

    fun featured(c: Context, deviceId: String, max: Int = 10): List<App> {
        val all = apps(c, deviceId)
        return (FAVORITES.mapNotNull { f -> all.firstOrNull { it.pkg == f } } + all).distinctBy { it.pkg }.take(max)
    }
}
