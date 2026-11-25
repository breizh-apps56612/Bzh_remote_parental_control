package fr.paul.telecommande.box

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Autorisation « accès aux notifications » (facultative) : sert uniquement à lire le titre en cours. */
class MediaListener : NotificationListenerService()

object Media {
    fun allowed(ctx: Context): Boolean =
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.contains(ctx.packageName) == true

    /** Titre, artiste/chaîne, image et progression de ce qui est lu (si l'appli le publie). */
    fun nowPlaying(ctx: Context): JSONObject? {
        if (!allowed(ctx)) return null
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
        val sessions = runCatching { msm.getActiveSessions(ComponentName(ctx, MediaListener::class.java)) }.getOrNull() ?: return null
        val c = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull() ?: return null
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val o = JSONObject().put("pkg", c.packageName).put("title", title)
            .put("subtitle", md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
            .put("duration", md.getLong(MediaMetadata.METADATA_KEY_DURATION))
            .put("position", c.playbackState?.position ?: 0L)
            .put("playing", c.playbackState?.state == PlaybackState.STATE_PLAYING)
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        art?.let { o.put("art", thumb(it)) }
        return o
    }

    private fun thumb(b: Bitmap): String {
        val w = 240; val h = (b.height * w / b.width.coerceAtLeast(1)).coerceIn(1, 400)
        val s = Bitmap.createScaledBitmap(b, w, h, true)
        val out = ByteArrayOutputStream()
        s.compress(Bitmap.CompressFormat.JPEG, 75, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

/** Administrateur de l'appareil (facultatif) : empêche la désinstallation directe de l'appli. */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Le contrôle parental de cette TV ne fonctionnera plus. Le parent sera prévenu sur son téléphone."
}
