package fr.paul.telecommande.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.paul.telecommande.R
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.DiagLog
import fr.paul.telecommande.core.DriverHub
import fr.paul.telecommande.core.RemoteKey
import fr.paul.telecommande.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * Télécommande sans ouvrir l'appli : lecture/pause, volume et muet dans le volet de notifications
 * (et sur l'écran verrouillé). Affichée quand on quitte l'écran télécommande, masquée quand on y revient.
 */
object ControlNotification {
    private const val CHANNEL = "controle"
    private const val ID = 7
    const val ACTION = "fr.paul.telecommande.CONTROL"

    fun enabled(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("control_notif", true)
    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("control_notif", on).apply()
        if (!on) hide(c)
    }

    fun show(c: Context, deviceId: String) {
        if (!enabled(c)) return
        val d = DeviceStore(c).get(deviceId) ?: return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Contrôle rapide de la TV", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Lecture/pause, volume et muet sans ouvrir l'appli"; setShowBadge(false)
            })
        fun act(key: String, icon: Int, label: String, req: Int) = NotificationCompat.Action(icon, label,
            PendingIntent.getBroadcast(c, req, Intent(c, ControlReceiver::class.java).setAction(ACTION)
                .putExtra("key", key).putExtra("id", deviceId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        val open = PendingIntent.getActivity(c, 70, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(d.name)
            .setContentText("Télécommande rapide")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(act("PLAY_PAUSE", R.drawable.ic_playpause, "Lecture / pause", 71))
            .addAction(act("VOL_DOWN", R.drawable.ic_vol_down, "Volume −", 72))
            .addAction(act("VOL_UP", R.drawable.ic_vol_up, "Volume +", 73))
            .addAction(act("MUTE", R.drawable.ic_mute, "Muet", 74))
            .addAction(act("close", R.drawable.ic_close, "Fermer", 75))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
        runCatching { NotificationManagerCompat.from(c).notify(ID, n) }
    }

    fun hide(c: Context) = runCatching { NotificationManagerCompat.from(c).cancel(ID) }
}

class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        if (key == "close") { ControlNotification.hide(c); return }
        val id = intent.getStringExtra("id") ?: return
        val d = DeviceStore(c).get(id) ?: return
        val k = runCatching { RemoteKey.valueOf(key) }.getOrNull() ?: return
        val done = goAsync()
        DriverHub.scope.launch {
            runCatching {
                val drv = DriverHub.driver(c, d)
                drv.connect(DriverHub.SilentPairing)
                drv.send(k)
            }.onFailure { DiagLog.log("CONTROLE", "Touche $key non envoyée : ${it.message}") }
            done.finish()
        }
    }
}
