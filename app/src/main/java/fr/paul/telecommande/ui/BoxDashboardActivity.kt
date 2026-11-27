package fr.paul.telecommande.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import fr.paul.telecommande.R
import fr.paul.telecommande.core.AppCatalog
import fr.paul.telecommande.core.BoxClient
import fr.paul.telecommande.core.BoxLink
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.ParentalStore
import fr.paul.telecommande.core.Usage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tableau de bord d'activité d'une TV équipée de l'appli Box (données mesurées sur la box). */
class BoxDashboardActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ID = "device_id"
        /** Écran « Parental » (règles, demandes, applis) ou « Activité » (temps passé). */
        const val EXTRA_MODE = "mode"
        const val MODE_PARENTAL = "parental"
        const val MODE_ACTIVITY = "activity"
    }

    private var mode = MODE_PARENTAL

    private lateinit var device: Device
    /** Page entière (bandeau nuit + contenu) ; [root] : le contenu sous le bandeau, refait à chaque affichage. */
    private lateinit var page: LinearLayout
    private lateinit var root: LinearLayout
    private var status: JSONObject? = null
    private var week: JSONObject? = null
    private var error: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        device = intent.getStringExtra(EXTRA_ID)?.let { DeviceStore(this).get(it) } ?: run { finish(); return }
        Ui.systemBars(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PARENTAL
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root = LinearLayout(this)
        setContentView(AuroraNav.wrap(this, ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(page) },
            if (mode == MODE_ACTIVITY) AuroraNav.Tab.ACTIVITY else AuroraNav.Tab.PARENTAL))
        Premium.darkStatusBar(this)
        render()
        lifecycleScope.launch { while (isActive) { load(); delay(30_000) } }
    }

    private suspend fun load() {
        val c = BoxClient.of(this, device)
        val now = System.currentTimeMillis()
        val from = Usage.addDays(Usage.startOfDay(now), -6)
        runCatching {
            status = c.call("status")
            // Activité : les chiffres du profil choisi (le total de la TV n'a d'intérêt que sans profils)
            val sel = if (mode == MODE_ACTIVITY) activityProfile(ParentalStore(this).boxRules(device.id)) else null
            val q = { a: Long, b: Long -> JSONObject().put("from", a).put("to", b).apply { if (sel != null) put("profile", sel.id) } }
            week = c.call("usage", q(from, now))
            today = if (sel != null) c.call("usage", q(Usage.startOfDay(now), now)) else null
            error = null
            if (fr.paul.telecommande.core.AppIcons.stale(this, device.id)) fr.paul.telecommande.core.AppIcons.refresh(this, device)
            logProgramReport(c)
        }.onFailure { error = it.message }
        render()
    }

    /** Temps du jour du profil affiché dans « Activité » (rempli seulement quand un profil est choisi). */
    private var today: JSONObject? = null
    /** Profil choisi dans « Activité » (null = automatique : l'enfant qui regarde, sinon le premier enfant). */
    private var actProfileId: String? = null

    /** Profil affiché dans « Activité », ou null quand la TV n'a pas de profils (on montre alors toute la TV). */
    private fun activityProfile(rules: fr.paul.telecommande.shared.BoxRules): fr.paul.telecommande.shared.Profile? {
        val ps = rules.profiles
        if (ps.isEmpty()) return null
        actProfileId?.let { id -> ps.firstOrNull { it.id == id }?.let { return it } }
        val watching = status?.optJSONObject("profile")?.optString("id")
        return ps.firstOrNull { it.id == watching && !it.unlimited } ?: ps.firstOrNull { !it.unlimited } ?: ps.first()
    }

    /** La TV a-t-elle bien filtré par profil ? (une ancienne appli TV renvoie le temps de toute la TV) */
    private fun filtered(p: fr.paul.telecommande.shared.Profile?, data: JSONObject?) = p != null && data?.optString("profile") == p.id

    /** Ce que la TV sait lire des programmes : noté dans le journal (toutes les 10 min au plus, et seulement si ça change). */
    private suspend fun logProgramReport(c: BoxClient) {
        val prefs = getSharedPreferences("prog_report", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("at_${device.id}", 0) < 10 * 60_000L) return
        prefs.edit().putLong("at_${device.id}", now).apply()
        val res = runCatching { c.call("prog_report") }.getOrNull() ?: return
        val lines = BoxActions.reportLines(res)
        val h = lines.joinToString("|").hashCode()
        if (h == prefs.getInt("hash_${device.id}", 0)) return
        prefs.edit().putInt("hash_${device.id}", h).apply()
        lines.forEach { fr.paul.telecommande.core.DiagLog.log("PROGRAMMES", "${device.name} : " + it.replace("\n", " · ")) }
    }

    private var tab = 0          // 0 = aujourd'hui, 1 = semaine, 2 = horaires
    private var showAllApps = false
    private var lastOk = 0L

    private fun section(text: String) = Ui.label(this, text).apply { setPadding(Ui.dp(this@BoxDashboardActivity, 6), 0, 0, 0) }

    private fun render() {
        val c = this
        page.removeAllViews()
        val rules = ParentalStore(c).boxRules(device.id)
        val st = status
        if (st != null && error == null) lastOk = System.currentTimeMillis()

        // ===== Bandeau nuit : état de la TV, qui regarde, temps restant en très grand =====
        page.addView(if (mode == MODE_ACTIVITY) activityHero(rules) else parentalHero(rules), LinearLayout.LayoutParams(-1, -2))
        root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(c, 16); setPadding(p, Ui.dp(c, 2), p, 0) }
        page.addView(root, LinearLayout.LayoutParams(-1, -2))
        if (error != null) root.addView(Ui.text(c, "Vérifiez que la TV est allumée et sur le même Wi-Fi. Nouvel essai toutes les 30 s.", 13f, muted = true), Ui.lp(c, 14))

        // ===== Demandes en attente (en premier : c'est ce qui attend une réponse) =====
        val pending = st?.optJSONArray("pending")
        if (pending != null && pending.length() > 0) {
            for (i in 0 until pending.length()) root.addView(requestCard(pending.getJSONObject(i)), Ui.lp(c, 14))
        }

        if (mode == MODE_ACTIVITY) renderActivity(rules) else renderParental(rules)
        root.addView(AuroraNav.spacer(c))
    }

    /** État de la TV en une pastille (texte, couleur du point). */
    private fun tvState(): Pair<String, Int> {
        val st = status
        return when {
            error != null -> "${device.name} · injoignable" to Ui.color(this, R.color.danger)
            st == null -> "${device.name} · connexion…" to 0xFF9A9DC0.toInt()
            !st.optBoolean("interactive") -> "${device.name} · en veille" to 0xFF9A9DC0.toInt()
            else -> "${device.name} · en direct" to Ui.color(this, R.color.mint)
        }
    }

    private fun heroTop(h: LinearLayout) {
        val c = this
        val top = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        val (txt, dot) = tvState()
        top.addView(Premium.heroPill(c, txt, dot), LinearLayout.LayoutParams(0, -2, 1f))
        if (lastOk > 0) top.addView(Premium.heroText(c, SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(lastOk)), 13f, soft = true))
        h.addView(top)
    }

    /** Temps du jour d'un profil (ms) et temps restant (ms, null = sans limite), mesurés par la TV si elle les donne. */
    private fun profileTime(rules: fr.paul.telecommande.shared.BoxRules, p: fr.paul.telecommande.shared.Profile?): Pair<Long, Long?> {
        val st = status
        val u = st?.optJSONObject("profileUsage")?.optJSONObject(p?.id ?: "*")
        // Ancienne appli TV (sans temps par profil) : à défaut, le temps de toute la TV
        val used = u?.optLong("ms") ?: st?.optLong("todayTotal") ?: 0L
        if (!rules.enabled || p?.unlimited == true) return used to null
        if (u != null && u.has("left")) return used to u.optLong("left").takeIf { it >= 0 }
        val limit = if (p != null && p.dailyTotalMin > 0) p.dailyTotalMin else rules.dailyTotalMin
        return used to if (limit > 0) (limit * 60_000L - used).coerceAtLeast(0) else null
    }

    private fun limitOf(rules: fr.paul.telecommande.shared.BoxRules, p: fr.paul.telecommande.shared.Profile?) =
        if (!rules.enabled || p?.unlimited == true) 0 else if (p != null && p.dailyTotalMin > 0) p.dailyTotalMin else rules.dailyTotalMin

    /** Bandeau « Parental » : qui regarde, le temps qu'il lui reste en très grand, et les gestes du soir. */
    private fun parentalHero(rules: fr.paul.telecommande.shared.BoxRules): LinearLayout {
        val c = this
        val st = status
        val h = Premium.hero(c)
        heroTop(h)
        val whoId = st?.optJSONObject("profile")?.optString("id")
        val prof = rules.profiles.firstOrNull { it.id == whoId }
        val on = st != null && error == null && st.optBoolean("interactive")
        val app = st?.optString("label")?.ifBlank { null }
        h.addView(Premium.heroText(c, when {
            error != null -> "La TV ne répond pas pour l'instant"
            st == null -> "Connexion à la TV…"
            !on -> "Personne devant la TV"
            prof != null -> "${prof.emoji}  ${prof.name} regarde " + (app ?: "l'accueil")
            else -> "En ce moment : " + (app ?: "l'accueil")
        }, 15.5f, soft = true).apply { maxLines = 2 }, Ui.lp(c, 22))

        val (used, left) = profileTime(rules, prof)
        val limit = limitOf(rules, prof)
        val (big, unit) = when {
            !rules.enabled -> "Libre" to "contrôle en pause"
            prof?.unlimited == true -> "Libre" to "profil parent"
            left != null && left <= 0 -> "0" to "min · temps écoulé"
            left != null -> ((left + 59_999) / 60_000).toInt().let { m -> if (m < 60) "$m" to "min restantes" else fr.paul.telecommande.shared.RulesEngine.fmt(left) to "restantes" }
            else -> Usage.format(used) to "aujourd'hui"
        }
        h.addView(Premium.bigNumber(c, big, unit, if (big.length > 4) 52f else 72f), Ui.lp(c, 6))
        if (limit > 0) {
            h.addView(Premium.heroProgress(c, used.toFloat() / (limit * 60_000L)), LinearLayout.LayoutParams(-1, Ui.dp(c, 6)).apply { topMargin = Ui.dp(c, 18) })
        }
        val cap = LinearLayout(c)
        cap.addView(Premium.heroText(c, if (limit > 0) "${Usage.format(used)} utilisées sur ${Usage.format(limit * 60_000L)}" else if (rules.enabled) "Pas de limite de temps par jour" else "Aucune limite sur la TV", 12.5f, soft = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        if (rules.enabled && rules.bedtimeEnabled) cap.addView(Premium.heroText(c, "Couvre-feu " + fr.paul.telecommande.shared.RulesEngine.hhmm(rules.bedtimeStart), 12.5f, soft = true))
        h.addView(cap, Ui.lp(c, 8))

        val btns = LinearLayout(c)
        btns.addView(Premium.heroButton(c, "+ 15 min", true) { quickBonus(15) }, LinearLayout.LayoutParams(0, Ui.dp(c, 52), 1.3f))
        btns.addView(Premium.heroButton(c, "Message", false) { BoxActions.message(c, device) }, LinearLayout.LayoutParams(0, Ui.dp(c, 52), 1f).apply { marginStart = Ui.dp(c, 8) })
        btns.addView(Premium.heroButton(c, "Plus", false) { moreActions(rules) }, LinearLayout.LayoutParams(0, Ui.dp(c, 52), 1f).apply { marginStart = Ui.dp(c, 8) })
        h.addView(btns, Ui.lp(c, 20))
        return h
    }

    /** « Plus » : les autres gestes (autre durée, récompense, changer qui regarde). */
    private fun moreActions(rules: fr.paul.telecommande.shared.BoxRules) {
        val items = mutableListOf("Ajouter un autre temps…" to { addTime() }, "Récompense" to { BoxActions.reward(this, device) })
        if (rules.profiles.isNotEmpty()) items += "Changer qui regarde" to { BoxActions.pickProfile(this, device, rules.profiles) { lifecycleScope.launch { load() } } }
        AlertDialog.Builder(this).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    /** « + 15 min » sur le temps de TV total, avec l'empreinte ou le code parent. */
    private fun quickBonus(minutes: Int) {
        if (!unlocked) { Ui.requirePin(this, "Code parent pour ajouter du temps.") { unlocked = true; quickBonus(minutes) }; return }
        lifecycleScope.launch {
            runCatching { BoxClient.of(this@BoxDashboardActivity, device).call("bonus", JSONObject().put("pkg", "*").put("minutes", minutes)) }
                .onSuccess { Ui.toast(this@BoxDashboardActivity, "+$minutes min accordées") }
                .onFailure { Ui.toast(this@BoxDashboardActivity, "TV injoignable : réessayez dans un instant") }
            load()
        }
    }

    /** Bandeau « Activité » : le profil choisi, son temps du jour en très grand, sa limite et sa semaine en barres. */
    private fun activityHero(rules: fr.paul.telecommande.shared.BoxRules): LinearLayout {
        val c = this
        val h = Premium.hero(c)
        heroTop(h)
        h.addView(Premium.heroText(c, "Activité", 28f, bold = true), Ui.lp(c, 18))
        val p = activityProfile(rules)
        if (rules.profiles.size > 1) h.addView(profileChips(rules, p), Ui.lp(c, 14))

        val usage = status?.optJSONObject("profileUsage")?.optJSONObject(p?.id ?: "*")
        val todayMs = when {
            p == null -> status?.optLong("todayTotal") ?: 0L
            usage != null -> usage.optLong("ms")
            filtered(p, today) -> today?.optJSONObject("byApp")?.let { o -> o.keys().asSequence().sumOf { o.optLong(it) } } ?: 0L
            else -> 0L
        }
        val limit = limitOf(rules, p)
        h.addView(Premium.heroText(c, if (p == null) "Aujourd'hui sur la TV" else "${p.emoji}  ${p.name} aujourd'hui", 13.5f, soft = true), Ui.lp(c, 14))
        h.addView(Premium.bigNumber(c, Usage.format(todayMs), if (limit > 0) "sur " + Usage.format(limit * 60_000L) else "", 56f), Ui.lp(c, 4))
        h.addView(Premium.heroText(c, todayCaption(rules, p, todayMs, limit, usage), 13f, soft = true), Ui.lp(c, 6))
        if (limit > 0) h.addView(Premium.heroProgress(c, todayMs.toFloat() / (limit * 60_000L)), LinearLayout.LayoutParams(-1, Ui.dp(c, 6)).apply { topMargin = Ui.dp(c, 12) })

        val w = week
        if (w == null) { h.addView(Premium.heroText(c, loadingText(), 13f, soft = true), Ui.lp(c, 14)); return h }
        val now = System.currentTimeMillis()
        val days = (6 downTo 0).map { Usage.addDays(Usage.startOfDay(now), -it) }
        val daily = w.optJSONArray("daily")
        val values = days.indices.map { i -> daily?.optLong(i) ?: 0L }
        val active = values.count { it > 0 }.coerceAtLeast(1)
        h.addView(Premium.heroText(c, "Moyenne : ${Usage.format(values.sum() / active)} par jour regardé (7 jours)", 13f, soft = true), Ui.lp(c, 14))
        val fmt = SimpleDateFormat("EEE", Locale.FRANCE)
        h.addView(BarChartView(c).apply {
            heightDp = 130; onDark()
            labels = days.map { fmt.format(Date(it)).trimEnd('.').replaceFirstChar { ch -> ch.uppercase() } }
            limitMin = limit
            valuesMin = values.map { (it / 60_000).toInt() }
        }, Ui.lp(c, 14))
        if (limit > 0)
            h.addView(Premium.heroText(c, "Pointillés : limite de ${Usage.format(limit * 60_000L)} · ambre : dépassée · menthe : aujourd'hui", 12f, soft = true), Ui.lp(c, 6))
        if (p != null && !filtered(p, w))
            h.addView(Premium.heroText(c, "Mettez à jour l'appli de la TV pour voir le détail de ce profil (en attendant : toute la TV).", 12f, soft = true), Ui.lp(c, 8))
        return h
    }

    /** Une ligne sous le grand chiffre : temps restant, dépassement ou « sans limite ». */
    private fun todayCaption(rules: fr.paul.telecommande.shared.BoxRules, p: fr.paul.telecommande.shared.Profile?, todayMs: Long, limit: Int, usage: JSONObject?): String {
        if (!rules.enabled) return "Contrat en pause : aucune limite"
        if (p?.unlimited == true) return "Profil parent : aucune limite"
        if (limit <= 0) return "Pas de limite par jour"
        // La TV donne le temps restant exact (bonus compris) quand elle le connaît
        val left = usage?.takeIf { it.has("left") }?.optLong("left")?.takeIf { it >= 0 } ?: (limit * 60_000L - todayMs)
        return if (left <= 0) "Temps du jour écoulé" else "Reste " + Usage.format(left) + " aujourd'hui"
    }

    /** Pastilles de profil dans le bandeau : on choisit l'enfant dont on veut voir l'activité. */
    private fun profileChips(rules: fr.paul.telecommande.shared.BoxRules, sel: fr.paul.telecommande.shared.Profile?): android.view.View {
        val c = this
        val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        rules.profiles.forEach { p ->
            val on = p.id == sel?.id
            row.addView(android.widget.TextView(c).apply {
                text = "${p.emoji}  ${p.name}"; textSize = 14f; maxLines = 1; gravity = Gravity.CENTER
                typeface = if (on) Ui.bold else Ui.medium
                setTextColor(if (on) Ui.color(c, R.color.hero_bg) else android.graphics.Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = Ui.dp(c, 20).toFloat()
                    if (on) setColor(android.graphics.Color.WHITE) else { setColor(0x14FFFFFF); setStroke(Ui.dp(c, 1), 0x40FFFFFF) }
                }
                minHeight = Ui.dp(c, 44); setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0)
                contentDescription = "Voir l'activité de ${p.name}"
                isClickable = true; isFocusable = true
                setOnClickListener {
                    if (on) return@setOnClickListener
                    actProfileId = p.id; week = null; today = null; render()
                    lifecycleScope.launch { load() }
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(c, 8) })
        }
        return android.widget.HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    /** Encart « demande de temps » : texte + deux boutons ronds Refuser / Accepter. */
    private fun requestCard(r: JSONObject): android.view.View {
        val c = this
        val card = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Ui.color(c, R.color.notice_bg)); cornerRadius = Ui.dp(c, 26).toFloat() }
            setPadding(Ui.dp(c, 18), Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14))
        }
        val t = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        t.addView(Ui.text(c, "+${r.optInt("minutes")} min demandées", 15.5f).apply { typeface = Ui.bold })
        t.addView(Ui.text(c, r.optString("label").ifBlank { "Depuis la TV" }, 13f).apply { setTextColor(Ui.color(c, R.color.notice_ink)) })
        card.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        fun round(icon: Int, desc: String, bg: Int, fg: Int, action: () -> Unit) = android.widget.ImageButton(c).apply {
            setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(fg); contentDescription = desc
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(bg) }
            setOnClickListener { action() }
        }
        card.addView(round(R.drawable.ic_close, "Refuser", Ui.color(c, R.color.surface), Ui.color(c, R.color.ink)) { respond(r, false) },
            LinearLayout.LayoutParams(Ui.dp(c, 46), Ui.dp(c, 46)).apply { marginEnd = Ui.dp(c, 8) })
        card.addView(round(R.drawable.ic_ok, "Accepter", Ui.color(c, R.color.primary), android.graphics.Color.WHITE) { respond(r, true) },
            LinearLayout.LayoutParams(Ui.dp(c, 46), Ui.dp(c, 46)))
        return card
    }

    private var unlocked = false

    /** « Parental » : la règle du jour en une phrase, un réglage rapide du temps, les actions et les applis. */
    private fun renderParental(rules: fr.paul.telecommande.shared.BoxRules) {
        val c = this
        // ----- Profils : les règles de temps sont propres à chaque profil -----
        val watching = status?.optJSONObject("profile")?.optString("id")
        val profiles = rules.profiles
        if (selected == null || (selected != FAMILY && profiles.none { it.id == selected }))
            selected = watching?.takeIf { w -> profiles.any { it.id == w } } ?: profiles.firstOrNull { !it.unlimited }?.id ?: FAMILY
        val head = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.title(c, "Profils", 18f), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Ui.text(c, "+ Ajouter", 14f).apply {
            setTextColor(Ui.color(c, R.color.accent)); typeface = Ui.medium; setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10))
            isClickable = true; isFocusable = true; contentDescription = "Ajouter un profil"
            setOnClickListener { openRules(-1) }
        })
        root.addView(head, Ui.lp(c, 18))
        val strip = LinearLayout(c).apply { setPadding(0, Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 6)) }
        profiles.forEach { p -> strip.addView(profileTile(rules, p, p.id == selected, p.id == watching) { selected = p.id; render() }) }
        strip.addView(profileTile(rules, null, selected == FAMILY, false) { selected = FAMILY; render() })
        root.addView(android.widget.HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false
            isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(Ui.dp(c, 32))
            addView(strip)
        }, Ui.lp(c, 4))

        // ----- La règle du profil choisi, en une phrase + réglage rapide -----
        val prof = profiles.firstOrNull { it.id == selected }
        val sum = Ui.card(c)
        if (prof != null && prof.id == watching) sum.addView(Ui.pill(c, "Devant la TV en ce moment", Ui.color(c, R.color.ok)), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Ui.dp(c, 12) })
        sum.addView(Ui.text(c, "", 16.5f).apply { text = sentence(rules, prof); setLineSpacing(0f, 1.2f) })
        if (profiles.isEmpty()) sum.addView(Ui.text(c, "Créez un profil par enfant : chacun aura son temps de TV et ses programmes selon son âge. La TV demande « Qui regarde ? » à l'allumage.", 13f, muted = true), Ui.lp(c, 10))
        val editable = rules.enabled && prof?.unlimited != true
        if (editable) {
            val effective = if (prof != null && prof.dailyTotalMin > 0) prof.dailyTotalMin else rules.dailyTotalMin
            val step = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply { setColor(Ui.color(c, R.color.surface2)); cornerRadius = Ui.dp(c, 30).toFloat() }
                setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6))
            }
            fun stepBtn(label: String, desc: String, primary: Boolean, delta: Int) = android.widget.TextView(c).apply {
                text = label; gravity = Gravity.CENTER; textSize = 22f; contentDescription = desc
                setTextColor(if (primary) android.graphics.Color.WHITE else Ui.color(c, R.color.ink))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Ui.color(c, if (primary) R.color.primary else R.color.surface))
                }
                elevation = Ui.dp(c, 1).toFloat()
                isClickable = true; isFocusable = true
                setOnClickListener { changeDaily(prof?.id, delta) }
            }
            step.addView(stepBtn("−", "Réduire de 15 minutes", false, -15), LinearLayout.LayoutParams(Ui.dp(c, 50), Ui.dp(c, 50)))
            val mid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            mid.addView(Ui.title(c, if (effective > 0) Usage.format(effective * 60_000L) else "Sans limite", 22f).apply { gravity = Gravity.CENTER })
            mid.addView(Ui.text(c, when {
                prof == null -> "par jour · toute la famille"
                prof.dailyTotalMin == 0 -> "par jour · comme la famille"
                else -> "par jour pour ${prof.name}"
            }, 12f, muted = true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            step.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
            step.addView(stepBtn("+", "Ajouter 15 minutes", true, 15), LinearLayout.LayoutParams(Ui.dp(c, 50), Ui.dp(c, 50)))
            sum.addView(step, Ui.lp(c, 16))
        }
        val links = LinearLayout(c)
        val idx = if (prof != null) profiles.indexOf(prof) else -2
        links.addView(Ui.button(c, when { !rules.enabled -> "Activer et régler"; prof != null -> "Modifier le profil"; profiles.isEmpty() -> "Créer un profil"; else -> "Toutes les règles" },
            primary = !rules.enabled || profiles.isEmpty()) {
            openRules(if (prof != null) idx else if (profiles.isEmpty() && rules.enabled) -1 else -2)
        }.compact(), LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1f).apply { marginEnd = Ui.dp(c, 5) })
        links.addView(Ui.button(c, if (prof != null) "Toutes les règles" else "Pas à pas") {
            if (prof != null) openRules(-2)
            else startActivity(Intent(c, RulesWizardActivity::class.java).putExtra(RulesWizardActivity.EXTRA_ID, device.id))
        }.compact(), LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1f).apply { marginStart = Ui.dp(c, 5) })
        sum.addView(links, Ui.lp(c, 14))
        root.addView(sum, Ui.lp(c, 10))

        // ----- Conseils de l'assistant : règles qui se contredisent ou ne servent à rien -----
        val names = status?.optJSONObject("labels")
        val advices = fr.paul.telecommande.shared.RulesAdvisor.check(rules, { pkg -> names?.optString(pkg)?.ifBlank { null } ?: AppCatalog.name(pkg) })
            .filter { it.id !in Premium.dismissed(c, device.id) }
        if (rules.enabled && advices.isNotEmpty()) {
            val ah = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
            ah.addView(Ui.title(c, "L'assistant a repéré", 18f), LinearLayout.LayoutParams(0, -2, 1f))
            if (advices.size > 2) ah.addView(Ui.text(c, "Tout voir (${advices.size})", 14f).apply {
                setTextColor(Ui.color(c, R.color.accent)); typeface = Ui.medium; setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10))
                isClickable = true; isFocusable = true
                setOnClickListener { startActivity(Intent(c, RulesWizardActivity::class.java).putExtra(RulesWizardActivity.EXTRA_ID, device.id).putExtra(RulesWizardActivity.EXTRA_CHECK, true)) }
            })
            root.addView(ah, Ui.lp(c, 24))
            advices.take(2).forEach { adv ->
                root.addView(Premium.adviceCard(c, adv, onFix = { f -> applyFix(f) }, onDismiss = { Premium.dismiss(c, device.id, adv.id); render() }), Ui.lp(c, 10))
            }
        }

        // ----- Applis : temps du jour et limite -----
        val appsHead = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        appsHead.addView(Ui.title(c, "Applis", 18f), LinearLayout.LayoutParams(0, -2, 1f))
        appsHead.addView(Ui.text(c, "Tout voir", 14f).apply {
            setTextColor(Ui.color(c, R.color.accent)); typeface = Ui.medium; setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10))
            isClickable = true; isFocusable = true
            setOnClickListener { startActivity(Intent(c, BoxRulesActivity::class.java).putExtra(BoxRulesActivity.EXTRA_ID, device.id)) }
        })
        root.addView(appsHead, Ui.lp(c, 24))
        val apps = Ui.group(c)
        val today = status?.optJSONObject("today"); val labels = status?.optJSONObject("labels")
        val pkgs = ((today?.keys()?.asSequence()?.toList() ?: emptyList()) + rules.apps.filterValues { !it.isDefault }.keys).distinct()
            .sortedByDescending { today?.optLong(it) ?: 0L }.take(5)
        if (pkgs.isEmpty()) apps.addView(Ui.text(c, if (status == null) loadingText() else "Aucune appli regardée aujourd'hui.", 14f, muted = true).apply {
            setPadding(Ui.dp(c, 18), Ui.dp(c, 16), Ui.dp(c, 18), Ui.dp(c, 16))
        })
        pkgs.forEach { pkg ->
            val ms = today?.optLong(pkg) ?: 0L
            val r = if (rules.enabled) rules.apps[pkg] else null
            val limit = r?.limitMin ?: 0
            val name = labels?.optString(pkg)?.ifBlank { null } ?: AppCatalog.name(pkg)
            val sub = when {
                r?.blocked == true -> "Bloquée"
                limit > 0 -> Usage.format(ms) + " sur " + Usage.format(limit * 60_000L)
                else -> Usage.format(ms) + " aujourd'hui"
            }
            val row = Ui.row(c, "📺", name, sub, value = if (r?.blocked == true) "⛔" else if (limit > 0) Usage.format(limit * 60_000L) else "—",
                danger = r?.blocked == true || (limit > 0 && ms >= limit * 60_000L), icon = appIcon(pkg)) {
                startActivity(Intent(c, BoxRulesActivity::class.java).putExtra(BoxRulesActivity.EXTRA_ID, device.id))
            }
            Ui.addRow(apps, row)
        }
        root.addView(apps, Ui.lp(c, 6))

        // ----- Réglages et confidentialité -----
        root.addView(section("La TV"), Ui.lp(c, 26))
        val set = Ui.group(c)
        Ui.addRow(set, Ui.row(c, "🩺", "Santé de la TV", "Mémoire, stockage, connexion") { BoxActions.diagnostics(c, device) })
        val since = week?.optLong("since")?.takeIf { it > 0 }
        Ui.addRow(set, Ui.row(c, "🔒", "Vos données restent chez vous",
            "Sur la box et ce téléphone uniquement, échangées en chiffré. Aucun compte, aucun serveur." +
                (if (since != null) "\nMesuré depuis le " + DateFormat.getDateInstance(DateFormat.LONG, Locale.FRANCE).format(Date(since)) + "." else "")).apply {
            (getChildAt(1) as? LinearLayout)?.getChildAt(1)?.let { (it as? android.widget.TextView)?.maxLines = 4 }
        })
        Ui.addRow(set, Ui.row(c, "⛓️", "Retirer l'appairage", "La TV n'appliquera plus vos règles à jour", danger = true) {
            Ui.requirePin(c, "Retirer l'appairage ? La TV n'appliquera plus vos règles à jour.") {
                lifecycleScope.launch {
                    runCatching { BoxClient.of(c, device).call("unpair") }
                    device.boxFp = ""; DeviceStore(c).save(device); BoxLink.sync(c); finish()
                }
            }
        })
        root.addView(set, Ui.lp(c, 8))
    }

    /** Bouton de demi-largeur : une seule ligne, jamais coupé. */
    private fun android.widget.TextView.compact() = apply {
        maxLines = 1; textSize = 14f; minHeight = 0; setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 10), 0)
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** La règle du profil (ou de toute la famille), en français courant, chiffres en couleur. */
    private fun sentence(r: fr.paul.telecommande.shared.BoxRules, p: fr.paul.telecommande.shared.Profile?): CharSequence {
        val b = android.text.SpannableStringBuilder()
        fun strong(t: String) {
            val s = b.length; b.append(t)
            b.setSpan(android.text.style.ForegroundColorSpan(Ui.color(this, R.color.accent)), s, b.length, 0)
            b.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s, b.length, 0)
        }
        fun hm(m: Int) = if (m % 60 == 0) "${m / 60} h" else "${m / 60} h %02d".format(m % 60)
        if (!r.enabled) { b.append("Le contrôle parental est "); strong("en pause"); b.append(" : la TV n'a aucune limite, pour aucun profil."); return b }
        if (p != null && p.unlimited) { strong(p.name); b.append(" : "); strong("aucune limite"); b.append(". Ce profil est protégé par le code parent."); return b }
        val daily = if (p != null && p.dailyTotalMin > 0) p.dailyTotalMin else r.dailyTotalMin
        if (p != null) { strong(p.name); p.age()?.let { b.append(" ($it ans)") }; b.append(" peut regarder la TV ") }
        else b.append("Sans profil choisi, la TV peut être regardée ")
        if (daily > 0) strong(Usage.format(daily * 60_000L) + " par jour") else strong("sans limite de temps")
        if (r.activeFrom != r.activeTo) { b.append(", entre "); strong(hm(r.activeFrom)); b.append(" et "); strong(hm(r.activeTo)) }
        b.append(".")
        if (r.bedtimeEnabled) { b.append(" Couvre-feu à "); strong(hm(r.bedtimeStart)); b.append(".") }
        val age = p?.programMaxAge() ?: r.maxAge
        if (age > 0) { b.append(" Programmes "); strong("jusqu'à $age ans"); b.append(".") }
        val blocked = r.apps.values.count { it.blocked }
        if (blocked > 0) { b.append(" "); strong("$blocked appli" + if (blocked > 1) "s bloquées" else " bloquée"); b.append(".") }
        return b
    }

    private val FAMILY = "*famille*"
    private var selected: String? = null

    /** Ouvre les règles complètes ; [profileIndex] ≥ 0 : directement la fiche du profil, −1 : nouveau profil. */
    private fun openRules(profileIndex: Int) {
        startActivity(Intent(this, BoxRulesActivity::class.java).putExtra(BoxRulesActivity.EXTRA_ID, device.id)
            .apply { if (profileIndex >= -1) putExtra(BoxRulesActivity.EXTRA_PROFILE, profileIndex) })
    }

    /** Applique une correction proposée par l'assistant (code parent), puis l'envoie à la TV. */
    private fun applyFix(f: fr.paul.telecommande.shared.RulesAdvisor.Fix) {
        if (!unlocked) { Ui.requirePin(this, "Code parent pour corriger la règle.") { unlocked = true; applyFix(f) }; return }
        val store = ParentalStore(this)
        val r = store.boxRules(device.id)
        f.applyTo(r)
        r.pinHash = store.boxPinHash
        r.version = System.currentTimeMillis()
        store.saveBoxRules(device.id, r)
        render()
        lifecycleScope.launch {
            runCatching { BoxClient.of(this@BoxDashboardActivity, device).call("set_rules", JSONObject().put("rules", r.toJson())) }
                .onSuccess { Ui.toast(this@BoxDashboardActivity, "Règle corrigée sur la TV ✓") }
                .onFailure { Ui.toast(this@BoxDashboardActivity, "TV injoignable : la correction partira à sa prochaine connexion") }
        }
    }

    /** Tuile de profil : nom et âge, temps du jour en grand, limite, barre ; cerclée d'encre quand elle est choisie. */
    private fun profileTile(rules: fr.paul.telecommande.shared.BoxRules, p: fr.paul.telecommande.shared.Profile?, on: Boolean, live: Boolean, action: () -> Unit) = LinearLayout(this).apply {
        val c = this@BoxDashboardActivity
        orientation = LinearLayout.VERTICAL
        background = Premium.tileBg(c, on)
        setPadding(Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 14))
        isClickable = true; isFocusable = true
        setOnClickListener { action() }
        val name = p?.name ?: "Famille"
        val head = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(c, p?.emoji ?: "👪", 20f).apply { importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(c, 8) })
        head.addView(Ui.text(c, name, 14.5f).apply { typeface = Ui.bold; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        if (live) head.addView(android.view.View(c).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Ui.color(c, R.color.ok)) }
            contentDescription = "devant la TV"
        }, LinearLayout.LayoutParams(Ui.dp(c, 10), Ui.dp(c, 10)))
        addView(head)
        val (used, _) = profileTime(rules, p)
        val limit = limitOf(rules, p)
        val hasUsage = p == null || status?.optJSONObject("profileUsage")?.has(p.id) == true || rules.profiles.isEmpty()
        val over = limit > 0 && used >= limit * 60_000L
        addView(Ui.title(c, when {
            p?.unlimited == true -> "Libre"
            hasUsage -> Usage.format(used)
            limit > 0 -> Usage.format(limit * 60_000L)
            else -> "—"
        }, 26f).apply { maxLines = 1; if (over) setTextColor(Ui.color(c, R.color.danger)) }, Ui.lp(c, 12))
        addView(Ui.text(c, when {
            p?.unlimited == true -> "profil parent"
            hasUsage && limit > 0 -> "sur ${Usage.format(limit * 60_000L)} aujourd'hui"
            hasUsage -> "aujourd'hui"
            limit > 0 -> "par jour" + (p?.age()?.let { " · $it ans" } ?: "")
            else -> p?.age()?.let { "$it ans" } ?: "règles générales"
        }, 12f, muted = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, Ui.lp(c, 4))
        if (hasUsage && limit > 0) addView(Ui.progress(c, used.toFloat() / (limit * 60_000L), over), LinearLayout.LayoutParams(-1, Ui.dp(c, 5)).apply { topMargin = Ui.dp(c, 10) })
        contentDescription = "$name" + (if (live) ", devant la TV" else "") + if (on) ", sélectionné" else ""
        layoutParams = LinearLayout.LayoutParams(Ui.dp(c, 156), -2).apply { marginEnd = Ui.dp(c, 10) }
    }

    /** Réglage rapide du temps par jour (pas de 15 min), protégé par le code parent, envoyé tout de suite à la TV. */
    private fun changeDaily(profileId: String?, delta: Int) {
        val apply = {
            unlocked = true
            val store = ParentalStore(this)
            val r = store.boxRules(device.id)
            val i = r.profiles.indexOfFirst { it.id == profileId }
            if (i >= 0) {
                // Profil enfant : son propre temps (au moins 15 min ; il part du temps de la famille s'il n'en avait pas)
                val p = r.profiles[i]
                val base = if (p.dailyTotalMin > 0) p.dailyTotalMin else r.dailyTotalMin.takeIf { it > 0 } ?: 120
                r.profiles[i] = p.copy(dailyTotalMin = (base + delta).coerceIn(15, 24 * 60))
            } else r.dailyTotalMin = (r.dailyTotalMin + delta).coerceIn(0, 24 * 60)
            r.pinHash = store.boxPinHash
            r.version = System.currentTimeMillis()
            store.saveBoxRules(device.id, r)
            render()
            lifecycleScope.launch {
                runCatching { BoxClient.of(this@BoxDashboardActivity, device).call("set_rules", JSONObject().put("rules", r.toJson())) }
                    .onFailure { Ui.toast(this@BoxDashboardActivity, "TV injoignable : la nouvelle limite sera envoyée à sa prochaine connexion") }
            }
        }
        if (unlocked) apply() else Ui.requirePin(this, "Code parent pour modifier le temps par jour.") { apply() }
    }

    /** « Activité » : qui regarde en ce moment et ce qui passe, puis le détail du profil choisi en onglets. */
    private fun renderActivity(rules: fr.paul.telecommande.shared.BoxRules) {
        val c = this
        val st = status
        val hero = Ui.card(c)
        val who = st?.optJSONObject("profile")
        hero.addView(Ui.label(c, "En ce moment"))
        hero.addView(Ui.text(c, when {
            st == null -> "—"
            !st.optBoolean("interactive") -> "Personne devant la TV"
            who != null -> "${who.optString("emoji")} ${who.optString("name")} regarde la TV"
            else -> "La TV est allumée"
        }, 19f).apply { typeface = Ui.bold; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, Ui.lp(c, 4))

        st?.optJSONObject("media")?.let { m ->
            hero.addView(Ui.divider(c), LinearLayout.LayoutParams(-1, 1).apply { topMargin = Ui.dp(c, 16) })
            val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dp(c, 14), 0, 0) }
            m.optString("art").takeIf { it.isNotBlank() }?.let { b64 ->
                runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp ->
                        row.addView(ImageView(c).apply {
                            setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Ui.dp(c, 10).toFloat() }
                            importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, LinearLayout.LayoutParams(Ui.dp(c, 80), Ui.dp(c, 45)).apply { marginEnd = Ui.dp(c, 12) })
                    }
                }
            }
            val t = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            t.addView(Ui.text(c, "▶  " + m.optString("title"), 14.5f).apply { typeface = Ui.medium; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            t.addView(Ui.text(c, listOf(m.optString("subtitle"), AppCatalog.name(m.optString("pkg"))).filter { it.isNotBlank() }.joinToString(" · "), 12.5f, muted = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            hero.addView(row)
        }
        root.addView(hero, Ui.lp(c, 14))

        root.addView(section("Détail"), Ui.lp(c, 24))
        val act = Ui.card(c)
        act.addView(Ui.tabs(c, listOf("Aujourd'hui", "Semaine", "Horaires"), tab) { tab = it; render() })
        when (tab) {
            0 -> todayTab(act, rules)
            1 -> weekTab(act, rules)
            else -> hoursTab(act)
        }
        root.addView(act, Ui.lp(c, 8))
    }

    private fun rulesSummary(rules: fr.paul.telecommande.shared.BoxRules): String {
        if (!rules.enabled) return "Désactivées"
        val parts = mutableListOf<String>()
        parts += if (rules.dailyTotalMin > 0) Usage.format(rules.dailyTotalMin * 60_000L) + " par jour" else "sans limite par jour"
        if (rules.bedtimeEnabled) parts += "couvre-feu " + fr.paul.telecommande.shared.RulesEngine.hhmm(rules.bedtimeStart)
        val n = rules.apps.values.count { !it.isDefault }
        if (n > 0) parts += "$n appli" + (if (n > 1) "s" else "")
        return parts.joinToString(" · ")
    }

    private fun loadingText() = if (error != null) "Pas de données : la TV ne répond pas pour l'instant." else "Chargement…"

    private fun appIcon(pkg: String) = runCatching { fr.paul.telecommande.core.AppIcons.icon(this, device.id, pkg) }.getOrNull()

    private fun todayTab(card: LinearLayout, rules: fr.paul.telecommande.shared.BoxRules) {
        val c = this
        val st = status
        val p = activityProfile(rules)
        // Profil choisi : ses applis du jour ; sans profils (ou ancienne appli TV) : toute la TV
        val own = today?.takeIf { filtered(p, it) }
        val todayMap = own?.optJSONObject("byApp") ?: st?.optJSONObject("today")
        val appLabels = own?.optJSONObject("labels") ?: st?.optJSONObject("labels")
        val entries = todayMap?.keys()?.asSequence()?.map { it to todayMap.optLong(it) }?.filter { it.second > 0 }?.sortedByDescending { it.second }?.toList() ?: emptyList()
        if (entries.isEmpty()) { card.addView(Ui.text(c, if (st == null) loadingText() else if (p != null) "${p.name} n'a rien regardé aujourd'hui." else "Rien de regardé aujourd'hui.", 14f, muted = true), Ui.lp(c, 16)); return }
        // Les limites par appli ne concernent pas le profil parent
        val limited = rules.enabled && p?.unlimited != true
        val max = entries.maxOf { it.second }
        val shown = if (showAllApps) entries else entries.take(4)
        shown.forEach { (pkg, ms) ->
            val limit = if (limited) rules.apps[pkg]?.limitMin ?: 0 else 0
            val over = limit > 0 && ms >= limit * 60_000L
            val line = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dp(c, 14), 0, 0) }
            val icon = appIcon(pkg)
            if (icon != null) line.addView(ImageView(c).apply {
                setImageBitmap(icon); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Ui.dp(c, 8).toFloat() }
                importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(Ui.dp(c, 32), Ui.dp(c, 32)).apply { marginEnd = Ui.dp(c, 12) })
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            val top = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(Ui.text(c, appLabels?.optString(pkg)?.ifBlank { null } ?: AppCatalog.name(pkg), 15f).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(Ui.text(c, Usage.format(ms) + if (limit > 0) " / ${Usage.format(limit * 60_000L)}" else "", 13.5f, muted = !over).apply {
                if (over) setTextColor(Ui.color(c, R.color.danger)); setPadding(Ui.dp(c, 8), 0, 0, 0)
            })
            col.addView(top)
            val ratio = if (limit > 0) ms.toFloat() / (limit * 60_000L) else ms.toFloat() / max
            col.addView(Ui.progress(c, ratio, over), LinearLayout.LayoutParams(-1, Ui.dp(c, 5)).apply { topMargin = Ui.dp(c, 6) })
            line.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(line)
        }
        if (entries.size > 4) card.addView(Ui.text(c, if (showAllApps) "Voir moins" else "Voir les ${entries.size - 4} autres", 14f).apply {
            setTextColor(Ui.color(c, R.color.accent)); typeface = Ui.medium; gravity = Gravity.CENTER
            setPadding(0, Ui.dp(c, 14), 0, Ui.dp(c, 2)); isClickable = true; isFocusable = true
            setOnClickListener { showAllApps = !showAllApps; render() }
        }, Ui.lp(c, 4))
    }

    private fun weekTab(card: LinearLayout, rules: fr.paul.telecommande.shared.BoxRules) {
        val c = this
        val w = week ?: run { card.addView(Ui.text(c, loadingText(), 14f, muted = true), Ui.lp(c, 16)); return }
        val now = System.currentTimeMillis()
        val days = (6 downTo 0).map { Usage.addDays(Usage.startOfDay(now), -it) }
        val fmt = SimpleDateFormat("EEE", Locale.FRANCE)
        val daily = w.optJSONArray("daily")
        val values = days.indices.map { i -> daily?.optLong(i) ?: 0L }
        val total = values.sum()
        val activeDays = values.count { it > 0 }.coerceAtLeast(1)
        val stats = LinearLayout(c).apply { setPadding(0, Ui.dp(c, 16), 0, 0) }
        fun stat(v: String, l: String) = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.title(c, v, 22f)); addView(Ui.text(c, l, 12f, muted = true))
        }
        stats.addView(stat(Usage.format(total), "sur 7 jours"), LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(stat(Usage.format(total / activeDays), "par jour regardé"), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(stats)

        val byApp = w.optJSONObject("byApp"); val wl = w.optJSONObject("labels")
        if (byApp != null && byApp.length() > 0) {
            card.addView(Ui.divider(c), LinearLayout.LayoutParams(-1, 1).apply { topMargin = Ui.dp(c, 16) })
            card.addView(Ui.label(c, "Applis les plus utilisées"), Ui.lp(c, 14))
            val wMap = byApp.keys().asSequence().associateWith { byApp.optLong(it) }
            val wSlices = PieChartView.slicesFor(wMap) { pkg -> wl?.optString(pkg)?.ifBlank { null } ?: AppCatalog.name(pkg) }
            val wColor = wSlices.associate { it.key to it.color }
            val pieRow = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
            pieRow.addView(PieChartView(c).apply {
                heightDp = 120; slices = wSlices; centerTitle = ""; centerSub = ""
            }, LinearLayout.LayoutParams(Ui.dp(c, 120), Ui.dp(c, 120)).apply { marginEnd = Ui.dp(c, 14) })
            val legend = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            wMap.entries.sortedByDescending { it.value }.take(5).forEach { (pkg, ms) ->
                val line = LinearLayout(c).apply { setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4)); gravity = Gravity.CENTER_VERTICAL }
                line.addView(android.view.View(c).apply {
                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(wColor[pkg] ?: 0xFF9CA3AF.toInt()) }
                }, LinearLayout.LayoutParams(Ui.dp(c, 10), Ui.dp(c, 10)).apply { marginEnd = Ui.dp(c, 8) })
                line.addView(Ui.text(c, wl?.optString(pkg)?.ifBlank { null } ?: AppCatalog.name(pkg), 13.5f).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                line.addView(Ui.text(c, Usage.format(ms), 13f, muted = true).apply { setPadding(Ui.dp(c, 6), 0, 0, 0) })
                legend.addView(line)
            }
            pieRow.addView(legend, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(pieRow, Ui.lp(c, 10))
        }
    }

    private fun hoursTab(card: LinearLayout) {
        val c = this
        val w = week ?: run { card.addView(Ui.text(c, loadingText(), 14f, muted = true), Ui.lp(c, 16)); return }
        val hourly = w.optJSONArray("hourly")
        card.addView(Ui.text(c, "À quelles heures la TV est regardée (7 derniers jours).", 13f, muted = true), Ui.lp(c, 14))
        card.addView(BarChartView(c).apply {
            heightDp = 120; showValues = false
            highlight = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            labels = (0 until 24).map { if (it % 6 == 0) "${it}h" else "" }
            valuesMin = (0 until 24).map { ((hourly?.optLong(it) ?: 0L) / 60_000).toInt() }
        }, Ui.lp(c, 10))
        card.addView(Ui.text(c, peakText(hourly) ?: "Pas encore assez de données.", 13.5f), Ui.lp(c, 10))
    }

    private fun peakText(hourly: org.json.JSONArray?): String? {
        if (hourly == null) return null
        val v = (0 until 24).map { hourly.optLong(it) }
        val total = v.sum(); if (total <= 0) return null
        val peak = v.indices.maxByOrNull { v[it] } ?: return null
        val evening = (18..23).sumOf { v[it] } * 100 / total
        return "Pic vers ${peak}h · $evening % du temps entre 18h et minuit"
    }

    private fun respond(r: JSONObject, accept: Boolean) {
        // Accorder du temps demande le parent (comme la récompense et le temps par jour) ; refuser, non.
        if (accept && !unlocked) { Ui.requirePin(this, "Code parent pour accorder ce temps.") { unlocked = true; respond(r, true) }; return }
        lifecycleScope.launch {
            BoxLink.respond(this@BoxDashboardActivity, device, r.optString("id"), accept, r.optInt("minutes", 15))
                .onFailure { Ui.toast(this@BoxDashboardActivity, "Erreur : ${it.message}") }
            load()
        }
    }

    private fun addTime() {
        if (!unlocked) { Ui.requirePin(this, "Code parent pour ajouter du temps.") { unlocked = true; addTime() }; return }
        val st = status
        val todayMap = st?.optJSONObject("today"); val appLabels = st?.optJSONObject("labels")
        val pkgs = listOf("*") + (todayMap?.keys()?.asSequence()?.toList() ?: emptyList())
        val names = pkgs.map { if (it == "*") "Temps de TV total" else appLabels?.optString(it)?.ifBlank { null } ?: AppCatalog.name(it) }
        AlertDialog.Builder(this).setTitle("Ajouter du temps sur…").setItems(names.toTypedArray()) { _, i ->
            val opts = listOf(10, 15, 30, 60)
            AlertDialog.Builder(this).setTitle(names[i]).setItems(opts.map { "+$it min" }.toTypedArray()) { _, j ->
                lifecycleScope.launch {
                    runCatching { BoxClient.of(this@BoxDashboardActivity, device).call("bonus", JSONObject().put("pkg", pkgs[i]).put("minutes", opts[j])) }
                        .onSuccess { Ui.toast(this@BoxDashboardActivity, "+${opts[j]} min accordées") }
                        .onFailure { Ui.toast(this@BoxDashboardActivity, "Erreur : ${it.message}") }
                    load()
                }
            }.show()
        }.show()
    }

}
