package fr.paul.telecommande.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import fr.paul.telecommande.R
import fr.paul.telecommande.ui.StatsActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Surveille en continu les box Android TV : enregistre l'appli au premier plan (quelle que soit
 * la télécommande utilisée) et applique le contrôle parental.
 */
object TvMonitor {
    data class Live(val connected: Boolean = false, val pkg: String = "", val on: Boolean = false, val lastAction: String = "")

    private val watchers = HashMap<String, Job>()
    private val _live = MutableStateFlow<Map<String, Live>>(emptyMap())
    val live: StateFlow<Map<String, Live>> = _live

    @Synchronized
    fun active(): Boolean = watchers.values.any { it.isActive }

    /** Démarre / arrête les surveillances selon les réglages enregistrés. */
    @Synchronized
    fun sync(context: Context) {
        val store = DeviceStore(context)
        val parental = ParentalStore(context)
        BoxLink.sync(context)
        // Une box équipée de l'appli Box se surveille elle-même : pas de double comptage côté téléphone.
        val wanted = store.all().filter { it.isSmartBox && !it.hasBoxApp && (it.monitorEnabled || parental.rules(it.id).enabled) }
        val ids = wanted.map { it.id }.toSet()
        watchers.keys.filter { it !in ids }.forEach { watchers.remove(it)?.cancel(); _live.update { m -> m - it } }
        wanted.forEach { d -> if (watchers[d.id]?.isActive != true) watchers[d.id] = DriverHub.scope.launch { watch(context.applicationContext, d.id) } }
    }

