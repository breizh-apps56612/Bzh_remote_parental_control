package fr.paul.telecommande.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import fr.paul.telecommande.R
import fr.paul.telecommande.ui.StatsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Liens permanents avec les applis Box appairées : réception des demandes de temps
 * (notification avec « +15 min » / « Refuser ») et de l'appli en cours.
 */
object BoxLink {
    private val jobs = HashMap<String, Job>()
    private val pollers = HashMap<String, Job>()
    /** Demandes de code déjà présentées au parent (pour ne pas les afficher deux fois). */
    private val shownPins = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private val _online = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val online: StateFlow<Map<String, Boolean>> = _online
    private val _app = MutableStateFlow<Map<String, String>>(emptyMap())
    val app: StateFlow<Map<String, String>> = _app

    /** Tous les événements des box (id de l'appareil, événement) : clavier à ouvrir, diagnostic… */
    val events = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, JSONObject>>(extraBufferCapacity = 32)
    private val _profile = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Profil « Qui regarde ? » actif par box (emoji + prénom). */
    val profile: StateFlow<Map<String, String>> = _profile

    /** Pavé fermé sur la TV (code tapé, annulé) : la fenêtre de validation du téléphone se ferme. */
    val pinClosed = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)

    @Synchronized fun active() = jobs.values.any { it.isActive }

    @Synchronized
    fun sync(context: Context) {
        val ctx = context.applicationContext
        val boxes = DeviceStore(ctx).all().filter { it.hasBoxApp }
        val ids = boxes.map { it.id }.toSet()
        jobs.keys.filter { it !in ids }.forEach { jobs.remove(it)?.cancel() }
        pollers.keys.filter { it !in ids }.forEach { pollers.remove(it)?.cancel() }
        boxes.forEach { d -> if (pollers[d.id]?.isActive != true) pollers[d.id] = DriverHub.scope.launch { pollPin(ctx, d.id) } }
        boxes.forEach { d ->
            if (jobs[d.id]?.isActive == true) return@forEach
            jobs[d.id] = DriverHub.scope.launch {
                var backoff = 5_000L
                while (isActive) {
                    val dev = DeviceStore(ctx).get(d.id) ?: break
                    if (!dev.hasBoxApp) break
                    try {
                        pushRulesIfNewer(ctx, dev)
                        withContext(Dispatchers.IO) {
                            DiagLog.log("BOX", "Lien avec ${dev.name} ouvert (alertes de la TV)")
                            BoxClient.of(ctx, dev).subscribe { ev ->
                                _online.update { it + (dev.id to true) }
                                if (ev.optString("event") !in setOf("app", "field")) DiagLog.log("BOX", "Événement ${dev.name} : ${ev.optString("event")} ${ev.optString("text", ev.optString("title"))}".trim())
                                backoff = 5_000L
                                when (ev.optString("event")) {
                                    "request" -> notifyRequest(ctx, dev, ev)
                                    "request_update" -> cancel(ctx, ev.optString("id"))
                                    "app" -> _app.update { it + (dev.id to ev.optString("pkg")) }
                                    "profile" -> _profile.update { it + (dev.id to (ev.optString("emoji") + " " + ev.optString("name")).trim()) }
                                    "diag" -> notifyDiag(ctx, dev, ev)
                                    "pin_request" -> notifyPin(ctx, dev, ev)
                                    "program_request" -> notifyProgram(ctx, dev, ev)
                                    "grace" -> notifyGrace(ctx, dev, ev)
                                    "pin_closed" -> {
                                    (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(PIN_NOTIF)
                                    pinClosed.tryEmit(ev.optString("id"))
                                }
                                    "message_seen" -> notifyInfo(ctx, dev, "Message vu sur la TV", "« ${ev.optString("title")} » : quelqu'un a répondu « J'arrive ! »", 7900)
                                    "parent_unlock" -> notifyInfo(ctx, dev, "Code parent utilisé sur la TV", "+${ev.optInt("minutes")} min sur ${ev.optString("label")}", 7901)
                                }
                                events.tryEmit(dev.id to ev)
                            }
                        }
                    } catch (e: Exception) {
                        DiagLog.log("BOX", "Lien avec ${dev.name} coupé : ${e.javaClass.simpleName} ${e.message ?: ""}")
                        if (e is java.net.SocketTimeoutException) backoff = 2_000L   // lien mort détecté : on revient vite
                    }
                    _online.update { it + (dev.id to false) }
                    delay(backoff); backoff = minOf(backoff * 2, 120_000L)
                }
            }
        }
    }

    /** Règles modifiées pendant que la box était éteinte : envoyées dès qu'elle répond. */
    suspend fun pushRulesIfNewer(ctx: Context, d: Device) {
        val local = ParentalStore(ctx).boxRules(d.id)
        if (local.version == 0L) return
        val client = BoxClient.of(ctx, d)
        val remote = runCatching { client.call("status").optLong("rulesVersion") }.getOrNull() ?: return
        if (local.version > remote) client.call("set_rules", JSONObject().put("rules", local.toJson()))
    }

    // ---------- Code parent demandé sur la TV : validation sur le téléphone ----------

    const val PIN_NOTIF = 7950

    /**
     * Filet de sécurité : même si le lien permanent est tombé sans prévenir, on demande à la TV
     * si un pavé de code est affiché (toutes les 3 s appli ouverte, toutes les 20 s sinon).
     */
    private suspend fun pollPin(ctx: Context, id: String) {
        var unsupported = false
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val dev = DeviceStore(ctx).get(id) ?: break
            if (!dev.hasBoxApp) break
            val res = withContext(Dispatchers.IO) { runCatching { BoxClient.of(ctx, dev).call("pin_pending") } }
            unsupported = res.exceptionOrNull()?.message == "Commande inconnue"   // appli TV pas encore à jour
            val r = res.getOrNull()
            if (r != null) {
                val pid = r.optString("pin")
                if (pid.isNotBlank() && pid !in shownPins) {
                    DiagLog.log("BOX", "Pavé du code repéré par vérification (lien permanent muet)")
                    notifyPin(ctx, dev, JSONObject().put("id", pid).put("title", r.optString("title")))
                }
                r.optJSONObject("program")?.let { notifyProgram(ctx, dev, it) }
            }
            delay(when { unsupported -> 120_000L; fr.paul.telecommande.App.foreground -> 3_000L; else -> 20_000L })
        }
    }

    /** Programme bloqué sur la TV, l'enfant demande : empreinte puis cette fois / aujourd'hui / toujours. */
    fun notifyProgram(ctx: Context, d: Device, ev: JSONObject) {
        val rid = "prog:" + ev.optString("id")
        if (!shownPins.add(rid)) return   // déjà présentée (lien + vérification)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Demandes de temps", NotificationManager.IMPORTANCE_HIGH))
        val who = ev.optString("profileName").ifBlank { "Un enfant" }
        val what = ev.optString("title").ifBlank { "un programme sur ${ev.optString("label")}" }
        val i = Intent(ctx, fr.paul.telecommande.ui.ProgramApproveActivity::class.java)
            .putExtra("device", d.id).putExtra("id", ev.optString("id")).putExtra("title", ev.optString("title"))
            .putExtra("reason", ev.optString("reason")).putExtra("label", ev.optString("label")).putExtra("unknown", ev.optBoolean("unknown"))
            .putExtra("profile", ev.optString("profile")).putExtra("profileName", ev.optString("profileName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val nidP = PIN_NOTIF + 1
        val pi = PendingIntent.getActivity(ctx, nidP, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(nidP, NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("$who veut regarder « ${what.take(40)} »")
            .setContentText(ev.optString("reason"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ev.optString("reason")))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi).setAutoCancel(true)
            .addAction(0, "Répondre", pi)
            .build())
        if (fr.paul.telecommande.App.foreground) runCatching { ctx.startActivity(i) }
    }

    fun notifyPin(ctx: Context, d: Device, ev: JSONObject) {
        val pid = ev.optString("id")
        if (pid.isNotBlank() && !shownPins.add(pid)) return   // déjà présentée (lien + vérification)
        synchronized(shownPins) { while (shownPins.size > 50) shownPins.remove(shownPins.first()) }
        DiagLog.log("BOX", "La TV demande le code parent : ${ev.optString("title")}")
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Demandes de temps", NotificationManager.IMPORTANCE_HIGH))
        val i = Intent(ctx, fr.paul.telecommande.ui.PinApproveActivity::class.java)
            .putExtra("device", d.id).putExtra("id", ev.optString("id")).putExtra("title", ev.optString("title"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, PIN_NOTIF, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(PIN_NOTIF, NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("${d.name} demande le code parent")
            .setContentText("${ev.optString("title")} · touchez pour valider avec votre empreinte")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pi).setAutoCancel(true)
            .addAction(0, "Valider", pi)
            .build())
        // Appli ouverte au premier plan : on ouvre directement la validation
        if (fr.paul.telecommande.App.foreground) runCatching { ctx.startActivity(i) }
    }

    /** Temps écoulé, la TV laisse finir l'épisode : le parent peut couper tout de suite. */
    fun notifyGrace(ctx: Context, d: Device, ev: JSONObject) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Demandes de temps", NotificationManager.IMPORTANCE_HIGH))
        val until = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date(ev.optLong("until")))
        val cut = PendingIntent.getBroadcast(ctx, 7960, Intent(ctx, GraceReceiver::class.java).putExtra("device", d.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(7960, NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("${ev.optString("label")} : temps écoulé")
            .setContentText("La TV laisse finir l'épisode et coupe au plus tard à $until.")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
            .addAction(0, "Couper maintenant", cut)
            .build())
    }

    // ---------- Notifications « demande de temps » ----------

    private const val CHANNEL = "requests"

    private fun nid(requestId: String) = 7000 + (requestId.hashCode() and 0xFFF)

    fun notifyRequest(ctx: Context, d: Device, ev: JSONObject) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Demandes de temps", NotificationManager.IMPORTANCE_HIGH))
        val rid = ev.optString("id")
        val minutes = ev.optInt("minutes", 15)
        fun action(accept: Boolean, code: Int) = PendingIntent.getBroadcast(ctx, nid(rid) * 2 + code,
            Intent(ctx, RequestReceiver::class.java).putExtra("device", d.id).putExtra("rid", rid)
                .putExtra("accept", accept).putExtra("minutes", minutes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(ctx, nid(rid), Intent(ctx, StatsActivity::class.java).putExtra(StatsActivity.EXTRA_ID, d.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val what = when (ev.optString("kind")) {
            "total" -> "du temps de TV en plus"
            "bedtime" -> "à regarder la TV pendant le couvre-feu"
            "schedule" -> "à utiliser ${ev.optString("label")} en dehors des horaires"
            else -> "$minutes min de plus sur ${ev.optString("label")}"
        }
        nm.notify(nid(rid), NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("${d.name} : demande de temps")
            .setContentText("Quelqu'un demande $what.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Accepter (+$minutes min)", action(true, 1))
            .addAction(0, "Refuser", action(false, 0))
            .build())
    }

    /** Diagnostic de la box : plantage d'appli, redémarrage, mise en veille automatique — avec le conseil adapté. */
    fun notifyDiag(ctx: Context, d: Device, ev: JSONObject) {
        DiagLog.log("BOX", "Diagnostic ${d.name} : ${ev.optString("kind")} ${ev.optString("text")}")
        val (title, text) = when (ev.optString("kind")) {
            "crash" -> "Une appli a planté sur ${d.name}" to "« ${ev.optString("text").take(80)} ». Si ça se répète : Paramètres de la TV › Applications › cette appli › Vider le cache."
            "reboot" -> "${d.name} a redémarré" to "La protection est de nouveau active. Si la box redémarre souvent, vérifiez son alimentation."
            "sleep" -> "${d.name} mise en veille" to "Personne n'a répondu à « Vous êtes toujours là ? »."
            else -> d.name to ev.optString("text")
        }
        notifyInfo(ctx, d, title, text, 7800 + (ev.optString("kind").hashCode() and 0x3F))
    }

    fun notifyInfo(ctx: Context, d: Device, title: String, text: String, id: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel("box_info", "Informations des TV", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, id, Intent(ctx, fr.paul.telecommande.ui.BoxDashboardActivity::class.java)
            .putExtra(fr.paul.telecommande.ui.BoxDashboardActivity.EXTRA_ID, d.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            nm.notify(id, NotificationCompat.Builder(ctx, "box_info").setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).build())
        }
    }

    fun cancel(ctx: Context, rid: String) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(nid(rid))
    }

    suspend fun respond(ctx: Context, d: Device, rid: String, accept: Boolean, minutes: Int): Result<Unit> = runCatching {
        BoxClient.of(ctx, d).call("respond", JSONObject().put("rid", rid).put("accept", accept).put("minutes", minutes))
        cancel(ctx, rid)
    }
}

/** Boutons « Accepter » / « Refuser » de la notification. */
class RequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val d = DeviceStore(context).get(intent.getStringExtra("device") ?: return) ?: return
        val rid = intent.getStringExtra("rid") ?: return
        val pending = goAsync()
        DriverHub.scope.launch {
            val r = BoxLink.respond(context, d, rid, intent.getBooleanExtra("accept", false), intent.getIntExtra("minutes", 15))
            if (r.isFailure) withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Box injoignable : réponse non envoyée", android.widget.Toast.LENGTH_LONG).show()
            }
            pending.finish()
        }
    }
}

class GraceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val d = DeviceStore(context).get(intent.getStringExtra("device") ?: return) ?: return
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(7960)
        val pending = goAsync()
        DriverHub.scope.launch {
            val r = runCatching { BoxClient.of(context, d).call("end_grace") }
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, if (r.isSuccess) "TV coupée" else "Box injoignable", android.widget.Toast.LENGTH_SHORT).show()
            }
            pending.finish()
        }
    }
}
