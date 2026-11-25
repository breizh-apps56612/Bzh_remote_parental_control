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
    companion object { const val EXTRA_ID = "device_id" }

    private lateinit var device: Device
    private lateinit var root: LinearLayout
    private var status: JSONObject? = null
    private var week: JSONObject? = null
    private var error: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        device = intent.getStringExtra(EXTRA_ID)?.let { DeviceStore(this).get(it) } ?: run { finish(); return }
        Ui.systemBars(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(this@BoxDashboardActivity, 16); setPadding(p, p, p, p * 2) }
        setContentView(ScrollView(this).apply { setBackgroundResource(R.drawable.bg_screen); addView(root) })
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
        }.onFailure { error = it.message }
        render()
    }

    private var tab = 0          // 0 = aujourd'hui, 1 = semaine, 2 = horaires
    private var showAllApps = false
    private var lastOk = 0L

    private fun row2(c: android.content.Context, left: android.view.View, right: android.view.View) = LinearLayout(c).apply {
        addView(left, LinearLayout.LayoutParams(0, Ui.dp(c, 56), 1f).apply { marginEnd = Ui.dp(c, 5) })
        addView(right, LinearLayout.LayoutParams(0, Ui.dp(c, 56), 1f).apply { marginStart = Ui.dp(c, 5) })
    }

    private fun section(text: String) = Ui.label(this, text).apply { setPadding(Ui.dp(this@BoxDashboardActivity, 6), 0, 0, 0) }

    private fun render() {
        val c = this
        root.removeAllViews()
        val rules = ParentalStore(c).boxRules(device.id)
        val st = status
        if (st != null && error == null) lastOk = System.currentTimeMillis()

        // ===== En-tête : nom, titre, état en direct =====
        root.addView(Ui.text(c, device.name, 13f, muted = true))
        root.addView(Ui.title(c, "Tableau de bord", 28f), Ui.lp(c, 2))
        val (stateText, dot) = when {
            error != null -> "TV injoignable" to Ui.color(c, R.color.danger)
            st == null -> "Connexion…" to Ui.color(c, R.color.muted)
            !st.optBoolean("interactive") -> "TV en veille" to Ui.color(c, R.color.muted)
            else -> "Allumée · " + st.optString("label").ifBlank { "accueil" } to Ui.color(c, R.color.ok)
        }
        val head = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.pill(c, stateText, dot), LinearLayout.LayoutParams(-2, -2))
        if (lastOk > 0) head.addView(Ui.text(c, "  à " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(lastOk)), 12f, muted = true))
        root.addView(head, Ui.lp(c, 10))
        if (error != null) root.addView(Ui.text(c, "Vérifiez que la TV est allumée et sur le même Wi-Fi. Nouvel essai toutes les 30 s.", 12.5f, muted = true), Ui.lp(c, 6))

        // ===== Demandes en attente (en premier : c'est ce qui attend une réponse) =====
        val pending = st?.optJSONArray("pending")
        if (pending != null && pending.length() > 0) {
            val card = Ui.card(c)
            card.addView(Ui.label(c, if (pending.length() > 1) "${pending.length()} demandes en attente" else "Demande en attente"))
            for (i in 0 until pending.length()) {
                val r = pending.getJSONObject(i)
                card.addView(Ui.text(c, "🙋 ${r.optString("label")} · +${r.optInt("minutes")} min", 16f).apply { typeface = Ui.medium }, Ui.lp(c, 10))
                card.addView(row2(c,
                    Ui.tile(c, "✓", "Accepter", primary = true) { respond(r, true) },
                    Ui.tile(c, "✕", "Refuser") { respond(r, false) }), Ui.lp(c, 8))
            }
            root.addView(card, Ui.lp(c, 14))
        }

        // ===== Carte principale : anneau du jour + qui regarde + ce qui passe =====
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

        // ===== Actions rapides : 2 × 2, libellés courts sur une ligne =====
        root.addView(row2(c,
            Ui.tile(c, "📣", "Message", primary = true) { BoxActions.message(c, device) },
            Ui.tile(c, "⏱️", "Du temps en +") { addTime() }), Ui.lp(c, 12))
        root.addView(row2(c,
            Ui.tile(c, "🎉", "Récompense") { BoxActions.reward(c, device) },
            Ui.tile(c, "👤", "Qui regarde") { BoxActions.pickProfile(c, device, rules.profiles) { lifecycleScope.launch { load() } } }), Ui.lp(c, 10))

        // ===== Activité : un seul bloc, trois onglets (au lieu de trois grandes cartes) =====
        root.addView(section("Activité"), Ui.lp(c, 26))
        val act = Ui.card(c)
        act.addView(Ui.tabs(c, listOf("Aujourd'hui", "Semaine", "Horaires"), tab) { tab = it; render() })
        when (tab) {
            0 -> todayTab(act, rules)
            1 -> weekTab(act, rules)
            else -> hoursTab(act)
        }
        root.addView(act, Ui.lp(c, 8))

        // ===== Réglages =====
        root.addView(section("Réglages"), Ui.lp(c, 26))
        val set = Ui.group(c)
        Ui.addRow(set, Ui.row(c, "✨", "Assistant pas à pas", "Tout régler en quelques questions") {
            startActivity(Intent(c, RulesWizardActivity::class.java).putExtra(RulesWizardActivity.EXTRA_ID, device.id))
        })
        Ui.addRow(set, Ui.row(c, "🛡️", "Règles et limites", rulesSummary(rules)) {
            startActivity(Intent(c, BoxRulesActivity::class.java).putExtra(BoxRulesActivity.EXTRA_ID, device.id))
        })
        Ui.addRow(set, Ui.row(c, "🩺", "Santé de la TV", "Mémoire, stockage, connexion") { BoxActions.diagnostics(c, device) })
        root.addView(set, Ui.lp(c, 8))

        // ===== Confidentialité =====
        root.addView(section("Confidentialité"), Ui.lp(c, 26))
        val priv = Ui.group(c)
        val since = week?.optLong("since")?.takeIf { it > 0 }
        Ui.addRow(priv, Ui.row(c, "🔒", "Vos données restent chez vous",
            "Sur la box et ce téléphone uniquement, échangées en chiffré. Aucun compte, aucun serveur." +
                (if (since != null) "\nMesuré depuis le " + DateFormat.getDateInstance(DateFormat.LONG, Locale.FRANCE).format(Date(since)) + "." else "")).apply {
            (getChildAt(1) as? LinearLayout)?.getChildAt(1)?.let { (it as? android.widget.TextView)?.maxLines = 4 }
        })
        Ui.addRow(priv, Ui.row(c, "⛓️", "Retirer l'appairage", "La TV n'appliquera plus vos règles à jour", danger = true) {
            Ui.requirePin(c, "Retirer l'appairage ? La TV n'appliquera plus vos règles à jour.") {
                lifecycleScope.launch {
                    runCatching { BoxClient.of(c, device).call("unpair") }
                    device.boxFp = ""; DeviceStore(c).save(device); BoxLink.sync(c); finish()
                }
            }
        })
        root.addView(priv, Ui.lp(c, 8))
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

    private fun appIcon(pkg: String) = runCatching { fr.paul.telecommande.core.AppIcons.icon(this, device.id, pkg) }.getOrNull()

    private fun todayTab(card: LinearLayout, rules: fr.paul.telecommande.shared.BoxRules) {
        val c = this
        val st = status
        val todayMap = st?.optJSONObject("today"); val appLabels = st?.optJSONObject("labels")
        val entries = todayMap?.keys()?.asSequence()?.map { it to todayMap.optLong(it) }?.filter { it.second > 0 }?.sortedByDescending { it.second }?.toList() ?: emptyList()
        if (entries.isEmpty()) { card.addView(Ui.text(c, if (st == null) "Chargement…" else "Rien de regardé aujourd'hui.", 14f, muted = true), Ui.lp(c, 16)); return }
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
        val w = week ?: run { card.addView(Ui.text(c, "Chargement…", 14f, muted = true), Ui.lp(c, 16)); return }
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
        val w = week ?: run { card.addView(Ui.text(c, "Chargement…", 14f, muted = true), Ui.lp(c, 16)); return }
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