    private suspend fun watch(ctx: Context, deviceId: String) {
        val db = UsageDb.get(ctx)
        val parental = ParentalStore(ctx)
        val lock = Mutex()
        var sessionId = -1L
        var sessionPkg: String? = null
        var standbySent = false
        var lastStandby = 0L
        var connectedAt = 0L
        val lastHome = HashMap<String, Long>()
        val warned = HashSet<String>()
        var warnedDay = 0L
        var listening: Job? = null

        suspend fun tick() = lock.withLock {
            val device = DeviceStore(ctx).get(deviceId) ?: return@withLock
            val tv = DriverHub.androidTv(ctx, device) ?: return@withLock
            val now = System.currentTimeMillis()
            val on = tv.isConnected && tv.isOn.value
            val pkg = if (on) tv.currentApp.value.ifBlank { "unknown" } else null

            // --- journal ---
            if (pkg != sessionPkg) {
                if (sessionId >= 0) db.extend(sessionId, now)
                sessionId = if (pkg != null) db.open(deviceId, pkg, now) else -1L
                sessionPkg = pkg
            } else if (sessionId >= 0) db.extend(sessionId, now)
            _live.update { it + (deviceId to (it[deviceId] ?: Live()).copy(connected = tv.isConnected, pkg = pkg ?: "", on = on)) }

            // --- contrôle parental ---
            val rules = parental.rules(deviceId)
            if (!rules.enabled || pkg == null) return@withLock
            val dayStart = Usage.startOfDay(now)
            if (dayStart != warnedDay) { warned.clear(); warnedDay = dayStart }
            val usage = db.totalsByApp(deviceId, dayStart, now)
            val (decision, warnings) = ParentalLogic.evaluate(rules, Usage.minuteOfDay(now), usage, pkg, on, usage.values.sum())
            if (decision != Decision.None) DiagLog.log("PARENTAL", "Décision $decision · règles ${rules.toJson()} · appli $pkg · total ${usage.values.sum() / 60_000} min")
            warnings.forEach { w -> if (warned.add(w.key)) notify(ctx, device, w.message, w.key.hashCode()) }
            when (decision) {
                is Decision.GoHome -> {
                    if (now - (lastHome[decision.pkg] ?: 0L) > 15_000) {
                        lastHome[decision.pkg] = now
                        runCatching { tv.send(RemoteKey.HOME) }
                        notify(ctx, device, decision.reason + " : retour à l'accueil.", 1000 + decision.pkg.hashCode() % 1000)
                        _live.update { it + (deviceId to (it[deviceId] ?: Live()).copy(lastAction = decision.reason)) }
                    }
                }
                is Decision.Standby -> if (
                    // Sécurité : jamais pendant les 5 s qui suivent la connexion (état de la TV pas encore connu),
                    // seulement si une vraie appli est ouverte, et au plus une fois par minute.
                    now - connectedAt > 5_000 && pkg != "unknown" && tv.isOn.value && now - lastStandby > 60_000
                ) {
                    standbySent = true; lastStandby = now
                    DiagLog.log("PARENTAL", "Mise en veille demandée : ${decision.reason} (appli $pkg)")
                    runCatching { tv.sleep() }   // touche VEILLE : ne peut jamais rallumer ni éteindre/rallumer en boucle
                    // Certaines box (Freebox Player…) ignorent la touche VEILLE : on vérifie, puis on ramène à l'accueil
                    DriverHub.scope.launch {
                        kotlinx.coroutines.delay(6_000)
                        if (tv.isConnected && tv.isOn.value) {
                            DiagLog.log("PARENTAL", "La box ignore la mise en veille : retour à l'accueil")
                            runCatching { tv.send(RemoteKey.HOME) }
                        }
                    }
                    notify(ctx, device, decision.reason + " : TV mise en veille.", 2000)
                    _live.update { it + (deviceId to (it[deviceId] ?: Live()).copy(lastAction = decision.reason)) }
                } else if (
                    // Certaines box (Freebox Player POP…) ignorent la touche VEILLE : la TV reste allumée.
                    // On ferme alors l'appli en cours (retour à l'accueil), au plus toutes les 15 s.
                    lastStandby > 0 && now - lastStandby > 6_000 && tv.isOn.value && pkg != "unknown" &&
                    AppCatalog.kind(pkg) != AppCatalog.Kind.HOME && now - (lastHome[pkg] ?: 0L) > 15_000
                ) {
                    lastHome[pkg] = now
                    DiagLog.log("PARENTAL", "Veille ignorée par la box : retour à l'accueil (appli $pkg)")
                    runCatching { tv.send(RemoteKey.HOME) }
                    notify(ctx, device, decision.reason + " : retour à l'accueil.", 2001)
                }
                Decision.None -> {}
            }
        }

        try {
            while (true) {
                val device = DeviceStore(ctx).get(deviceId) ?: break
                val tv = DriverHub.androidTv(ctx, device) ?: break
                if (!tv.isConnected) {
                    runCatching { tv.connect(DriverHub.SilentPairing) }
                    if (tv.isConnected) {
                        standbySent = false
                        connectedAt = System.currentTimeMillis()
                        listening?.cancel()
                        // réaction immédiate à chaque changement d'appli ou de veille
                        listening = DriverHub.scope.launch {
                            launch { tv.currentApp.collect { tick() } }
                            launch { tv.isOn.collect { tick() } }
                        }
                    }
                }
                tick()
                delay(if (tv.isConnected) 20_000 else 30_000)
            }
        } finally {
            listening?.cancel()
            if (sessionId >= 0) db.extend(sessionId, System.currentTimeMillis())
        }
    }

    // ---------- Notifications au parent ----------

    private const val CHANNEL = "parental"

    private fun notify(ctx: Context, device: Device, text: String, id: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Contrôle parental", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, id, Intent(ctx, StatsActivity::class.java).putExtra(StatsActivity.EXTRA_ID, device.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(5000 + (id and 0xFFF), NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(device.name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    /** Utilisé par les interfaces : temps du jour par appli pour une box. */
    fun todayUsage(ctx: Context, deviceId: String): Map<String, Long> {
        val now = System.currentTimeMillis()
        return UsageDb.get(ctx).totalsByApp(deviceId, Usage.startOfDay(now), now)
    }

    fun isWatching(deviceId: String) = synchronized(this) { watchers[deviceId]?.isActive == true }
}
