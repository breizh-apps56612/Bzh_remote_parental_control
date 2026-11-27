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

/**
 * Onglet « Enfants » (2.5.1) : tout ce qui concerne un enfant au même endroit, pour une TV équipée de l'appli Box.
 * En haut, on choisit l'enfant ; on voit son temps restant en très grand et les gestes du moment (+15 min, message).
 * Dessous : ses règles (son contrat, ou une limite simple), ses bonus, ce qu'il a regardé, puis « Plus ».
 * Les anciens écrans « Parental » et « Activité » sont regroupés ici.
 */
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
            AuroraNav.Tab.KIDS))
        Premium.darkStatusBar(this)
        render()
        lifecycleScope.launch { while (isActive) { load(); delay(30_000) } }
    }

    /** Retour du contrat ou des règles : on réaffiche avec les règles enregistrées. */
    override fun onResume() {
        super.onResume()
        if (::page.isInitialized) render()
    }

    private suspend fun load() {
        val c = BoxClient.of(this, device)
        val now = System.currentTimeMillis()
        val from = Usage.addDays(Usage.startOfDay(now), -6)
        runCatching {
            status = c.call("status")
            // Activité : les chiffres du profil choisi (le total de la TV n'a d'intérêt que sans profils)
            val sel = activityProfile(ParentalStore(this).boxRules(device.id))
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
    /** Profil affiché dans « Activité », ou null quand la TV n'a pas de profils (on montre alors toute la TV). */
    private fun activityProfile(rules: fr.paul.telecommande.shared.BoxRules): fr.paul.telecommande.shared.Profile? {
        val ps = rules.profiles
        if (ps.isEmpty()) return null
        selected?.let { id -> ps.firstOrNull { it.id == id }?.let { return it } }
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
        page.addView(familyHero(rules), LinearLayout.LayoutParams(-1, -2))
        root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(c, 16); setPadding(p, Ui.dp(c, 2), p, 0) }
        page.addView(root, LinearLayout.LayoutParams(-1, -2))
        if (error != null) root.addView(Ui.text(c, "Vérifiez que la TV est allumée et sur le même Wi-Fi. Nouvel essai toutes les 30 s.", 13f, muted = true), Ui.lp(c, 14))

        // ===== Demandes en attente (en premier : c'est ce qui attend une réponse) =====
        val pending = st?.optJSONArray("pending")
        if (pending != null && pending.length() > 0) {
            for (i in 0 until pending.length()) root.addView(requestCard(pending.getJSONObject(i)), Ui.lp(c, 14))
        }

        renderFamily(rules)
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
        val eff = rules.forProfile(p)
        if (!rules.enabled || !eff.enabled) return used to null
        if (u != null && u.has("left")) return used to u.optLong("left").takeIf { it >= 0 }
        val limit = eff.dailyTotalMin
        return used to if (limit > 0) (limit * 60_000L - used).coerceAtLeast(0) else null
    }

    private fun limitOf(rules: fr.paul.telecommande.shared.BoxRules, p: fr.paul.telecommande.shared.Profile?) =
        rules.forProfile(p).let { if (!rules.enabled || !it.enabled) 0 else it.dailyTotalMin }

    /** « Plus » : les autres gestes (autre durée, récompense, changer qui regarde). */
    private fun moreActions(rules: fr.paul.telecommande.shared.BoxRules) {
        val items = mutableListOf("Ajouter une autre durée, ou du temps sur une appli" to { addTime() },
            "Afficher « Bravo ! » sur la TV (récompense)" to { BoxActions.reward(this, device) })
        if (rules.profiles.isNotEmpty()) items += "Changer qui regarde la TV" to { BoxActions.pickProfile(this, device, rules.profiles) { lifecycleScope.launch { load() } } }
        AlertDialog.Builder(this).setTitle("Autres gestes").setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("Fermer", null).show()
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

    // ---------- Onglet « Enfants » ----------

    /**
     * Bandeau nuit : l'enfant choisi (pastilles), son temps restant en très grand, sa barre, l'extinction du soir,
     * et les trois gestes du moment. Sans profil : une invitation claire à ajouter un enfant.
     */
    private fun familyHero(rules: fr.paul.telecommande.shared.BoxRules): LinearLayout {
        val c = this
        val st = status
        val h = Premium.hero(c)
        heroTop(h)
        h.addView(Premium.heroText(c, "Enfants", 28f, bold = true), Ui.lp(c, 18))
        val p = activityProfile(rules)
        if (rules.profiles.isNotEmpty()) h.addView(profileChips(rules, p), Ui.lp(c, 14))
        if (p == null) {
            h.addView(Premium.heroText(c, "Aucun enfant pour l'instant", 20f, bold = true), Ui.lp(c, 18))
            h.addView(Premium.heroText(c, "Ajoutez un profil par enfant : chacun aura son temps de TV, des programmes adaptés à son âge et, si vous le souhaitez, son contrat.", 14f, soft = true), Ui.lp(c, 6))
            h.addView(Premium.heroButton(c, "Ajouter un enfant", true) { openRules(-1) }, LinearLayout.LayoutParams(-1, Ui.dp(c, 52)).apply { topMargin = Ui.dp(c, 18) })
            return h
        }
        val watching = st?.optJSONObject("profile")?.optString("id") == p.id && st?.optBoolean("interactive") == true
        h.addView(Premium.heroText(c, "${p.emoji}  ${p.name}" + (if (watching) " · devant la TV" else "") + (p.age()?.let { " · $it ans" } ?: ""), 14.5f, soft = true), Ui.lp(c, 16))
        val (used, left) = profileTime(rules, p)
        val limit = limitOf(rules, p)
        val (big, unit) = when {
            !rules.enabled -> "Libre" to "contrôle en pause"
            p.unlimited -> "Libre" to "profil parent"
            p.contract?.paused == true -> "Libre" to "contrat en pause"
            left != null && left <= 0 -> "0" to "min · temps écoulé"
            left != null -> ((left + 59_999) / 60_000).toInt().let { m -> if (m < 60) "$m" to "min restantes" else fr.paul.telecommande.shared.RulesEngine.fmt(left) to "restantes" }
            else -> Usage.format(used) to "aujourd'hui"
        }
        h.addView(Premium.bigNumber(c, big, unit, if (big.length > 4) 52f else 72f), Ui.lp(c, 4))
        if (limit > 0) h.addView(Premium.heroProgress(c, used.toFloat() / (limit * 60_000L)), LinearLayout.LayoutParams(-1, Ui.dp(c, 6)).apply { topMargin = Ui.dp(c, 16) })
        val eff = rules.forProfile(p)
        val cap = listOfNotNull(
            if (limit > 0) "${Usage.format(used)} regardées sur ${Usage.format(limit * 60_000L)}" else if (!p.unlimited) "${Usage.format(used)} regardées aujourd'hui" else null,
            if (eff.enabled && eff.bedtimeEnabled) "TV éteinte à " + fr.paul.telecommande.shared.ContractWizard.clock(eff.bedtimeStart) else null
        ).joinToString(" · ")
        if (cap.isNotBlank()) h.addView(Premium.heroText(c, cap, 12.5f, soft = true), Ui.lp(c, 8))
        val btns = LinearLayout(c)
        btns.addView(Premium.heroButton(c, "+ 15 min", true) { quickBonus(15) }, LinearLayout.LayoutParams(0, Ui.dp(c, 52), 1.3f))
        btns.addView(Premium.heroButton(c, "Message", false) { BoxActions.message(c, device) }, LinearLayout.LayoutParams(0, Ui.dp(c, 52), 1f).apply { marginStart = Ui.dp(c, 8) })
        btns.addView(Premium.heroButton(c, "Plus", false) { moreActions(rules) }, LinearLayout.LayoutParams(0, Ui.dp(c, 52), 1f).apply { marginStart = Ui.dp(c, 8) })
        h.addView(btns, Ui.lp(c, 20))
        return h
    }

    /** Titre de partie, en phrase simple. */
    private fun heading(text: String) = Ui.title(this, text, 19f)

    /** Corps de l'onglet « Enfants » : ses règles, ses bonus, ce qu'il a regardé, un conseil, puis « Plus ». */
    private fun renderFamily(rules: fr.paul.telecommande.shared.BoxRules) {
        val c = this
        val p = activityProfile(rules)
        val profiles = rules.profiles

        // ----- 1. Les règles de l'enfant -----
        if (p != null) {
            root.addView(heading(if (p.unlimited) "Profil ${p.name}" else "Les règles de ${p.name}"), Ui.lp(c, 22))
            when {
                p.unlimited -> {
                    val card = Ui.card(c)
                    card.addView(Ui.text(c, "Aucune limite pour ce profil. Il est protégé par le code parent : un enfant ne peut pas le choisir sur la TV.", 15f).apply { setLineSpacing(0f, 1.2f) })
                    card.addView(Ui.button(c, "Modifier le profil") { openRules(profiles.indexOf(p)) }, Ui.lp(c, 14))
                    root.addView(card, Ui.lp(c, 10))
                }
                p.contract != null -> contractCards(rules, p).forEach { root.addView(it, Ui.lp(c, 10)) }
                else -> root.addView(simpleRulesCard(rules, p), Ui.lp(c, 10))
            }
            if (p.contract?.paused == false && rules.enabled) root.addView(bonusCard(p), Ui.lp(c, 12))
        } else if (!rules.enabled) {
            val card = Ui.card(c)
            card.addView(Ui.text(c, "", 15.5f).apply { text = sentence(rules, null); setLineSpacing(0f, 1.2f) })
            card.addView(Ui.button(c, "Activer pas à pas", primary = true) {
                startActivity(Intent(c, RulesWizardActivity::class.java).putExtra(RulesWizardActivity.EXTRA_ID, device.id))
            }, Ui.lp(c, 14))
            root.addView(card, Ui.lp(c, 18))
        }

        // ----- 2. Ce qui a été regardé -----
        root.addView(heading(if (p != null) "Ce que ${p.name} a regardé" else "Ce qui a été regardé"), Ui.lp(c, 26))
        val act = Ui.card(c)
        act.addView(Ui.tabs(c, listOf("Aujourd'hui", "Semaine", "Horaires"), tab) { tab = it; render() })
        when (tab) {
            0 -> todayTab(act, rules)
            1 -> weekTab(act, rules)
            else -> hoursTab(act)
        }
        root.addView(act, Ui.lp(c, 10))

        // ----- 3. Un conseil de l'assistant (le plus important seulement) -----
        val names = status?.optJSONObject("labels")
        val advice = fr.paul.telecommande.shared.RulesAdvisor.check(rules, { pkg -> names?.optString(pkg)?.ifBlank { null } ?: AppCatalog.name(pkg) })
            .filter { it.id !in Premium.dismissed(c, device.id) }
        if (rules.enabled && advice.isNotEmpty()) {
            root.addView(heading("Un conseil"), Ui.lp(c, 26))
            root.addView(Premium.adviceCard(c, advice.first(), onFix = { f -> applyFix(f) }, onDismiss = { Premium.dismiss(c, device.id, advice.first().id); render() }), Ui.lp(c, 10))
        }

        // ----- 4. Plus : tout le reste, rangé dans une seule liste -----
        root.addView(heading("Plus"), Ui.lp(c, 26))
        val more = Ui.group(c)
        Ui.addRow(more, Ui.row(c, "➕", "Ajouter un enfant", "Un profil par enfant, avec son âge") { openRules(-1) })
        Ui.addRow(more, Ui.row(c, "📋", "Toutes les règles de la TV", "Applis, horaires, programmes, couvre-feu") { openRules(-2) })
        Ui.addRow(more, Ui.row(c, "🧭", "Tout régler pas à pas", "Une question par écran") {
            startActivity(Intent(c, RulesWizardActivity::class.java).putExtra(RulesWizardActivity.EXTRA_ID, device.id))
        })
        Ui.addRow(more, Ui.row(c, "🩺", "Santé de la TV", "Mémoire, stockage, connexion") { BoxActions.diagnostics(c, device) })
        val since = week?.optLong("since")?.takeIf { it > 0 }
        Ui.addRow(more, Ui.row(c, "🔒", "Vos données restent chez vous",
            "Sur la box et ce téléphone uniquement, échangées en chiffré. Aucun compte, aucun serveur." +
                (if (since != null) "\nMesuré depuis le " + DateFormat.getDateInstance(DateFormat.LONG, Locale.FRANCE).format(Date(since)) + "." else "")).apply {
            (getChildAt(1) as? LinearLayout)?.getChildAt(1)?.let { (it as? android.widget.TextView)?.maxLines = 4 }
        })
        Ui.addRow(more, Ui.row(c, "⛓️", "Retirer l'appairage", "La TV n'appliquera plus vos règles à jour", danger = true) {
            Ui.requirePin(c, "Retirer l'appairage ? La TV n'appliquera plus vos règles à jour.") {
                lifecycleScope.launch {
                    runCatching { BoxClient.of(c, device).call("unpair") }
                    device.boxFp = ""; DeviceStore(c).save(device); BoxLink.sync(c); finish()
                }
            }
        })
        root.addView(more, Ui.lp(c, 10))
    }

    /** Enfant sans contrat : sa règle en une phrase, le temps par jour réglable d'un geste, et l'invitation au contrat. */
    private fun simpleRulesCard(rules: fr.paul.telecommande.shared.BoxRules, p: fr.paul.telecommande.shared.Profile): android.view.View {
        val c = this
        val card = Ui.card(c)
        card.addView(Ui.text(c, "", 16f).apply { text = sentence(rules, p); setLineSpacing(0f, 1.2f) })
        if (rules.enabled) {
            val effective = if (p.dailyTotalMin > 0) p.dailyTotalMin else rules.dailyTotalMin
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
                setOnClickListener { changeDaily(p.id, delta) }
                Motion.pressable(this, 0.88f)
            }
            step.addView(stepBtn("−", "Réduire de 15 minutes", false, -15), LinearLayout.LayoutParams(Ui.dp(c, 50), Ui.dp(c, 50)))
            val mid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            mid.addView(Ui.title(c, if (effective > 0) Usage.format(effective * 60_000L) else "Sans limite", 22f).apply { gravity = Gravity.CENTER })
            mid.addView(Ui.text(c, "de TV par jour", 12f, muted = true).apply { gravity = Gravity.CENTER })
            step.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
            step.addView(stepBtn("+", "Ajouter 15 minutes", true, 15), LinearLayout.LayoutParams(Ui.dp(c, 50), Ui.dp(c, 50)))
            card.addView(step, Ui.lp(c, 16))
        }
        card.addView(Ui.divider(c), LinearLayout.LayoutParams(-1, 1).apply { topMargin = Ui.dp(c, 18) })
        card.addView(Ui.text(c, "Envie de règles plus complètes ? Le contrat se règle en 5 phrases : semaine, week-end, extinction le soir, bonus.", 13.5f, muted = true), Ui.lp(c, 14))
        val links = LinearLayout(c)
        links.addView(Ui.button(c, "Créer son contrat", primary = true) { openContract(p, false) }.compact(),
            LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1.2f).apply { marginEnd = Ui.dp(c, 5) })
        links.addView(Ui.button(c, "Modifier") { openRules(rules.profiles.indexOf(p)) }.compact(),
            LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1f).apply { marginStart = Ui.dp(c, 5) })
        card.addView(links, Ui.lp(c, 12))
        return card
    }

    /** Une ligne sous le grand chiffre : temps restant, dépassement ou « sans limite ». */
    private fun todayCaption(rules: fr.paul.telecommande.shared.BoxRules, p: fr.paul.telecommande.shared.Profile?, todayMs: Long, limit: Int, usage: JSONObject?): String {
        if (!rules.enabled) return "Contrôle parental désactivé : aucune limite"
        if (p?.unlimited == true) return "Profil parent : aucune limite"
        if (p?.contract?.paused == true) return "Contrat en pause : aucune limite pour ${p.name}"
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
                    selected = p.id; week = null; today = null; render()
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

    /** Enfant affiché (null = automatique : l'enfant qui regarde, sinon le premier enfant). */
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
                r.profiles[i] = p.copy(dailyTotalMin = (base + delta).coerceIn(15, 24 * 60),
                    weekendMin = if (p.weekendMin > 0) (p.weekendMin + delta).coerceIn(15, 24 * 60) else 0)
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
        val limited = rules.enabled && rules.forProfile(p).enabled
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

    // ---------- Contrat parental ----------

    /** Modifie un profil (code parent), l'enregistre et l'envoie à la TV. */
    private fun editProfile(profileId: String, reason: String, block: (fr.paul.telecommande.shared.BoxRules, fr.paul.telecommande.shared.Profile) -> Unit) {
        val apply = {
            unlocked = true
            val store = ParentalStore(this)
            val r = store.boxRules(device.id)
            r.profiles.firstOrNull { it.id == profileId }?.let { p ->
                block(r, p)
                r.pinHash = store.boxPinHash
                r.version = System.currentTimeMillis()
                store.saveBoxRules(device.id, r)
                render()
                lifecycleScope.launch {
                    runCatching { BoxClient.of(this@BoxDashboardActivity, device).call("set_rules", JSONObject().put("rules", r.toJson())) }
                        .onFailure { Ui.toast(this@BoxDashboardActivity, "TV injoignable : le contrat sera envoyé à sa prochaine connexion") }
                }
            }
            Unit
        }
        if (unlocked) apply() else Ui.requirePin(this, reason) { apply() }
    }

    private fun openContract(p: fr.paul.telecommande.shared.Profile, mixer: Boolean) {
        startActivity(Intent(this, ContractActivity::class.java).putExtra(ContractActivity.EXTRA_ID, device.id)
            .putExtra(ContractActivity.EXTRA_PROFILE, p.id).apply { if (mixer) putExtra(ContractActivity.EXTRA_START, ContractActivity.START_MIXER) })
    }

    /** Cartes « Contrat » de l'écran Parental : proposer d'en créer un, ou l'état du contrat, son bilan et ses gestes. */
    private fun contractCards(rules: fr.paul.telecommande.shared.BoxRules, p: fr.paul.telecommande.shared.Profile): List<android.view.View> {
        val c = this
        val k = p.contract
        if (k == null) {
            val card = Ui.card(c)
            card.addView(Ui.text(c, "Fixez les règles de ${p.name} à votre façon", 16f).apply { typeface = Ui.bold })
            card.addView(Ui.text(c, "5 phrases à compléter, puis tout se règle d'un geste à la table de mixage. Vous pourrez le mettre en pause, le modifier ou l'arrêter à tout moment.", 13.5f, muted = true), Ui.lp(c, 6))
            card.addView(Ui.button(c, "Créer le contrat de ${p.name}", primary = true) { openContract(p, false) }, Ui.lp(c, 14))
            return listOf(card)
        }
        val out = mutableListOf<android.view.View>()
        reviewCard(p)?.let { out += it }
        val card = Ui.card(c)
        val days = ((System.currentTimeMillis() - k.since) / fr.paul.telecommande.shared.ContractWizard.DAY_MS).toInt()
        card.addView(Ui.pill(c, if (k.paused) "En pause : aucune limite pour ${p.name}" else if (days < 1) "Actif depuis aujourd'hui" else "Actif depuis $days jour" + if (days > 1) "s" else "",
            Ui.color(c, if (k.paused) R.color.amber else R.color.ok)), LinearLayout.LayoutParams(-2, -2))
        card.addView(Ui.text(c, fr.paul.telecommande.shared.ContractWizard.sentence(p.name, fr.paul.telecommande.shared.ContractWizard.mixOf(rules, p)), 15f).apply { setLineSpacing(0f, 1.2f) }, Ui.lp(c, 12))
        if (k.showChild) card.addView(Ui.text(c, "Montré à ${p.name} sur la TV.", 12.5f, muted = true), Ui.lp(c, 6))
        val btns = LinearLayout(c)
        btns.addView(Ui.button(c, "Modifier", primary = true) { openContract(p, true) }, LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1f).apply { marginEnd = Ui.dp(c, 4) })
        btns.addView(Ui.button(c, if (k.paused) "Reprendre" else "Pause") {
            editProfile(p.id, if (k.paused) "Code parent pour reprendre le contrat." else "Code parent pour mettre le contrat en pause.") { _, q ->
                q.contract?.status = if (k.paused) fr.paul.telecommande.shared.Contract.ACTIVE else fr.paul.telecommande.shared.Contract.PAUSED
            }
        }, LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1f).apply { marginStart = Ui.dp(c, 4); marginEnd = Ui.dp(c, 4) })
        btns.addView(Ui.button(c, "Arrêter") {
            AlertDialog.Builder(c).setTitle("Arrêter le contrat de ${p.name} ?")
                .setMessage("${p.name} garde sa limite actuelle en semaine, en mode simple. Les bonus, les relectures et l'extinction du soir propres au contrat s'arrêtent.")
                .setPositiveButton("Arrêter") { _, _ -> editProfile(p.id, "Code parent pour arrêter le contrat.") { _, q -> fr.paul.telecommande.shared.ContractWizard.stop(q) } }
                .setNegativeButton("Annuler", null).show()
        }, LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1f).apply { marginStart = Ui.dp(c, 4) })
        card.addView(btns, Ui.lp(c, 14))
        out += card
        return out
    }

    /** Bilan proposé au parent quand c'est le moment (relecture prévue ou bonus répétés), ou null. */
    private fun reviewCard(p: fr.paul.telecommande.shared.Profile): android.view.View? {
        val c = this
        val rv = fr.paul.telecommande.shared.ContractWizard.review(p.name, p.contract, System.currentTimeMillis()) ?: return null
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Ui.color(c, R.color.indigo_soft)); cornerRadius = Ui.dp(c, 24).toFloat() }
            setPadding(Ui.dp(c, 18), Ui.dp(c, 16), Ui.dp(c, 18), Ui.dp(c, 16))
        }
        card.addView(Ui.label(c, rv.title))
        card.addView(Ui.text(c, rv.text, 16f).apply { typeface = Ui.bold; setTextColor(Ui.color(c, R.color.indigo_ink)); setLineSpacing(0f, 1.2f) }, Ui.lp(c, 6))
        card.addView(Ui.text(c, "Ce n'est qu'une proposition : vous pouvez donner plus de liberté, récompenser autrement (une sortie, choisir le film du samedi…), resserrer, ou tout garder.", 13f, muted = true), Ui.lp(c, 6))
        val btns = LinearLayout(c)
        // Revoir : la table de mixage ; valider le contrat repousse le prochain bilan
        btns.addView(Ui.button(c, "Revoir le contrat", primary = true) { openContract(p, true) }, LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1.3f).apply { marginEnd = Ui.dp(c, 4) })
        btns.addView(Ui.button(c, "Garder tel quel") {
            editProfile(p.id, "Code parent pour garder le contrat.") { _, q -> q.contract?.let { fr.paul.telecommande.shared.ContractWizard.postpone(it, System.currentTimeMillis()) } }
        }, LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1f).apply { marginStart = Ui.dp(c, 4) })
        card.addView(btns, Ui.lp(c, 14))
        return card
    }

    /** Motif choisi pour le prochain bonus (écran Activité). */
    private var bonusReason = fr.paul.telecommande.shared.ContractWizard.REASONS.first()

    /** « Accorder un bonus » : un motif, puis le bonus prévu par le contrat ; gardé pour les bilans. */
    private fun bonusCard(p: fr.paul.telecommande.shared.Profile): android.view.View {
        val c = this
        val k = p.contract!!
        val card = Ui.card(c)
        card.addView(Ui.label(c, "Accorder un bonus à ${p.name}"))
        val reasons = (fr.paul.telecommande.shared.ContractWizard.REASONS + listOf(bonusReason)).distinct()
        val wrap = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        var line = LinearLayout(c)
        reasons.forEachIndexed { i, r ->
            if (i % 2 == 0 && i > 0) { wrap.addView(line, Ui.lp(c, 8)); line = LinearLayout(c) }
            line.addView(reasonChip(r, r == bonusReason) { bonusReason = r; render() }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i % 2 == 0) marginEnd = Ui.dp(c, 4) else marginStart = Ui.dp(c, 4) })
        }
        if (reasons.size % 2 == 1) line.addView(reasonChip("Autre motif…", false) { askReason() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(c, 4) })
        wrap.addView(line, Ui.lp(c, 8))
        if (reasons.size % 2 == 0) wrap.addView(reasonChip("Autre motif…", false) { askReason() }, Ui.lp(c, 8))
        card.addView(wrap, Ui.lp(c, 4))
        val minutes = k.bonusMinutes()
        if (minutes > 0) {
            card.addView(Ui.button(c, "+ $minutes min pour « $bonusReason »", primary = true) { grantBonus(p, minutes, bonusReason) }, Ui.lp(c, 14))
        } else {
            card.addView(Ui.text(c, "Votre contrat ne prévoit pas de bonus de temps.", 13.5f, muted = true), Ui.lp(c, 12))
            card.addView(Ui.button(c, "Accorder quand même 15 min") { grantBonus(p, 15, bonusReason) }, Ui.lp(c, 8))
        }
        card.addView(Ui.text(c, "Le bonus s'ajoute au temps de TV du jour. Pensez aussi aux récompenses sans écran : une sortie, choisir le film du samedi…", 12.5f, muted = true), Ui.lp(c, 10))
        return card
    }

    private fun reasonChip(label: String, on: Boolean, action: () -> Unit) = android.widget.TextView(this).apply {
        val c = this@BoxDashboardActivity
        text = label; textSize = 14f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
        typeface = if (on) Ui.bold else Ui.medium
        setTextColor(Ui.color(c, if (on) R.color.indigo_ink else R.color.ink))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Ui.dp(c, 16).toFloat(); setColor(Ui.color(c, if (on) R.color.indigo_soft else R.color.tile))
            if (on) setStroke(Ui.dp(c, 2), Ui.color(c, R.color.accent))
        }
        minHeight = Ui.dp(c, 46); setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0)
        contentDescription = label + if (on) ", choisi" else ""
        isClickable = true; isFocusable = true; setOnClickListener { action() }
    }

    private fun askReason() {
        val input = android.widget.EditText(this).apply { hint = "Ex. : bonne note, rangé sa chambre"; maxLines = 1; filters = arrayOf(android.text.InputFilter.LengthFilter(40)) }
        val box = LinearLayout(this).apply { val p = Ui.dp(this@BoxDashboardActivity, 20); setPadding(p, Ui.dp(this@BoxDashboardActivity, 8), p, 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(this).setTitle("Motif du bonus").setView(box)
            .setPositiveButton("OK") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { bonusReason = it.replaceFirstChar { ch -> ch.uppercase() }; render() } }
            .setNegativeButton("Annuler", null).show()
    }

    private fun grantBonus(p: fr.paul.telecommande.shared.Profile, minutes: Int, reason: String) {
        if (!unlocked) { Ui.requirePin(this, "Code parent pour accorder un bonus.") { unlocked = true; grantBonus(p, minutes, reason) }; return }
        lifecycleScope.launch {
            runCatching { BoxClient.of(this@BoxDashboardActivity, device).call("bonus", JSONObject().put("pkg", "*").put("minutes", minutes)) }
                .onSuccess {
                    Ui.toast(this@BoxDashboardActivity, "+$minutes min pour ${p.name} : « $reason »")
                    // Gardé dans le contrat pour les bilans (« 6 bonus devoirs en 2 mois »)
                    editProfile(p.id, "") { _, q -> q.contract?.bonuses?.add(fr.paul.telecommande.shared.ContractBonus(System.currentTimeMillis(), minutes, reason)) }
                }
                .onFailure { Ui.toast(this@BoxDashboardActivity, "TV injoignable : réessayez dans un instant") }
            load()
        }
    }
}
