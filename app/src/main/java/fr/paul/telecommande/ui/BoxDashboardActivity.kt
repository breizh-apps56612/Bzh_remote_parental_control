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
    private lateinit var root: LinearLayout
    private var status: JSONObject? = null
    private var week: JSONObject? = null
    private var error: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        device = intent.getStringExtra(EXTRA_ID)?.let { DeviceStore(this).get(it) } ?: run { finish(); return }
        Ui.systemBars(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PARENTAL
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(this@BoxDashboardActivity, 16); setPadding(p, p + Ui.dp(this@BoxDashboardActivity, 8), p, 0) }
        setContentView(AuroraNav.wrap(this, ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(root) },
            if (mode == MODE_ACTIVITY) AuroraNav.Tab.ACTIVITY else AuroraNav.Tab.PARENTAL))
        render()
        lifecycleScope.launch { while (isActive) { load(); delay(30_000) } }
    }

    private suspend fun load() {
        val c = BoxClient.of(this, device)
        val now = System.currentTimeMillis()
        val from = Usage.addDays(Usage.startOfDay(now), -6)
        runCatching {
            status = c.call("status")
            week = c.call("usage", JSONObject().put("from", from).put("to", now))
            error = null
            if (fr.paul.telecommande.core.AppIcons.stale(this, device.id)) fr.paul.telecommande.core.AppIcons.refresh(this, device)
            logProgramReport(c)
        }.onFailure { error = it.message }
        render()
    }

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
        root.removeAllViews()
        val rules = ParentalStore(c).boxRules(device.id)
        val st = status
        if (st != null && error == null) lastOk = System.currentTimeMillis()

        // ===== En-tête : TV, titre, état en direct =====
        val (stateText, dot) = when {
            error != null -> "TV injoignable" to Ui.color(c, R.color.danger)
            st == null -> "Connexion…" to Ui.color(c, R.color.muted)
            !st.optBoolean("interactive") -> "TV en veille" to Ui.color(c, R.color.muted)
            else -> "Allumée · " + st.optString("label").ifBlank { "accueil" } to Ui.color(c, R.color.ok)
        }
        root.addView(Ui.text(c, device.name + (if (mode == MODE_PARENTAL && rules.enabled) " · protégé par code" else ""), 13f, muted = true))
        root.addView(Ui.title(c, if (mode == MODE_ACTIVITY) "Activité" else "Contrôle parental", 28f), Ui.lp(c, 4))
        val head = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.pill(c, stateText, dot), LinearLayout.LayoutParams(-2, -2))
        if (lastOk > 0) head.addView(Ui.text(c, "  à " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(lastOk)), 12f, muted = true))
        root.addView(head, Ui.lp(c, 12))
        if (error != null) root.addView(Ui.text(c, "Vérifiez que la TV est allumée et sur le même Wi-Fi. Nouvel essai toutes les 30 s.", 12.5f, muted = true), Ui.lp(c, 6))

        // ===== Demandes en attente (en premier : c'est ce qui attend une réponse) =====
        val pending = st?.optJSONArray("pending")
        if (pending != null && pending.length() > 0) {
            for (i in 0 until pending.length()) root.addView(requestCard(pending.getJSONObject(i)), Ui.lp(c, 14))
        }

        if (mode == MODE_ACTIVITY) renderActivity(rules) else renderParental(rules)
        root.addView(AuroraNav.spacer(c))
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
        // ----- La règle en une phrase + réglage rapide -----
        val sum = Ui.card(c)
        sum.addView(Ui.text(c, "", 16.5f).apply { text = sentence(rules); setLineSpacing(0f, 1.2f) })
        if (rules.enabled) {
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
                setOnClickListener { changeDaily(delta) }
            }
            step.addView(stepBtn("−", "Réduire de 15 minutes", false, -15), LinearLayout.LayoutParams(Ui.dp(c, 50), Ui.dp(c, 50)))
            val mid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            mid.addView(Ui.title(c, if (rules.dailyTotalMin > 0) Usage.format(rules.dailyTotalMin * 60_000L) else "Sans limite", 22f).apply { gravity = Gravity.CENTER })
            mid.addView(Ui.text(c, "de TV par jour", 12f, muted = true).apply { gravity = Gravity.CENTER })
            step.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
            step.addView(stepBtn("+", "Ajouter 15 minutes", true, 15), LinearLayout.LayoutParams(Ui.dp(c, 50), Ui.dp(c, 50)))
            sum.addView(step, Ui.lp(c, 16))
        }
        val links = LinearLayout(c)
        links.addView(Ui.button(c, if (rules.enabled) "Toutes les règles" else "Activer et régler", primary = !rules.enabled) {
            startActivity(Intent(c, BoxRulesActivity::class.java).putExtra(BoxRulesActivity.EXTRA_ID, device.id))
        }.compact(), LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1f).apply { marginEnd = Ui.dp(c, 5) })
        links.addView(Ui.button(c, "Pas à pas") {
            startActivity(Intent(c, RulesWizardActivity::class.java).putExtra(RulesWizardActivity.EXTRA_ID, device.id))
        }.compact(), LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1f).apply { marginStart = Ui.dp(c, 5) })
        sum.addView(links, Ui.lp(c, 14))
        root.addView(sum, Ui.lp(c, 14))

        // ----- Actions rapides : 4 boutons ronds -----
        val acts = LinearLayout(c)
        fun act(icon: Int, label: String, primary: Boolean, action: () -> Unit) = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true; isFocusable = true; contentDescription = label
            setOnClickListener { action() }
            addView(ImageView(c).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(if (primary) android.graphics.Color.WHITE else Ui.color(c, R.color.ink))
                if (primary) setBackgroundResource(R.drawable.bg_fab)
                else background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Ui.color(c, R.color.surface)); setStroke(1, Ui.color(c, R.color.line))
                }
                elevation = Ui.dp(c, if (primary) 6 else 2).toFloat()
                val p = Ui.dp(c, 16); setPadding(p, p, p, p)
                importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(Ui.dp(c, 58), Ui.dp(c, 58)))
            addView(Ui.text(c, label, 12.5f).apply { gravity = Gravity.CENTER; maxLines = 1; typeface = Ui.medium }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(c, 8) })
        }
        acts.addView(act(R.drawable.ic_clock, "+ Temps", true) { addTime() }, LinearLayout.LayoutParams(0, -2, 1f))
        acts.addView(act(R.drawable.ic_message, "Message", false) { BoxActions.message(c, device) }, LinearLayout.LayoutParams(0, -2, 1f))
        acts.addView(act(R.drawable.ic_ok, "Récompense", false) { BoxActions.reward(c, device) }, LinearLayout.LayoutParams(0, -2, 1f))
        acts.addView(act(R.drawable.ic_person, "Qui regarde", false) { BoxActions.pickProfile(c, device, rules.profiles) { lifecycleScope.launch { load() } } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(acts, Ui.lp(c, 20))

        // ----- Applis : temps du jour et limite -----
        val head = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.title(c, "Applis", 18f), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Ui.text(c, "Tout voir", 14f).apply {
            setTextColor(Ui.color(c, R.color.accent)); typeface = Ui.medium; setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10))
            isClickable = true; isFocusable = true
            setOnClickListener { startActivity(Intent(c, BoxRulesActivity::class.java).putExtra(BoxRulesActivity.EXTRA_ID, device.id)) }
        })
        root.addView(head, Ui.lp(c, 24))
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

    /** La règle du jour, en français courant, chiffres en couleur. */
    private fun sentence(r: fr.paul.telecommande.shared.BoxRules): CharSequence {
        val b = android.text.SpannableStringBuilder()
        fun strong(t: String) {
            val s = b.length; b.append(t)
            b.setSpan(android.text.style.ForegroundColorSpan(Ui.color(this, R.color.accent)), s, b.length, 0)
            b.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s, b.length, 0)
        }
        fun hm(m: Int) = if (m % 60 == 0) "${m / 60} h" else "${m / 60} h %02d".format(m % 60)
        if (!r.enabled) { b.append("Le contrôle parental est "); strong("en pause"); b.append(" : la TV n'a aucune limite."); return b }
        b.append("La TV peut être regardée ")
        if (r.dailyTotalMin > 0) { strong(Usage.format(r.dailyTotalMin * 60_000L) + " par jour") } else strong("sans limite de temps")
        if (r.activeFrom != r.activeTo) { b.append(", entre "); strong(hm(r.activeFrom)); b.append(" et "); strong(hm(r.activeTo)) }
        b.append(".")
        if (r.bedtimeEnabled) { b.append(" Couvre-feu à "); strong(hm(r.bedtimeStart)); b.append(".") }
        val blocked = r.apps.values.count { it.blocked }
        if (blocked > 0) { b.append(" "); strong("$blocked appli" + if (blocked > 1) "s bloquées" else " bloquée"); b.append(".") }
        return b
    }

    /** Réglage rapide du temps par jour (pas de 15 min), protégé par le code parent, envoyé tout de suite à la TV. */
    private fun changeDaily(delta: Int) {
        val apply = {
            unlocked = true
            val store = ParentalStore(this)
            val r = store.boxRules(device.id)
            r.dailyTotalMin = (r.dailyTotalMin + delta).coerceIn(0, 24 * 60)
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

    /** « Activité » : anneau du jour, qui regarde, ce qui passe, puis le détail en onglets. */
    private fun renderActivity(rules: fr.paul.telecommande.shared.BoxRules) {
        val c = this
        val st = status
        val todayMs = st?.optLong("todayTotal") ?: 0L
        val limitMin = if (rules.enabled) rules.dailyTotalMin else 0
        val hero = Ui.card(c)
        val top = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(RingView(c).apply {
            ratio = if (limitMin > 0) todayMs.toFloat() / (limitMin * 60_000L) else -1f
            center = Usage.format(todayMs)
            caption = if (limitMin > 0) "sur " + Usage.format(limitMin * 60_000L) else "aujourd'hui"
            contentDescription = "Aujourd'hui : $center" + if (limitMin > 0) " sur $caption" else ""
        })
        val side = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 16), 0, 0, 0) }
        val who = st?.optJSONObject("profile")
        side.addView(Ui.label(c, "Qui regarde"))
        side.addView(Ui.text(c, when {
            who != null -> "${who.optString("emoji")} ${who.optString("name")}"
            rules.profiles.isEmpty() -> "—"
            else -> "Personne"
        }, 19f).apply { typeface = Ui.bold; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, Ui.lp(c, 2))
        val (remain, over) = when {
            !rules.enabled -> "Contrôle parental désactivé" to false
            limitMin <= 0 -> "Pas de limite par jour" to false
            todayMs >= limitMin * 60_000L -> "Limite dépassée de " + Usage.format(todayMs - limitMin * 60_000L) to true
            else -> "Reste " + Usage.format(limitMin * 60_000L - todayMs) to false
        }
        side.addView(Ui.text(c, remain, 13.5f, muted = !over).apply { if (over) setTextColor(Ui.color(c, R.color.danger)) }, Ui.lp(c, 10))
        top.addView(side, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(top)

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
        val todayMap = st?.optJSONObject("today"); val appLabels = st?.optJSONObject("labels")
        val entries = todayMap?.keys()?.asSequence()?.map { it to todayMap.optLong(it) }?.filter { it.second > 0 }?.sortedByDescending { it.second }?.toList() ?: emptyList()
        if (entries.isEmpty()) { card.addView(Ui.text(c, if (st == null) loadingText() else "Rien de regardé aujourd'hui.", 14f, muted = true), Ui.lp(c, 16)); return }
        val max = entries.maxOf { it.second }
        val shown = if (showAllApps) entries else entries.take(4)
        shown.forEach { (pkg, ms) ->
            val limit = if (rules.enabled) rules.apps[pkg]?.limitMin ?: 0 else 0
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
        card.addView(BarChartView(c).apply {
            heightDp = 120
            labels = days.map { fmt.format(Date(it)).trimEnd('.').replaceFirstChar { ch -> ch.uppercase() } }
            limitMin = if (rules.enabled) rules.dailyTotalMin else 0
            valuesMin = values.map { (it / 60_000).toInt() }
        }, Ui.lp(c, 14))

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
        lifecycleScope.launch {
            BoxLink.respond(this@BoxDashboardActivity, device, r.optString("id"), accept, r.optInt("minutes", 15))
                .onFailure { Ui.toast(this@BoxDashboardActivity, "Erreur : ${it.message}") }
            load()
        }
    }

    private fun addTime() {
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
