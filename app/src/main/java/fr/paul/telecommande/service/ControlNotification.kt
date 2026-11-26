package fr.paul.telecommande.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.VolumeProviderCompat
import fr.paul.telecommande.R
import fr.paul.telecommande.core.AppCatalog
import fr.paul.telecommande.core.AppIcons
import fr.paul.telecommande.core.BoxClient
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.DiagLog
import fr.paul.telecommande.core.DriverHub
import fr.paul.telecommande.core.RemoteKey
import fr.paul.telecommande.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Télécommande dans le lecteur multimédia des réglages rapides d'Android : lecture/pause, −10 s / +10 s, muet,
 * ce qui passe sur la TV, et les boutons de volume du téléphone qui règlent la TV même appli fermée.
 */
object ControlNotification {
    const val CHANNEL = "controle"
    const val ID = 7
    const val ACTION = "fr.paul.telecommande.CONTROL"

    fun enabled(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("control_notif", true)
    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("control_notif", on).apply()
        if (!on) hide(c)
    }

    /** À appeler quand l'appli est visible (Android interdit de lancer ce service depuis l'arrière-plan). */
    fun show(c: Context, deviceId: String) {
        if (!enabled(c) || DeviceStore(c).get(deviceId) == null) return
        runCatching { ContextCompat.startForegroundService(c, Intent(c, TvControlService::class.java).putExtra("id", deviceId)) }
            .onFailure { DiagLog.log("CONTROLE", "Lecteur des réglages rapides non lancé : ${it.message}") }
    }

    fun hide(c: Context) { runCatching { c.stopService(Intent(c, TvControlService::class.java)) } }

    fun createChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Contrôle rapide de la TV", NotificationManager.IMPORTANCE_LOW).apply {
                description = "La télécommande dans le lecteur des réglages rapides"; setShowBadge(false)
            })
    }

    /** Envoie une touche à la TV (connexion partagée avec l'écran télécommande). */
    fun send(c: Context, d: Device, key: RemoteKey) {
        DriverHub.scope.launch {
            runCatching { val drv = DriverHub.driver(c, d); drv.connect(DriverHub.SilentPairing); drv.send(key) }
                .onFailure { DiagLog.log("CONTROLE", "Touche $key non envoyée : ${it.message}") }
        }
    }
}

class TvControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: MediaSessionCompat? = null
    private var device: Device? = null
    private var playing = true
    private var title = ""
    private var pkg = ""
    private var poll: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ControlNotification.createChannel(this)
        val id = intent?.getStringExtra("id") ?: device?.id
        val d = id?.let { DeviceStore(this).get(it) }
        if (d == null) { stopSelf(); return START_NOT_STICKY }
        device = d
        if (session == null) session = MediaSessionCompat(this, "telecommande").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = toggle()
                override fun onPause() = toggle()
                override fun onSkipToPrevious() = key(RemoteKey.REWIND)
                override fun onSkipToNext() = key(RemoteKey.FAST_FORWARD)
                override fun onRewind() = key(RemoteKey.REWIND)
                override fun onFastForward() = key(RemoteKey.FAST_FORWARD)
                override fun onStop() { stopSelf() }
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    when (action) {
                        "mute" -> key(RemoteKey.MUTE)
                        "remote" -> startActivity(Intent(this@TvControlService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            })
            // Boutons de volume du téléphone → volume de la TV, même appli fermée
            setPlaybackToRemote(object : VolumeProviderCompat(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    if (direction > 0) key(RemoteKey.VOL_UP) else if (direction < 0) key(RemoteKey.VOL_DOWN)
                }
            })
            isActive = true
        }
        if (title.isBlank()) title = d.name
        update()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, ControlNotification.ID, notification(), type)
        if (poll?.isActive != true) poll = scope.launch { while (isActive) { refresh(); delay(15_000) } }
        return START_NOT_STICKY
    }

    private fun key(k: RemoteKey) { device?.let { ControlNotification.send(this, it, k) } }

    private fun toggle() { key(RemoteKey.PLAY_PAUSE); playing = !playing; update(); notifyAgain() }

    /** Ce qui passe sur la TV, d'après l'appli Box (titre du programme, sinon nom de l'appli). */
    private suspend fun refresh() {
        val d = device ?: return
        if (!d.hasBoxApp) return
        val st = runCatching { BoxClient.of(this, d).call("status") }.getOrNull() ?: return
        val p = st.optString("pkg"); val media = st.optJSONObject("media")
        val t = media?.optString("title")?.takeIf { it.isNotBlank() } ?: st.optString("label").ifBlank { AppCatalog.name(p) }
        if (t != title || p != pkg) { title = t; pkg = p; update(); notifyAgain() }
    }

    private fun update() {
        val s = session ?: return
        val d = device ?: return
        val meta = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title.ifBlank { d.name })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, d.name)
        if (pkg.isNotBlank()) AppIcons.icon(this, d.id, pkg)?.let { meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it) }
        s.setMetadata(meta.build())
        s.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_STOP)
            .addCustomAction(PlaybackStateCompat.CustomAction.Builder("mute", "Muet", R.drawable.ic_mute).build())
            .addCustomAction(PlaybackStateCompat.CustomAction.Builder("remote", "Télécommande", R.drawable.ic_remote).build())
            .setState(if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build())
    }

    private fun pending(k: String, req: Int) = PendingIntent.getBroadcast(this, req,
        Intent(this, ControlReceiver::class.java).setAction(ControlNotification.ACTION).putExtra("key", k).putExtra("id", device?.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(): android.app.Notification {
        val d = device!!
        val open = PendingIntent.getActivity(this, 70, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, ControlNotification.CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title.ifBlank { d.name })
            .setContentText(d.name)
            .setContentIntent(open)
            .setOnlyAlertOnce(true).setShowWhen(false).setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(NotificationCompat.Action(R.drawable.ic_back10, "−10 s", pending("REWIND", 71)))
            .addAction(NotificationCompat.Action(R.drawable.ic_playpause, "Lecture / pause", pending("toggle", 72)))
            .addAction(NotificationCompat.Action(R.drawable.ic_fwd10, "+10 s", pending("FAST_FORWARD", 73)))
            .addAction(NotificationCompat.Action(R.drawable.ic_mute, "Muet", pending("MUTE", 74)))
            .addAction(NotificationCompat.Action(R.drawable.ic_close, "Fermer", pending("close", 75)))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session?.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun notifyAgain() { runCatching { getSystemService(NotificationManager::class.java)?.notify(ControlNotification.ID, notification()) } }

    override fun onDestroy() {
        scope.cancel()
        session?.run { isActive = false; release() }
        session = null
        super.onDestroy()
    }
}

class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        if (key == "close") { ControlNotification.hide(c); return }
        val id = intent.getStringExtra("id") ?: return
        val d = DeviceStore(c).get(id) ?: return
        val k = if (key == "toggle") RemoteKey.PLAY_PAUSE else runCatching { RemoteKey.valueOf(key) }.getOrNull() ?: return
        ControlNotification.send(c, d, k)
    }
}
