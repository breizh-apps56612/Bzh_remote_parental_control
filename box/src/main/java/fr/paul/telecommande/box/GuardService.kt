package fr.paul.telecommande.box

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import fr.paul.telecommande.shared.BoxRules
import fr.paul.telecommande.shared.GraceKey
import fr.paul.telecommande.shared.ProtectedApps
import fr.paul.telecommande.shared.RulesEngine
import fr.paul.telecommande.shared.UsageContext
import fr.paul.telecommande.shared.Verdict
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Cœur de l'appli Box. Activé par l'utilisateur dans Paramètres > Accessibilité, il :
 *  - repère l'appli au premier plan (événements de changement de fenêtre, sans lire leur contenu) ;
 *  - mesure le temps par appli et applique les règles du parent (limites, horaires, couvre-feu) ;
 *  - affiche le compte à rebours, l'écran « Temps écoulé » et la demande de temps ;
 *  - protège les Paramètres de la box par le code parent ;
 *  - répond aux téléphones appairés (serveur local chiffré).
 */
class GuardService : AccessibilityService() {
    lateinit var overlays: Overlays
        private set
    private lateinit var store: BoxStore
    private lateinit var log: UsageLog
    private lateinit var server: Server
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var current: String? = null
    private var sessionId = -1L
    private var sessionPkg: String? = null
    private var settingsUnlockedUntil = 0L
    private var lastBlockPkg: String? = null
    private val warned = HashSet<String>()
    private var warnedDay = 0L
    private val labels = HashMap<String, String>()
    private var exempt: Set<String> = emptySet()

