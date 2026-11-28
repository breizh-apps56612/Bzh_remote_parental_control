package fr.paul.telecommande.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fr.paul.telecommande.R
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.DriverHub
import fr.paul.telecommande.core.ParentalStore
import fr.paul.telecommande.core.TvMonitor
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
 * Garde l'assistant actif quand l'appli est fermée : passage automatique des pubs YouTube,
 * saut des sponsors et veille programmée. Une notification permanente l'indique (exigence Android).
 */
class AssistantService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, build("Assistant TV actif"), type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DeviceStore(this).all().forEach { DriverHub.stopAssistant(it.id) }
            if (TvMonitor.active()) return START_STICKY   // le suivi parental ne s'arrête que depuis l'appli (code PIN)
            stopSelf(); return START_NOT_STICKY
        }
        // Relance les assistants des box configurées (après redémarrage du processus par exemple)
        DeviceStore(this).all()
            .filter { it.isSmartBox && it.ytScreenId.isNotBlank() && (it.ytAutoSkipAds || it.ytSkipSponsors) }
            .forEach { DriverHub.assistant(this, it).start() }
        TvMonitor.sync(this)   // relance aussi les liens avec les applis Box
        if (ticker?.isActive != true) ticker = scope.launch {
            while (isActive) {
                if (!DriverHub.hasBackgroundWork()) { stopSelf(); break }
                val ads = DriverHub.activeAssistants().values.sumOf { it.state.value.adsSkipped }
                val sponsors = DriverHub.activeAssistants().values.sumOf { it.state.value.sponsorsSkipped }
                val sleep = DriverHub.sleepAt.value.values.minOrNull()
                val parts = mutableListOf<String>()
                if (TvMonitor.active()) parts += "Suivi du temps d'écran actif"
                if (fr.paul.telecommande.core.BoxLink.active()) parts += "Relié à l'appli Box"
                if (DriverHub.activeAssistants().isNotEmpty()) parts += "YouTube : $ads pub(s) passée(s), $sponsors sponsor(s) sauté(s)"
                if (sleep != null) parts += "Veille à " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(sleep))
                (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notify(NOTIF_ID, build(parts.joinToString(" · ").ifBlank { "Assistant TV actif" }))
                delay(5_000)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Entente")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .apply { if (!parentalLocked()) addAction(0, "Arrêter", stop) }
            .build()
    }

    private fun parentalLocked(): Boolean {
        val p = ParentalStore(this)
        return DeviceStore(this).all().any { p.rules(it.id).enabled }
    }

    companion object {
        private const val CHANNEL = "assistant"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "stop"

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Assistant TV", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Passage automatique des pubs YouTube et veille programmée"
                    })
            }
        }

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, AssistantService::class.java)) }
        }
    }
}