    private val tick = object : Runnable {
        override fun run() { evaluate(); maybeOfferUpdate(); main.postDelayed(this, 4_000) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlays = Overlays(this)
        overlays.onPinRequest = { id, title -> if (::server.isInitialized) server.push(JSONObject().put("event", "pin_request").put("id", id).put("title", title)) }
        overlays.onPinClosed = { id -> if (::server.isInitialized) server.push(JSONObject().put("event", "pin_closed").put("id", id)) }
        store = BoxStore.get(this)
        // Démarrage de la box : on redemande « Qui regarde ? »
        if (store.isNewBoot(System.currentTimeMillis() - SystemClock.elapsedRealtime())) store.setActiveProfile(null)
        runCatching {
            val meta = getSharedPreferences("box_meta", Context.MODE_PRIVATE)
            val fp = fr.paul.telecommande.shared.BoxProtocol.fingerprint(store.identity().second)
            val before = meta.getString("fp", null)
            if (before != null && before != fp) addDiag("identity", "Identité de la box changée (${store.startupInfo}) : réappairage du téléphone nécessaire")
            meta.edit().putString("fp", fp).apply()
            addDiag("start", "Démarrage : ${store.startupInfo}, ${store.phones().size} téléphone(s) appairé(s)")
            meta.getString("accessibilityOpen", null)?.let { addDiag("setup", "Bouton Activer : écran ouvert = $it") }
        }
        // Fin d'épisode en cours avant un redémarrage / une mise à jour : on reprend la même échéance (pas de temps en plus)
        store.graceState().let { (until, gp) ->
            if (until > System.currentTimeMillis()) { graceUntil = until; gracePkg = gp; watchContent(true) } else if (until != 0L) { store.setGraceState(0, null) }
            Unit
        }
        log = UsageLog.get(this)
        exempt = computeExempt()
        server = Server(this).also { it.start() }
        registerScreenReceiver()
        if (SystemClock.elapsedRealtime() < 10 * 60_000L) addDiag("reboot", "La box a redémarré")
        Thread {
            runCatching { if (getSharedPreferences("box", MODE_PRIVATE).getBoolean("imported", false).not()) {
                log.importHistory(this, exempt + SETTINGS)
                getSharedPreferences("box", MODE_PRIVATE).edit().putBoolean("imported", true).apply()
            } }
            log.purgeBefore(System.currentTimeMillis() - 90L * 24 * 3600_000)
        }.start()
        main.post(tick)
    }

    // ---------- Mise à jour automatique ----------

    private val updater by lazy { fr.paul.telecommande.shared.Updater(this, "box") }
    private var lastUpdateCheck = 0L
    @Volatile private var pendingUpdate = false
    private var lastOffer = 0L

    /** Vérifie (4 fois par jour au plus) et propose la mise à jour seulement quand la TV est sur l'accueil. */
    private fun maybeOfferUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck > 6 * 3600_000L) {
            lastUpdateCheck = now
            kotlin.concurrent.thread { pendingUpdate = runCatching { updater.check() != null }.getOrDefault(false) }
        }
        if (!pendingUpdate || now - lastOffer < 12 * 3600_000L) return
        val home = runCatching {
            packageManager.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        }.getOrNull()
        if (current != null && current == home && !overlays.blockVisible) {
            lastOffer = now
            runCatching { startActivity(android.content.Intent(this, UpdateActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // N'est écouté que pendant « finir l'épisode » : on guette l'écran « Épisode suivant »
            val now = System.currentTimeMillis()
            if (graceUntil > 0 && now - lastContentCheck > 1_500) { lastContentCheck = now; evaluate() }
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastInteraction = System.currentTimeMillis()      // l'utilisateur se déplace avec la télécommande
            // Une zone de texte vient d'être sélectionnée : le téléphone peut ouvrir son clavier
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED && runCatching { event.source?.isEditable }.getOrNull() == true) {
                val now = System.currentTimeMillis()
                if (now - lastFieldPush > 2_000) {
                    lastFieldPush = now
                    server.push(JSONObject().put("event", "field").put("pkg", event.packageName?.toString() ?: "")
                        .put("hint", if (android.os.Build.VERSION.SDK_INT >= 26) runCatching { event.source?.hintText?.toString() }.getOrNull() ?: "" else ""))
                }
            }
            return
        }
        lastInteraction = System.currentTimeMillis()
        detectCrash(event)
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in TRANSIENT || isIme(pkg)) return
        if (pkg == current) return
        current = pkg
        lastBlockPkg = null
        server.push(JSONObject().put("event", "app").put("pkg", pkg).put("label", label(pkg)))
        evaluate()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(screenReceiver) }
        main.removeCallbacksAndMessages(null)
        if (sessionId >= 0) log.extend(sessionId, System.currentTimeMillis())
        if (::server.isInitialized) server.stop()
        if (::overlays.isInitialized) overlays.hideAll()
        super.onDestroy()
    }

    // ---------- Mesure + application des règles ----------

    // ---------- Profil, fin d'épisode, toujours là ----------

    @Volatile private var lastInteraction = System.currentTimeMillis()
    private var lastFieldPush = 0L
    private var lastActive: Boolean? = null
    private var graceUntil = 0L
    private var gracePkg: String? = null
    private var silentSince = 0L
    private var lastContentCheck = 0L
    private var nextSeenSince = 0L

    /** Écoute des changements de contenu uniquement pendant la grâce (économie de ressources le reste du temps). */
    private fun watchContent(on: Boolean) = runCatching {
        val info = serviceInfo ?: return@runCatching
        val want = if (on) info.eventTypes or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                   else info.eventTypes and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED.inv()
        if (want != info.eventTypes) { info.eventTypes = want; serviceInfo = info }
    }

    private fun endGrace() { nextSeenSince = 0; if (graceUntil != 0L) { graceUntil = 0; store.setGraceState(0, null); watchContent(false) } }

    // Écran de fin d'épisode des applis (Netflix, YouTube, Prime Video, Disney+, myCANAL, france.tv…)
    private val nextWords = listOf("épisode suivant", "episode suivant", "prochain épisode", "prochain episode",
        "next episode", "up next", "à suivre", "a suivre", "lecture automatique", "autoplay", "regarder le générique",
        "watch credits", "lecture dans", "playing in", "suivant dans", "next in", "vidéo suivante", "next video")
    private val nextIds = listOf("postplay", "post_play", "next_episode", "nextepisode", "up_next", "upnext", "autonav", "autoplay", "binge", "end_card", "endcard")

    /** Renvoie le texte trouvé si l'écran « épisode suivant / à suivre » est affiché. */
    private fun nextEpisodeOnScreen(): String? = runCatching {
        val roots = (runCatching { windows.mapNotNull { it.root } }.getOrNull().orEmpty() + listOfNotNull(rootInActiveWindow))
        var seen = 0
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo, d: Int): String? {
            if (d > 30 || ++seen > 900) return null
            if (n.isVisibleToUser) {
                val t = ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).lowercase()
                nextWords.firstOrNull { t.contains(it) }?.let { return t.trim().take(80) }
                val id = n.viewIdResourceName?.lowercase() ?: ""
                nextIds.firstOrNull { id.contains(it) }?.let { return id }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { c -> walk(c, d + 1)?.let { return it } }
            return null
        }
        roots.firstNotNullOfOrNull { walk(it, 0) }
    }.getOrNull()

    private fun activeProfile(rules: BoxRules): fr.paul.telecommande.shared.Profile? =
        store.activeProfile()?.let { id -> rules.profiles.firstOrNull { it.id == id } }

    private fun audioPlaying() = runCatching { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive }.getOrDefault(false)

    private fun pickProfile(p: fr.paul.telecommande.shared.Profile) {
        val rules = store.rules()
        val last = store.lastProfile()?.let { id -> rules.profiles.firstOrNull { it.id == id } }
        val apply = {
            store.setActiveProfile(p.id); lastBlockPkg = null; lastInteraction = System.currentTimeMillis()
            overlays.showBanner("Bonne séance, ${p.name} ${p.emoji}")
            server.push(JSONObject().put("event", "profile").put("id", p.id).put("name", p.name).put("emoji", p.emoji))
            main.post { evaluate() }
        }
        if (fr.paul.telecommande.shared.ProfileSwitch.needsPin(last, p, rules.pinHash.isNotBlank())) {
            val title = if (p.unlimited) "Profil ${p.name}" else "Changer de profil : ${p.name}"
            overlays.showPinPadEx(title, ::pinError, onSuccess = { apply() },
                onCancel = { main.post { evaluate() } })
        } else apply()
    }

    private fun stillThereCheck(rules: BoxRules, now: Long, interactive: Boolean) {
        if (!interactive || rules.stillThereMin <= 0 || overlays.stillVisible) return
        if (now - lastInteraction < rules.stillThereMin * 60_000L) return
        overlays.showStillThere(60, onYes = { lastInteraction = System.currentTimeMillis() }, onTimeout = {
            pauseMedia()
            performGlobalAction(GLOBAL_ACTION_HOME)
            if (android.os.Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            addDiag("sleep", "Personne devant la TV : mise en veille")
            lastInteraction = System.currentTimeMillis()
        })
    }

    @Synchronized
    private fun evaluate() {
        val now = System.currentTimeMillis()
        val interactive = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val pkg = if (interactive) current else null
        val allRules = store.rules()
        val profile = activeProfile(allRules)

        // journal (l'accueil et les Paramètres ne comptent pas comme du temps d'écran)
        val counted = pkg?.takeIf { it !in exempt && !ProtectedApps.isSettings(it) && !ProtectedApps.isInstaller(it) }
        if (counted != sessionPkg) {
            if (sessionId >= 0) log.extend(sessionId, now)
            sessionId = if (counted != null) log.open(counted, now, profile?.id ?: "") else -1L
            sessionPkg = counted
        } else if (sessionId >= 0) log.extend(sessionId, now)

        stillThereCheck(allRules, now, interactive)
        // « Qui regarde ? » dès l'allumage ou la sortie de veille, même sur l'écran d'accueil
        if (interactive && allRules.enabled && allRules.profiles.isNotEmpty() && profile == null) {
            if (!overlays.whoVisible && !overlays.pinVisible) {
                pauseMedia(); overlays.hideTimer()
                val lastId = store.lastProfile()
                val last = allRules.profiles.firstOrNull { it.id == lastId }
                overlays.showWhoWatches(allRules.profiles, lastId,
                    { fr.paul.telecommande.shared.ProfileSwitch.needsPin(last, it, allRules.pinHash.isNotBlank()) }) { pickProfile(it) }
            } else if (audioPlaying()) pauseMedia()   // pas de vidéo qui tourne derrière « Qui regarde ? »
            return
        }
        if (pkg == null) { overlays.hideTimer(); return }
        if (!allRules.enabled) { overlays.hideTimer(); return }
        val rules = allRules.forProfile(profile)
        if (!rules.enabled) { overlays.hideTimer(); return }

        // Début / fin du temps libre : on le dit à l'écran et au téléphone
        val activeNow = RulesEngine.rulesActive(rules, Stats.minuteOfDay(now), Stats.dayOfWeek(now))
        if (lastActive != null && lastActive != activeNow) {
            val msg = if (activeNow) "Les règles de la TV recommencent" else "Temps libre : plus de limites " +
                (if (rules.activeFrom != rules.activeTo) "jusqu'à ${RulesEngine.hhmm(rules.activeFrom)}" else "aujourd'hui")
            overlays.showBanner(msg)
            server.push(JSONObject().put("event", "rules_state").put("active", activeNow).put("message", msg))
        }
        lastActive = activeNow

        if (isProtected(pkg)) { protectSettings(rules, now, pkg); return }
        if (ProtectedApps.isInstaller(pkg)) return
        // Contournement : l'écran de blocage est affiché mais une vidéo continue en fond (lecture en arrière-plan, image dans l'image…)
        if (overlays.blockVisible && audioPlaying()) { pauseMedia(); addDiagOnce("bypass", "Lecture en fond pendant un blocage : mise en pause") }
        if (pkg in exempt) { overlays.hideTimer(); return }

        val day = Stats.startOfDay(now)
        if (day != warnedDay) { warned.clear(); warnedDay = day; store.purge(day); endGrace() }
        val ctx = usage(pkg, now, allRules, profile)

        // Sablier discret : moins de 15 minutes restantes
        val left = RulesEngine.remaining(rules, rules.apps[pkg], ctx)
        if (rules.showTimer && left != null && left in 1 until 15 * 60_000L) {
            overlays.showTimer("Encore ${RulesEngine.fmt(left)}", urgent = left <= 5 * 60_000L)
        } else overlays.hideTimer()

        when (val v = RulesEngine.evaluate(rules, label(pkg), rules.apps[pkg], ctx)) {
            is Verdict.Block -> {
                // Fin d'épisode : une vidéo est en cours → on attend qu'elle se termine (silence) ou la grâce max
                val timeKind = v.kind == Verdict.KIND_APP || v.kind == Verdict.KIND_TOTAL
                val graceKey = GraceKey.of(day, profile?.id ?: "", pkg, v.kind)
                if (timeKind && rules.graceMin > 0) {
                    val playing = audioPlaying()
                    if (graceUntil == 0L && playing && store.claimGrace(graceKey)) {
                        graceUntil = now + rules.graceMin * 60_000L; gracePkg = pkg; silentSince = 0
                        store.setGraceState(graceUntil, pkg)
                        watchContent(true)
                        overlays.showBanner("C'est le dernier épisode : la TV s'arrêtera à la fin")
                        server.push(JSONObject().put("event", "grace").put("pkg", pkg).put("label", label(pkg)).put("until", graceUntil))
                        addDiag("grace", "Temps écoulé sur ${label(pkg)} : fin d'épisode, coupure au plus tard dans ${rules.graceMin} min")
                        return
                    }
                    if (graceUntil > 0 && gracePkg == pkg && now < graceUntil) {
                        // Les applis enchaînent l'épisode suivant sans silence : on coupe dès l'écran de fin
                        // (les boutons « épisode suivant » des commandes du lecteur s'affichent aussi quand on appuie
                        //  sur la télécommande : on exige un écran stable 5 s, sans touche pressée)
                        val next = nextEpisodeOnScreen()?.takeIf { now - lastInteraction > 8_000 }
                        if (next != null) {
                            if (nextSeenSince == 0L) nextSeenSince = now
                            if (now - nextSeenSince < 5_000) { main.postDelayed({ evaluate() }, 2_500); return }
                            addDiag("grace", "Fin d'épisode repérée : $next")
                        } else {
                            nextSeenSince = 0
                            if (playing) { silentSince = 0; return }
                            if (silentSince == 0L) { silentSince = now; return }
                            if (now - silentSince < 8_000) return
                        }
                    } else if (graceUntil > 0 && now >= graceUntil) {
                        addDiag("grace", "Fin d'épisode non repérée (${rules.graceMin} min écoulées) : " +
                            (runCatching { rootInActiveWindow?.let { collectText(it, 0) } }.getOrNull() ?: "").replace(Regex("\\s+"), " ").take(200))
                    }
                    endGrace()
                }
                block(pkg, v, rules)
            }
            is Verdict.Warn -> {
                val bucket = if (v.remainingMs <= 60_000) "1" else "5"
                if (warned.add("$pkg|$bucket")) overlays.showBanner(v.message)
            }
            Verdict.Allowed -> { if (gracePkg == pkg) endGrace() }
        }
    }

    private fun usage(pkg: String, now: Long, allRules: BoxRules, profile: fr.paul.telecommande.shared.Profile?): UsageContext {
        val day = Stats.startOfDay(now)
        val totals = if (allRules.profiles.isEmpty()) log.totals(day, now) else log.totals(day, now, profile?.id ?: "")
        return UsageContext(
            minuteOfDay = Stats.minuteOfDay(now), dayOfWeek = Stats.dayOfWeek(now),
            usedMs = totals[pkg] ?: 0L, totalMs = totals.values.sum(),
            bonusMs = store.bonusMs(day, pkg), totalBonusMs = store.bonusMs(day, "*"),
            scheduleOverride = store.overrideUntil(pkg) > now, bedtimeOverride = store.overrideUntil("*") > now
        )
    }

    /**
     * Accorde [ms] sur [pkg] en levant TOUTES les règles qui bloquent (couvre-feu, horaires, temps total, temps de l'appli),
     * pour qu'une seule autorisation suffise. [fallback] : la règle de la demande, si plus rien ne bloque déjà.
     */
    private fun grant(pkg: String, ms: Long, fallback: String, by: String = "code parent") {
        val now = System.currentTimeMillis()
        val allRules = store.rules()
        val profile = activeProfile(allRules)
        val rules = allRules.forProfile(profile)
        val g = RulesEngine.grants(rules, label(pkg), rules.apps[pkg], usage(pkg, now, allRules, profile), ms)
            .ifEmpty { if (fallback == Verdict.KIND_BLOCKED) emptyMap() else mapOf(fallback to ms) }
        for ((k, amount) in g) when (k) {
            Verdict.KIND_TOTAL -> store.addBonus(Stats.startOfDay(now), "*", amount)
            Verdict.KIND_SCHEDULE -> store.setOverride(pkg, now + amount)
            Verdict.KIND_BEDTIME -> store.setOverride("*", now + amount)
            else -> store.addBonus(Stats.startOfDay(now), pkg, amount)
        }
        // Les demandes encore en attente pour ce qui vient d'être levé sont réglées : sinon la notification restait sur le
        // téléphone, et l'accepter plus tard redonnait du temps une deuxième fois.
        val lifted = g.keys
        val reqs = store.requests()
        val closed = reqs.filter { r ->
            r.optString("status") == "pending" &&
                (r.optString("pkg") == pkg || r.optString("kind") in setOf(Verdict.KIND_TOTAL, Verdict.KIND_BEDTIME).intersect(lifted))
        }
        if (closed.isNotEmpty()) {
            closed.forEach { it.put("status", "accepted").put("by", by).put("minutes", (ms / 60_000L).toInt()) }
            store.saveRequests(reqs)
            closed.forEach { server.push(JSONObject(it.toString()).put("event", "request_update")) }
        }
        if (g.size > 1 || g.values.any { it > ms })
            addDiag("grant", "Autorisation pour ${label(pkg)} : " + g.entries.joinToString(" + ") { "${it.key} ${RulesEngine.fmt(it.value)}" })
    }

    private fun block(pkg: String, v: Verdict.Block, rules: BoxRules) {
        pauseMedia()
        if (overlays.pinVisible) return   // le parent est en train de valider : on ne recouvre pas le pavé
        performGlobalAction(GLOBAL_ACTION_HOME)
        if (lastBlockPkg == pkg && overlays.blockVisible) return
        lastBlockPkg = pkg
        val pending = store.requests().any { it.optString("pkg") == pkg && it.optString("status") == "pending" && it.optString("kind") == v.kind }
        // Le parent peut débloquer sur place avec son code (sauf appli totalement interdite)
        val parent: (() -> Unit)? = if (rules.pinHash.isNotBlank() && v.kind != Verdict.KIND_BLOCKED) ({ parentUnlock(pkg, v) }) else null
        overlays.showBlock(v.title, v.reason, v.canRequest, rules.requestMinutes, pending, parent) {
            requestTime(pkg, v.kind, rules.requestMinutes)
            overlays.showBlock(v.title, v.reason, true, rules.requestMinutes, true, parent) {}
        }
    }

    /** Code parent saisi sur la TV : 30 minutes de plus, puis retour direct dans l'appli. */
    private fun parentUnlock(pkg: String, v: Verdict.Block) {
        overlays.hideBlock()
        overlays.showPinPadEx("Continuer la lecture (+30 min)", ::pinError,
            onSuccess = {
                val now = System.currentTimeMillis()
                grant(pkg, 30 * 60_000L, v.kind)
                lastBlockPkg = null
                overlays.hideBlock()
                overlays.showBanner("Code parent accepté : +30 min sur ${label(pkg)}")
                server.push(JSONObject().put("event", "parent_unlock").put("pkg", pkg).put("label", label(pkg)).put("minutes", 30).put("at", now))
                (packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg))?.let { runCatching { startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            },
            onCancel = { lastBlockPkg = null; main.post { evaluate() } })
    }

    /** Code parent saisi sur la TV : null si bon, sinon le message (essais restants, pause en cours). */
    private fun pinError(pin: String): String? {
        val r = store.tryPin(pin)
        if (r == fr.paul.telecommande.shared.PinThrottle.Result.Ok) return null
        if (r is fr.paul.telecommande.shared.PinThrottle.Result.Locked && r.fails > 0)
            addDiag("pin", "${r.fails} codes parent faux sur la TV : pause de ${RulesEngine.fmt(r.ms)}")
        return fr.paul.telecommande.shared.PinThrottle.message(r)
    }

    private fun protectSettings(rules: BoxRules, now: Long, pkg: String) {
        if (!rules.protectSettings || rules.pinHash.isBlank() || now < settingsUnlockedUntil || overlays.pinVisible) return
        val title = if (ProtectedApps.isInstaller(pkg)) "Installer ou supprimer une appli" else "Paramètres protégés"
        overlays.showPinPadEx(title, ::pinError,
            onSuccess = { settingsUnlockedUntil = System.currentTimeMillis() + 5 * 60_000 },
            onCancel = { performGlobalAction(GLOBAL_ACTION_BACK); performGlobalAction(GLOBAL_ACTION_HOME) })
    }

    /** Nouveau téléphone à appairer : le parent valide avant que le code d'appairage s'affiche. */
    fun approvePairing(onOk: () -> Unit, onCancel: () -> Unit) {
        addDiag("pair", "Un nouveau téléphone demande à être appairé")
        overlays.showPinPadEx("Appairer un nouveau téléphone ?", ::pinError, onSuccess = onOk, onCancel = onCancel)
    }

    // ---------- Paramètres et installation d'applis : protégés par le code parent ----------

    @Volatile private var selfUpdateUntil = 0L

    /** Notre propre mise à jour passe par l'installeur d'Android : on ne la bloque pas (3 minutes). */
    fun allowSelfUpdate() { selfUpdateUntil = System.currentTimeMillis() + 3 * 60_000L }

    private fun isProtected(pkg: String): Boolean =
        ProtectedApps.isSettings(pkg) || (ProtectedApps.isInstaller(pkg) && System.currentTimeMillis() > selfUpdateUntil)

    private fun pauseMedia() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
    }

    // ---------- Demandes de temps ----------

    private fun requestTime(pkg: String, kind: String, minutes: Int) {
        val req = JSONObject().put("id", UUID.randomUUID().toString()).put("pkg", pkg).put("label", label(pkg))
            .put("kind", kind).put("minutes", minutes).put("at", System.currentTimeMillis()).put("status", "pending")
        store.saveRequests(store.requests().filterNot { it.optString("pkg") == pkg && it.optString("status") == "pending" } + req)
        server.push(JSONObject(req.toString()).put("event", "request"))
    }

    private fun respond(id: String, accept: Boolean, minutes: Int, by: String): JSONObject {
        val r0 = store.requests().firstOrNull { it.optString("id") == id } ?: return err("Demande introuvable")
        if (r0.optString("status") != "pending") return err("Demande déjà traitée")
        val pkg = r0.optString("pkg")
        val ms = minutes.coerceIn(1, 240) * 60_000L
        if (accept) grant(pkg, ms, r0.optString("kind"), by)
        // relu après grant(), qui a pu régler d'autres demandes
        val list = store.requests()
        val r = list.firstOrNull { it.optString("id") == id } ?: r0
        r.put("status", if (accept) "accepted" else "refused").put("by", by).put("minutes", minutes)
        store.saveRequests(list)
        overlays.hideBlock()
        lastBlockPkg = null
        overlays.showBanner(if (accept) "Demande acceptée : +$minutes min sur ${r.optString("label")}" else "Demande refusée")
        server.push(JSONObject(r.toString()).put("event", "request_update"))
        return JSONObject().put("ok", true)
    }

    // ---------- Écran allumé / éteint : nouveau « Qui regarde ? » à chaque allumage ----------

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { store.setActiveProfile(null); overlays.hideTimer(); endGrace() }
                Intent.ACTION_SCREEN_ON -> { lastInteraction = System.currentTimeMillis(); main.post { evaluate() } }
            }
        }
    }

    private fun registerScreenReceiver() {
        val f = android.content.IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
        registerReceiver(screenReceiver, f)
    }

    // ---------- Diagnostic ----------

    private val crashWords = listOf("s'est arrêté", "s'est arrêtée", "ne répond pas", "has stopped", "keeps stopping", "isn't responding", "s'arrête")

    private fun detectCrash(e: AccessibilityEvent) {
        val p = e.packageName?.toString() ?: return
        if (p != "android" && p != "com.android.systemui") return
        val txt = (e.text.joinToString(" ") + " " + (runCatching { e.source?.let { collectText(it, 0) } }.getOrNull() ?: "")).trim()
        if (crashWords.any { txt.contains(it, ignoreCase = true) }) addDiag("crash", txt.take(160))
    }

    private fun collectText(n: android.view.accessibility.AccessibilityNodeInfo, depth: Int): String {
        if (depth > 4) return ""
        val sb = StringBuilder(n.text?.toString() ?: "")
        for (i in 0 until minOf(n.childCount, 12)) n.getChild(i)?.let { sb.append(' ').append(collectText(it, depth + 1)) }
        return sb.toString()
    }

    private var lastOnce = 0L
    private fun addDiagOnce(kind: String, text: String) {
        val now = System.currentTimeMillis()
        if (now - lastOnce > 60_000) { lastOnce = now; addDiag(kind, text) }
    }

    private fun addDiag(kind: String, text: String) {
        val e = JSONObject().put("kind", kind).put("text", text).put("at", System.currentTimeMillis()).put("app", current ?: "")
        store.addDiag(e)
        if (::server.isInitialized) server.push(JSONObject(e.toString()).put("event", "diag"))
    }

    // ---------- Vraie souris (flèche dessinée + gestes) ----------

    private fun tapAt(x: Float, y: Float, long: Boolean = false): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 24) return false
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, if (long) 700 else 60)).build()
        return dispatchGesture(g, null, null)
    }

    private fun swipe(x: Float, y: Float, dy: Float): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 24) return false
        val (_, h) = resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        val y2 = (y - dy).coerceIn(5f, h - 5f)
        val path = android.graphics.Path().apply { moveTo(x, y); lineTo(x, y2) }
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 220)).build()
        return dispatchGesture(g, null, null)
    }

    // ---------- Saisie de texte depuis le téléphone ----------

    private fun findEditable(n: android.view.accessibility.AccessibilityNodeInfo?, depth: Int = 0): android.view.accessibility.AccessibilityNodeInfo? {
        if (n == null || depth > 25) return null
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) findEditable(n.getChild(i), depth + 1)?.let { return it }
        return null
    }

    private fun typeText(text: String, submit: Boolean): JSONObject {
        val root = rootInActiveWindow ?: return err("Aucune fenêtre active")
        val field = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: findEditable(root) ?: return err("Aucun champ de saisie à l'écran : sélectionnez d'abord la zone de texte")
        field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
        val args = android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        var submitted = false
        if (submit && android.os.Build.VERSION.SDK_INT >= 30) {
            submitted = field.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        lastInteraction = System.currentTimeMillis()
        return if (ok) JSONObject().put("ok", true).put("submitted", submitted).put("hint", if (android.os.Build.VERSION.SDK_INT >= 26) field.hintText?.toString() ?: "" else "")
        else err("Le champ a refusé le texte")
    }

    // ---------- Applis (avec icônes), lancement, ouverture de liens ----------

    private fun iconB64(pkg: String): String? = runCatching {
        val pm = packageManager
        val dr = runCatching { pm.getApplicationBanner(pkg) }.getOrNull() ?: pm.getApplicationIcon(pkg)
        // qualité « écran de téléphone » : bannière TV 320×180, icône 192×192
        val w = if (dr.intrinsicWidth > dr.intrinsicHeight * 1.3) 320 else 192
        val h = if (w == 320) 180 else 192
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp); dr.setBounds(0, 0, w, h); dr.draw(c)
        val out = java.io.ByteArrayOutputStream(); bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }.getOrNull()

    private fun launch(pkg: String): Boolean {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    private fun openUrl(url: String): Boolean {
        val uri = android.net.Uri.parse(url.trim())
        if (uri.scheme != "http" && uri.scheme != "https") return false
        val yt = Regex("""(?:youtube\.com/watch\?v=|youtu\.be/|youtube\.com/shorts/)([\w-]{6,})""").find(url)?.groupValues?.get(1)
        val tries = buildList {
            if (yt != null) {
                add(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$yt")).setPackage("com.google.android.youtube.tv"))
                add(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$yt")))
            }
            add(Intent(Intent.ACTION_VIEW, uri))
        }
        return tries.any { i -> runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false) }
    }

    // ---------- Commandes des téléphones appairés ----------

    fun command(cmd: String, req: JSONObject, phoneName: String, phoneFp: String): JSONObject = when (cmd) {
        "status" -> status()
        "usage" -> usage(req.optLong("from"), req.optLong("to", System.currentTimeMillis()))
        "apps" -> JSONObject().put("ok", true).put("apps", JSONArray().apply {
            val icons = req.optBoolean("icons")
            tvApps().forEach { (p, l) -> put(JSONObject().put("pkg", p).put("label", l).apply { if (icons) iconB64(p)?.let { put("icon", it) } }) }
        })
        "launch" -> if (launch(req.optString("pkg"))) JSONObject().put("ok", true) else err("Impossible d'ouvrir cette appli")
        "open_url" -> if (openUrl(req.optString("url"))) JSONObject().put("ok", true) else err("Lien non pris en charge")
        "pointer" -> { overlays.movePointer(req.optDouble("dx").toFloat(), req.optDouble("dy").toFloat()); lastInteraction = System.currentTimeMillis(); JSONObject().put("ok", true) }
        "pointer_click" -> {
            overlays.pulsePointer(); lastInteraction = System.currentTimeMillis()
            if (tapAt(overlays.pointerX + 2, overlays.pointerY + 2, req.optBoolean("long"))) JSONObject().put("ok", true) else err("Clic impossible sur cette box")
        }
        "pointer_scroll" -> { swipe(overlays.pointerX, overlays.pointerY, req.optDouble("dy").toFloat()); JSONObject().put("ok", true) }
        "back" -> { performGlobalAction(GLOBAL_ACTION_BACK); JSONObject().put("ok", true) }
        "type_text" -> typeText(req.optString("text"), req.optBoolean("submit"))
        "message" -> {
            if (req.optBoolean("pause", true)) pauseMedia()
            overlays.showMessage(req.optString("emoji", "📣"), req.optString("title"), req.optString("text")) {
                server.push(JSONObject().put("event", "message_seen").put("title", req.optString("title")).put("at", System.currentTimeMillis()))
            }
            JSONObject().put("ok", true)
        }
        "set_profile" -> {
            val id = req.optString("id").ifBlank { null }
            store.setActiveProfile(id); lastBlockPkg = null; main.post { evaluate() }
            JSONObject().put("ok", true)
        }
        "diag" -> JSONObject().put("ok", true).put("events", JSONArray(store.diag()))
        "get_rules" -> JSONObject().put("ok", true).put("rules", store.rules().toJson())
        "set_rules" -> {
            val incoming = BoxRules.fromJson(req.optJSONObject("rules"))
            if (incoming.pinHash.isBlank() && store.rules().pinHash.isNotBlank()) incoming.pinHash = store.rules().pinHash
            store.saveRules(incoming)
            lastBlockPkg = null
            main.post { evaluate() }
            JSONObject().put("ok", true)
        }
        "end_grace" -> {
            if (graceUntil > 0) { graceUntil = System.currentTimeMillis() - 1; nextSeenSince = 0; silentSince = 0 }
            main.post { evaluate() }
            addDiag("grace", "Coupure demandée depuis le téléphone ($phoneName)")
            JSONObject().put("ok", true)
        }
        "pin_pending" -> {
            val p = overlays.pendingPin
            JSONObject().put("ok", true).apply { if (p != null) { put("pin", p.first); put("title", p.second) } }
        }
        "pin_approve" -> {
            val ok = overlays.approvePin(req.optString("id"), req.optString("title"))
            // Le parent a validé depuis son téléphone (empreinte ou code) : la pause des codes faux sur la TV ne vise que l'enfant
            if (ok) store.pinThrottle.clear()
            addDiag("pin", (if (ok) "Code parent validé depuis le téléphone" else "Validation du téléphone refusée") + " ($phoneName) : ${overlays.lastApproveInfo}")
            JSONObject().put("ok", ok).apply { if (!ok) put("error", "Demande annulée ou expirée sur la TV") }
        }
        "bonus" -> {
            val now = System.currentTimeMillis()
            val pkg = req.optString("pkg", "*").ifBlank { "*" }
            val minutes = req.optInt("minutes").coerceIn(1, 240)
            store.addBonus(Stats.startOfDay(now), pkg, minutes * 60_000L)
            // Bonus sur le temps total : si l'appli à l'écran a aussi atteint sa propre limite, on la lève aussi
            // (sinon la TV restait bloquée malgré le bonus). Le couvre-feu et les horaires ne sont pas touchés.
            val shown = lastBlockPkg ?: current
            if (pkg == "*" && shown != null) {
                val allRules = store.rules(); val profile = activeProfile(allRules); val rules = allRules.forProfile(profile)
                RulesEngine.grants(rules, label(shown), rules.apps[shown], usage(shown, now, allRules, profile), minutes * 60_000L)[Verdict.KIND_APP]
                    ?.let { store.addBonus(Stats.startOfDay(now), shown, it) }
            }
            overlays.hideBlock(); lastBlockPkg = null
            val reason = req.optString("reason")
            val who = activeProfile(store.rules())?.name
            if (reason.isNotBlank()) overlays.showReward("Bravo${if (who != null) " $who" else ""} ! +$minutes min", reason)
            else overlays.showBanner("Bonus : +$minutes min" + if (pkg == "*") "" else " sur ${label(pkg)}")
            JSONObject().put("ok", true)
        }
        "requests" -> JSONObject().put("ok", true).put("requests", JSONArray(store.requests().map { it }))
        "respond" -> respond(req.optString("rid"), req.optBoolean("accept"), req.optInt("minutes", 15), phoneName)
        "unpair" -> { store.removePhone(phoneFp); JSONObject().put("ok", true) }
        else -> err("Commande inconnue")
    }

    private fun status(): JSONObject {
        val now = System.currentTimeMillis()
        val day = Stats.startOfDay(now)
        val totals = log.totals(day, now)
        val pkg = current
        return JSONObject().put("ok", true)
            .put("pkg", pkg ?: "").put("label", pkg?.let { label(it) } ?: "")
            .put("interactive", (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive)
            .put("todayTotal", totals.values.sum())
            .put("today", JSONObject(totals as Map<*, *>))
            .put("labels", JSONObject(totals.keys.associateWith { label(it) } as Map<*, *>))
            .put("media", runCatching { Media.nowPlaying(this) }.getOrNull() ?: JSONObject.NULL)
            .put("pending", JSONArray(store.requests().filter { it.optString("status") == "pending" }))
            .put("perms", JSONObject().put("media", Media.allowed(this)).put("encrypted", store.encrypted))
            .put("rulesVersion", store.rules().version)
            .put("profile", activeProfile(store.rules())?.toJson() ?: JSONObject.NULL)
    }

    private fun usage(from: Long, to: Long): JSONObject {
        val sessions = log.sessions(from, to)
        val totals = Stats.totals(sessions, from, to)
        val days = ((to - from) / (24 * 3600_000L)).toInt().coerceIn(1, 31)
        val daily = JSONArray()
        var d = Stats.startOfDay(from)
        while (d < to) { val e = Stats.addDays(d, 1); daily.put(Stats.totals(sessions, d, minOf(e, to)).values.sum()); d = e }
        return JSONObject().put("ok", true)
            .put("byApp", JSONObject(totals as Map<*, *>))
            .put("labels", JSONObject(totals.keys.associateWith { label(it) } as Map<*, *>))
            .put("hourly", JSONArray(Stats.hourly(sessions, Stats.startOfDay(from), days).toList()))
            .put("daily", daily)
            .put("since", log.first() ?: JSONObject.NULL)
    }

    private fun err(m: String) = JSONObject().put("ok", false).put("error", m)

    // ---------- Applis ----------

    fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    fun tvApps(): List<Pair<String, String>> {
        val pm = packageManager
        val pkgs = LinkedHashSet<String>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0).forEach { pkgs += it.activityInfo.packageName }
        }
        return pkgs.filter { it != packageName && it !in exempt && !ProtectedApps.isSettings(it) && !ProtectedApps.isInstaller(it) }.map { it to label(it) }.sortedBy { it.second.lowercase() }
    }

    private fun computeExempt(): Set<String> {
        val homes = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
        return (homes + packageName + TRANSIENT).toSet()
    }

    private fun isIme(pkg: String): Boolean = runCatching {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.any { it.packageName == pkg }
    }.getOrDefault(false)

    companion object {
        @Volatile var instance: GuardService? = null
            private set

        val SETTINGS = ProtectedApps.SETTINGS + ProtectedApps.INSTALLERS
        val TRANSIENT = setOf("com.android.systemui", "android", "com.google.android.katniss", "com.google.android.tvrecommendations")

        fun enabled(ctx: Context): Boolean =
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.contains(ctx.packageName + "/") == true
    }
}
