package fr.paul.telecommande.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import fr.paul.telecommande.R
import fr.paul.telecommande.core.BoxClient
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.ParentalRules
import fr.paul.telecommande.core.ParentalStore
import fr.paul.telecommande.core.Usage
import fr.paul.telecommande.shared.AppRule
import fr.paul.telecommande.shared.BoxRules
import fr.paul.telecommande.shared.Profile
import fr.paul.telecommande.shared.RulesAdvisor
import fr.paul.telecommande.shared.RulesEngine
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Assistant pas à pas du contrôle parental : une question par écran, avec des choix clairs.
 * Fonctionne avec l'appli Box (règles appliquées par la TV) ou sans (règles appliquées par le téléphone).
 */
class RulesWizardActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ID = "device_id"
        /** Ouvrir directement la vérification (conseils de l'assistant). */
        const val EXTRA_CHECK = "check"
    }

    private enum class Step(val title: String, val intro: String) {
        PROFILES("Qui regarde la TV ?", "Créez un profil par enfant. À l'allumage, la TV demandera qui regarde, et chacun aura son propre temps."),
        WHEN("Quand les règles s'appliquent", "En dehors de ces heures et de ces jours, c'est du temps libre : plus aucune limite."),
        DAILY("Temps de TV par jour", "Le temps total pendant lequel la TV peut être utilisée chaque jour."),
        BEDTIME("Couvre-feu", "Pendant le couvre-feu, la TV est interdite. Il s'applique même pendant le temps libre."),
        APPS("Les applis", "Fixez une limite pour une appli précise, ou bloquez-la. Facultatif."),
        COMFORT("Confort", "Des petites attentions pour que l'arrêt se passe bien."),
        SUMMARY("Tout est prêt ?", "L'assistant a relu vos règles. Corrigez en un geste ce qui ne tient pas, puis activez.")
    }

    private lateinit var device: Device
    private lateinit var store: ParentalStore
    private lateinit var rules: BoxRules
    private var withBox = false
    private lateinit var steps: List<Step>
    private var index = 0
    private var apps: List<Pair<String, String>> = emptyList()

    private lateinit var heroBox: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var nav: LinearLayout
    /** Conseils marqués « Compris » pendant cet assistant. */
    private val seen = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        device = intent.getStringExtra(EXTRA_ID)?.let { DeviceStore(this).get(it) } ?: run { finish(); return }
        Ui.systemBars(this)
        store = ParentalStore(this)
        withBox = device.hasBoxApp
        rules = if (withBox) store.boxRules(device.id).copy(profiles = store.boxRules(device.id).profiles.map { it.copy() }.toMutableList(),
            apps = store.boxRules(device.id).apps.mapValues { it.value.copy() }.toMutableMap())
        else fromPhone(store.rules(device.id))
        steps = Step.values().filter { withBox || (it != Step.PROFILES && it != Step.APPS && it != Step.COMFORT) }
        if (intent.getBooleanExtra(EXTRA_CHECK, false)) index = steps.lastIndex

        val c = this
        heroBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        content = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(c, 18); setPadding(p, Ui.dp(c, 4), p, p) }
        nav = LinearLayout(c).apply {
            setPadding(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 16))
            setBackgroundResource(R.drawable.bg_bar)
        }
        setContentView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_screen)
            addView(ScrollView(c).apply {
                addView(LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; addView(heroBox, LinearLayout.LayoutParams(-1, -2)); addView(content, LinearLayout.LayoutParams(-1, -2)) })
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(nav, LinearLayout.LayoutParams(-1, -2))
        })
        Premium.darkStatusBar(this)
        content.visibility = View.INVISIBLE
        Ui.createPinIfNeeded(this) {
            Ui.requirePin(this, "Code parent pour configurer les règles.") { content.visibility = View.VISIBLE; render() }
        }
        if (withBox) lifecycleScope.launch {
            runCatching {
                val arr = BoxClient.of(c, device).call("apps").optJSONArray("apps")
                apps = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it).let { o -> o.optString("pkg") to o.optString("label") } }
            }
            if (steps[index] == Step.APPS) render()
        }
    }

    // ---------- Conversion règles du téléphone (sans appli Box) ----------

    private fun fromPhone(p: ParentalRules) = BoxRules(
        enabled = p.enabled, dailyTotalMin = p.dailyTotalMin, bedtimeEnabled = p.bedtimeEnabled,
        bedtimeStart = p.bedtimeStart, bedtimeEnd = p.bedtimeEnd,
        apps = p.perAppMin.mapValues { AppRule(limitMin = it.value) }.toMutableMap(),
        activeFrom = p.activeFrom, activeTo = p.activeTo, activeDays = p.activeDays
    )

    private fun toPhone(old: ParentalRules) = old.copy(
        enabled = rules.enabled, dailyTotalMin = rules.dailyTotalMin, bedtimeEnabled = rules.bedtimeEnabled,
        bedtimeStart = rules.bedtimeStart, bedtimeEnd = rules.bedtimeEnd,
        activeFrom = rules.activeFrom, activeTo = rules.activeTo, activeDays = rules.activeDays
    )

    // ---------- Affichage ----------

    private fun render() {
        val c = this
        val step = steps[index]
        content.removeAllViews()
        heroBox.removeAllViews()
        // ===== Bandeau nuit : retour, progression, titre de l'étape en grand =====
        val h = Premium.hero(c)
        val top = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Premium.heroRound(c, R.drawable.ic_back, if (index > 0) "Étape précédente" else "Fermer") { if (index > 0) { index--; render() } else finish() },
            LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)).apply { marginEnd = Ui.dp(c, 12) })
        val bar = LinearLayout(c)
        steps.indices.forEach { i ->
            bar.addView(View(c).apply { background = GradientDrawable().apply { setColor(if (i <= index) Color.WHITE else 0x38FFFFFF); cornerRadius = 99f } },
                LinearLayout.LayoutParams(0, Ui.dp(c, 4), 1f).apply { if (i > 0) marginStart = Ui.dp(c, 5) })
        }
        top.addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Premium.heroText(c, "${index + 1}/${steps.size}", 13f, bold = true).apply { setPadding(Ui.dp(c, 12), 0, 0, 0) })
        h.addView(top)
        heroChip()?.let { chip ->
            h.addView(TextView(c).apply {
                text = chip; textSize = 12.5f; typeface = Ui.bold; setTextColor(Ui.color(c, R.color.hero_bg))
                background = GradientDrawable().apply { setColor(Ui.color(c, R.color.mint)); cornerRadius = 99f }
                setPadding(Ui.dp(c, 12), Ui.dp(c, 5), Ui.dp(c, 12), Ui.dp(c, 5))
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(c, 22) })
        }
        h.addView(Premium.heroText(c, step.title, 34f, bold = true).apply { letterSpacing = -0.02f }, Ui.lp(c, 14))
        h.addView(Premium.heroText(c, step.intro, 14.5f, soft = true), Ui.lp(c, 8))
        h.addView(Premium.heroText(c, "Contrôle parental · ${device.name}", 12f, soft = true), Ui.lp(c, 14))
        heroBox.addView(h, LinearLayout.LayoutParams(-1, -2))
        when (step) {
            Step.PROFILES -> profiles()
            Step.WHEN -> whenStep()
            Step.DAILY -> daily()
            Step.BEDTIME -> bedtime()
            Step.APPS -> appsStep()
            Step.COMFORT -> comfort()
            Step.SUMMARY -> summary()
        }
        if (step != Step.SUMMARY && step != Step.PROFILES) liveAdvice()
        gridify(content)
        renderNav()
    }

    /** Pastille du bandeau : « Pré-rempli pour 8 ans » quand l'étape suit l'âge d'un enfant. */
    private fun heroChip(): String? {
        val age = RulesAdvisor.youngestAge(rules) ?: return if (steps[index] == Step.SUMMARY) "Vérifié par l'assistant" else null
        return when (steps[index]) {
            Step.PROFILES -> "Réglages pré-remplis selon l'âge"
            Step.DAILY, Step.BEDTIME -> "Conseillé pour ${age} ans"
            Step.SUMMARY -> "Vérifié par l'assistant"
            else -> null
        }
    }

    private fun advices() = RulesAdvisor.check(rules, { pkg -> apps.firstOrNull { it.first == pkg }?.second ?: fr.paul.telecommande.core.AppCatalog.name(pkg) },
        if (withBox && apps.isNotEmpty()) apps.map { it.first } else null).filter { it.id !in seen }

    /** Pendant les étapes : les règles qui se contredisent, tout de suite, avec leur correction. */
    private fun liveAdvice() {
        advices().filter { it.level != RulesAdvisor.Level.INFO }.forEach { a -> content.addView(adviceView(a), gap(16)) }
    }

    private fun adviceView(a: RulesAdvisor.Advice) = Premium.adviceCard(this, a,
        onFix = { f -> f.applyTo(rules); seen += a.id; Ui.toast(this, "Corrigé : ${f.label}"); render() },
        onDismiss = if (a.level == RulesAdvisor.Level.CONFLICT) null else ({ seen += a.id; render() }))

    private fun renderNav() {
        val c = this
        nav.removeAllViews()
        if (index > 0) nav.addView(Ui.button(c, "Retour") { index--; render() }, LinearLayout.LayoutParams(0, Ui.dp(c, 52), 1f).apply { marginEnd = Ui.dp(c, 10) })
        val last = index == steps.lastIndex
        nav.addView(Ui.button(c, if (last) "Activer les règles" else "Continuer", primary = true) {
            if (last) finishWizard() else { index++; render() }
        }, LinearLayout.LayoutParams(0, Ui.dp(c, 52), 1.6f))
    }

    // ---------- Éléments ----------

    /** Grande carte de choix (style bouton radio). */
    /** Un choix : ligne avec coche à droite (les choix qui se suivent forment une liste groupée, comme dans les Réglages). */
    private fun choice(title: String, sub: String?, selected: Boolean, onClick: () -> Unit): View =
        Ui.checkRow(this, title, sub, selected, onClick).apply { tag = CHOICE }

    private val CHOICE = "wizard-choice"

    /** Les choix qui se suivent sont rangés dans une même liste groupée. */
    private fun gridify(container: LinearLayout) {
        val views = (0 until container.childCount).map { container.getChildAt(it) }
        container.removeAllViews()
        var grid: LinearLayout? = null
        views.forEach { v ->
            if (v.tag == CHOICE) {
                val g = grid ?: Ui.group(this).also { grid = it; container.addView(it, gap(18)) }
                (v.parent as? android.view.ViewGroup)?.removeView(v)
                Ui.addRow(g, v)
            } else {
                grid = null
                val lp = v.layoutParams
                (v.parent as? android.view.ViewGroup)?.removeView(v)
                if (lp != null) container.addView(v, lp) else container.addView(v)
            }
        }
    }

    private fun chipRow(options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit): View {
        val c = this
        val row = LinearLayout(c)
        options.forEachIndexed { i, (label, on) ->
            row.addView(Ui.button(c, label, primary = on) { onPick(i) }.apply { minHeight = Ui.dp(c, 44); setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0) },
                LinearLayout.LayoutParams(-2, Ui.dp(c, 44)).apply { marginEnd = Ui.dp(c, 8) })
        }
        return HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun timeButton(label: String, minutes: Int, done: (Int) -> Unit) = Ui.button(this, "$label ${RulesEngine.hhmm(minutes)}") {
        Ui.pickTime(this, when (label) { "De" -> "Début du couvre-feu"; "à" -> "Fin du couvre-feu"; "Début" -> "Les règles commencent à"; "Fin" -> "Les règles s'arrêtent à"; else -> label }, minutes, done)
    }

    private fun gap(dp: Int = 14) = Ui.lp(this, dp)

    // ---------- Étapes ----------

    private val EMOJIS = listOf("🦖", "🦄", "🚒", "⚽", "🌸", "🐱", "🚀", "🎮", "🙂")

    private fun profiles() {
        val c = this
        rules.profiles.forEachIndexed { i, p ->
            content.addView(choice("${p.emoji}  ${p.name}", when {
                p.unlimited -> "Parent · sans limite · demande le code"
                p.dailyTotalMin > 0 -> "${Ui.ageLabel(p)} · ${Usage.format(p.dailyTotalMin * 60_000L)} par jour" +
                    (p.age()?.let { if (RulesAdvisor.presetFor(it).dailyMin == p.dailyTotalMin) " · conseillé" else "" } ?: "")
                else -> "${Ui.ageLabel(p)} · temps de la maison (étape suivante)"
            }, true) { editProfile(i) }, gap(if (i == 0) 18 else 10))
        }
        content.addView(Ui.button(c, "+ Ajouter un enfant") { editProfile(-1) }, gap(14))
        if (rules.profiles.none { it.unlimited }) content.addView(Ui.button(c, "+ Profil parents (sans limite)") {
            rules.profiles.add(Profile(java.util.UUID.randomUUID().toString().take(8), "Parents", "👑", 0, true)); render()
        }, gap(8))
        content.addView(Ui.text(c, if (rules.profiles.isEmpty()) "Sans profil, les mêmes règles s'appliquent à tout le monde. Vous pourrez en ajouter plus tard." else "Touchez un profil pour le modifier.", 13f, muted = true), gap(14))
    }

    private fun editProfile(i: Int, preset: Profile? = null) {
        val c = this
        // Nouvel enfant : l'âge d'abord (il fixe d'office la classification des programmes)
        if (i < 0 && preset == null) {
            // l'assistant pré-remplit le temps par jour selon l'âge (modifiable juste après)
            Ui.askAge(c, "", null) { age -> editProfile(-1, Profile(java.util.UUID.randomUUID().toString().take(8), "", EMOJIS.first(),
                dailyTotalMin = RulesAdvisor.presetFor(age).dailyMin, birthYear = Ui.birthYearFor(age))) }
            return
        }
        val p = preset ?: rules.profiles[i]
        val name = EditText(c).apply { hint = "Prénom"; setText(p.name); inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        var emoji = p.emoji
        val emojiRow = LinearLayout(c)
        val views = mutableListOf<TextView>()
        EMOJIS.forEach { e ->
            emojiRow.addView(Ui.text(c, e, 26f).apply {
                setPadding(Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4)); alpha = if (e == emoji) 1f else 0.35f
                setOnClickListener { emoji = e; views.forEach { v -> v.alpha = if (v.text == e) 1f else 0.35f } }
            }.also { views += it })
        }
        val limits = listOf(0, 30, 45, 60, 90, 120, 150, 180)
        var limit = p.dailyTotalMin
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 22), 0, Ui.dp(c, 22), 0)
            addView(name); addView(HorizontalScrollView(c).apply { addView(emojiRow) }, Ui.lp(c, 8))
        }
        if (!p.unlimited) {
            val holder = LinearLayout(c)
            val hint = Ui.text(c, "", 13f).apply { setTextColor(Ui.color(c, R.color.accent)); typeface = Ui.medium }
            fun draw() {
                holder.removeAllViews()
                holder.addView(chipRow(limits.map { (if (it == 0) "Comme la maison" else Usage.format(it * 60_000L)) to (it == limit) }) { k -> limit = limits[k]; draw() })
                hint.text = p.age()?.let { a -> RulesAdvisor.presetFor(a).let { pr -> "Conseillé pour $a ans : ${Usage.format(pr.dailyMin * 60_000L)} par jour" + if (limit == pr.dailyMin) " ✓" else "" } } ?: ""
            }
            val ageBtn = Ui.button(c, "Âge : " + Ui.ageLabel(p)) {}
            ageBtn.setOnClickListener {
                Ui.askAge(c, name.text.toString().trim(), p.age()) { a ->
                    p.birthYear = Ui.birthYearFor(a); ageBtn.text = "Âge : $a ans"
                    limit = RulesAdvisor.presetFor(a).dailyMin; draw()
                }
            }
            box.addView(ageBtn, Ui.lp(c, 12))
            box.addView(Ui.text(c, "Temps par jour pour ce profil", 13f, muted = true), Ui.lp(c, 12))
            draw(); box.addView(holder, Ui.lp(c, 6))
            box.addView(hint, Ui.lp(c, 8))
        }
        val dlg = AlertDialog.Builder(c).setTitle(if (i >= 0) "Profil" else "Nouvel enfant").setView(box)
            .setPositiveButton("OK") { _, _ ->
                p.name = name.text.toString().trim().ifBlank { "Enfant" }; p.emoji = emoji; p.dailyTotalMin = limit
                if (i < 0) {
                    if (RulesAdvisor.bedtimeIfNone(rules, p)) Ui.toast(c, "Couvre-feu conseillé ajouté : ${RulesEngine.hhmm(rules.bedtimeStart)}")
                    rules.profiles.add(p)
                }
                render()
            }.setNegativeButton("Annuler", null)
        if (i >= 0) dlg.setNeutralButton("Supprimer") { _, _ -> rules.profiles.removeAt(i); render() }
        dlg.show()
    }

    private fun whenStep() {
        val c = this
        val allDay = rules.activeFrom == rules.activeTo
        content.addView(choice("Toute la journée", "Les règles s'appliquent du matin au soir.", allDay) {
            rules.activeFrom = 0; rules.activeTo = 0; render()
        }, gap(18))
        content.addView(choice("Seulement à certaines heures", "Exemple : de 7 h à 21 h, puis plus de règles le soir.", !allDay) {
            if (allDay) { rules.activeFrom = 7 * 60; rules.activeTo = 21 * 60 }; render()
        }, gap(10))
        if (!allDay) {
            val times = LinearLayout(c)
            times.addView(timeButton("Début", rules.activeFrom) { rules.activeFrom = it; render() }, LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1f).apply { marginEnd = Ui.dp(c, 8) })
            times.addView(timeButton("Fin", rules.activeTo) { rules.activeTo = it; render() }, LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1f))
            content.addView(times, gap(12))
            content.addView(Ui.text(c, "À partir de ${RulesEngine.hhmm(rules.activeTo)} : temps libre, plus aucune limite jusqu'à ${RulesEngine.hhmm(rules.activeFrom)}. La TV l'annonce à l'écran.", 13f, muted = true), gap(8))
        }
        content.addView(Ui.text(c, "Quels jours ?", 16f).apply { typeface = Ui.medium }, gap(22))
        val presets = listOf("Tous les jours" to AppRule.ALL_DAYS, "En semaine" to 0x1F, "Le week-end" to 0x60)
        content.addView(chipRow(presets.map { it.first to (rules.activeDays == it.second) }) { k -> rules.activeDays = presets[k].second; render() }, gap(10))
        val names = listOf("L", "M", "M", "J", "V", "S", "D")
        val row = LinearLayout(c)
        names.forEachIndexed { d, n ->
            val on = rules.activeDays and (1 shl d) != 0
            row.addView(Ui.button(c, n, primary = on) { rules.activeDays = rules.activeDays xor (1 shl d); render() }.apply { setPadding(0, 0, 0, 0) },
                LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f).apply { if (d > 0) marginStart = Ui.dp(c, 5) })
        }
        content.addView(row, gap(10))
        if (rules.activeDays and AppRule.ALL_DAYS != AppRule.ALL_DAYS)
            content.addView(Ui.text(c, "Les autres jours : temps libre toute la journée.", 13f, muted = true), gap(8))
    }

    private fun daily() {
        val opts = listOf(0, 30, 45, 60, 90, 120, 150, 180, 240)
        val young = RulesAdvisor.youngestAge(rules)
        val advised = young?.let { RulesAdvisor.presetFor(it).dailyMin }
        opts.forEachIndexed { i, m ->
            content.addView(choice(if (m == 0) "Pas de limite" else Usage.format(m * 60_000L) + " par jour",
                if (m == 0) "Seuls les horaires, le couvre-feu et les applis comptent." else if (m == advised) "Conseillé pour $young ans" else null, rules.dailyTotalMin == m) {
                rules.dailyTotalMin = m; render()
            }, gap(if (i == 0) 18 else 8))
        }
        if (rules.profiles.any { it.dailyTotalMin > 0 })
            content.addView(Ui.text(this, "Les profils qui ont leur propre temps le gardent.", 13f, muted = true), gap(10))
    }

    private fun bedtime() {
        val c = this
        content.addView(choice("Pas de couvre-feu", null, !rules.bedtimeEnabled) { rules.bedtimeEnabled = false; render() }, gap(18))
        val sug = RulesAdvisor.suggestedBedtime(rules)
        content.addView(choice("Couvre-feu", "La TV est interdite pendant ces heures." + (sug?.let { " Conseillé : ${RulesEngine.hhmm(it)} (âge du plus jeune)." } ?: ""), rules.bedtimeEnabled) {
            if (!rules.bedtimeEnabled && sug != null) { rules.bedtimeStart = sug; rules.bedtimeEnd = RulesAdvisor.BEDTIME_END }
            rules.bedtimeEnabled = true; render()
        }, gap(10))
        if (rules.bedtimeEnabled) {
            val times = LinearLayout(c)
            times.addView(timeButton("De", rules.bedtimeStart) { rules.bedtimeStart = it; render() }, LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1f).apply { marginEnd = Ui.dp(c, 8) })
            times.addView(timeButton("à", rules.bedtimeEnd) { rules.bedtimeEnd = it; render() }, LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1f))
            content.addView(times, gap(12))
        }
    }

    private fun appsStep() {
        val c = this
        val all = (apps + rules.apps.keys.filter { k -> apps.none { it.first == k } }.map { it to it }).distinctBy { it.first }
        if (all.isEmpty()) { content.addView(Ui.text(c, "Chargement des applis de la TV… (la TV doit être allumée). Vous pouvez passer cette étape.", 14f, muted = true), gap(18)); return }
        val grid = Ui.group(c)
        all.forEach { (pkg, label) ->
            val r = rules.apps[pkg]
            Ui.addRow(grid, Ui.row(c, label.take(1).uppercase(), label, null, appLabel(r), danger = r?.blocked == true) { appChoice(pkg, label) })
        }
        content.addView(grid, gap(18))
    }

    /** Règle d'une appli : limite par jour, plage horaire personnalisée, jours, ou blocage. */
    private fun appChoice(pkg: String, label: String) {
        val c = this
        val r = (rules.apps[pkg] ?: AppRule()).copy()
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 22), Ui.dp(c, 4), Ui.dp(c, 22), Ui.dp(c, 4)) }
        val limits = listOf(0, 15, 30, 60, 120)
        fun draw() {
            body.removeAllViews()
            body.addView(choice("Bloquée", "Jamais disponible.", r.blocked) { r.blocked = !r.blocked; draw() })
            if (r.blocked) return
            body.addView(Ui.text(c, "Temps par jour", 15f).apply { typeface = Ui.medium }, gap(16))
            body.addView(chipRow(limits.map { (if (it == 0) "Sans limite" else Usage.format(it * 60_000L)) to (r.limitMin == it) }) { k -> r.limitMin = limits[k]; draw() }, gap(8))
            body.addView(Ui.text(c, "Quand ?", 15f).apply { typeface = Ui.medium }, gap(18))
            val custom = r.from != r.to
            body.addView(choice("À toute heure", null, !custom) { r.from = 0; r.to = 0; draw() }, gap(8))
            body.addView(choice("Plage horaire personnalisée", "Exemple : seulement de 17 h à 19 h.", custom) {
                if (!custom) { r.from = 17 * 60; r.to = 19 * 60 }; draw()
            }, gap(8))
            if (custom) {
                val t = LinearLayout(c)
                t.addView(Ui.button(c, "De ${RulesEngine.hhmm(r.from)}") { Ui.pickTime(c, "$label autorisée à partir de", r.from) { r.from = it; draw() } },
                    LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f).apply { marginEnd = Ui.dp(c, 8) })
                t.addView(Ui.button(c, "à ${RulesEngine.hhmm(r.to)}") { Ui.pickTime(c, "$label autorisée jusqu'à", r.to) { r.to = it; draw() } },
                    LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f))
                body.addView(t, gap(10))
            }
            body.addView(Ui.text(c, "Quels jours ?", 15f).apply { typeface = Ui.medium }, gap(18))
            val names = listOf("L", "M", "M", "J", "V", "S", "D")
            val row = LinearLayout(c)
            names.forEachIndexed { d, n ->
                row.addView(Ui.button(c, n, primary = r.days and (1 shl d) != 0) { r.days = r.days xor (1 shl d); draw() }.apply { setPadding(0, 0, 0, 0) },
                    LinearLayout.LayoutParams(0, Ui.dp(c, 42), 1f).apply { if (d > 0) marginStart = Ui.dp(c, 4) })
            }
            body.addView(row, gap(8))
            body.addView(Ui.text(c, if (r.days == 0) "Aucun jour choisi : l'appli sera indisponible." else "Les autres jours, l'appli n'est pas disponible.", 12f, muted = true).apply {
                visibility = if (r.days and AppRule.ALL_DAYS == AppRule.ALL_DAYS) View.GONE else View.VISIBLE }, gap(6))
        }
        draw()
        AlertDialog.Builder(c).setTitle(label).setView(ScrollView(c).apply { addView(body) })
            .setPositiveButton("Valider") { _, _ -> if (r.isDefault) rules.apps.remove(pkg) else rules.apps[pkg] = r; render() }
            .setNegativeButton("Annuler", null).show()
    }

    private fun appLabel(r: AppRule?): String = when {
        r == null || r.isDefault -> "Libre"
        r.blocked -> "Bloquée"
        else -> listOfNotNull(
            r.limitMin.takeIf { it > 0 }?.let { Usage.format(it * 60_000L) },
            if (r.from != r.to) "${RulesEngine.hhmm(r.from)}–${RulesEngine.hhmm(r.to)}" else null,
            if (r.days and AppRule.ALL_DAYS != AppRule.ALL_DAYS) "certains jours" else null
        ).joinToString(" · ")
    }

    private fun comfort() {
        content.addView(choice("Finir l'épisode en cours", "La TV attend la fin de la vidéo (au plus ${rules.graceMin.takeIf { it > 0 } ?: 10} min) avant de couper.", rules.graceMin > 0) {
            rules.graceMin = if (rules.graceMin > 0) 0 else 10; render()
        }, gap(18))
        content.addView(choice("Sablier à l'écran", "Le temps restant s'affiche dans un coin pendant les 15 dernières minutes.", rules.showTimer) {
            rules.showTimer = !rules.showTimer; render()
        }, gap(10))
        content.addView(choice("« Vous êtes toujours là ? »", "Après ${Usage.format((rules.stillThereMin.takeIf { it > 0 } ?: 180) * 60_000L)} sans toucher la télécommande, la TV le demande puis se met en veille.", rules.stillThereMin > 0) {
            rules.stillThereMin = if (rules.stillThereMin > 0) 0 else 180; render()
        }, gap(10))
        content.addView(Ui.text(this, "Touchez une option pour l'activer ou la désactiver.", 13f, muted = true), gap(10))
    }

    private fun summary() {
        val c = this
        val adv = advices()
        if (adv.isEmpty()) content.addView(Premium.adviceCard(c, RulesAdvisor.Advice("ok", RulesAdvisor.Level.INFO, "Aucune contradiction",
            "Vos règles tiennent ensemble. Vous pourrez les changer à tout moment."), onFix = {}), gap(18))
        else adv.forEachIndexed { i, a -> content.addView(adviceView(a), gap(if (i == 0) 18 else 10)) }
        content.addView(Ui.title(c, "En résumé", 18f), gap(26))
        val lines = mutableListOf<String>()
        if (withBox) lines += if (rules.profiles.isEmpty()) "👥  Mêmes règles pour tout le monde" else "👥  Profils : " + rules.profiles.joinToString(", ") { "${it.emoji} ${it.name}" }
        lines += "🕘  " + RulesEngine.activeLabel(rules) + if (rules.activeFrom != rules.activeTo || rules.activeDays and AppRule.ALL_DAYS != AppRule.ALL_DAYS) " · temps libre en dehors" else ""
        lines += "⏱️  " + if (rules.dailyTotalMin > 0) "${Usage.format(rules.dailyTotalMin * 60_000L)} de TV par jour" else "Pas de limite de temps par jour"
        lines += "🌙  " + if (rules.bedtimeEnabled) "Couvre-feu de ${RulesEngine.hhmm(rules.bedtimeStart)} à ${RulesEngine.hhmm(rules.bedtimeEnd)}" else "Pas de couvre-feu"
        if (withBox) {
            val limited = rules.apps.filterValues { !it.isDefault }
            lines += "📺  " + if (limited.isEmpty()) "Aucune règle par appli" else "${limited.size} appli(s) avec une règle"
            lines += "✨  " + listOfNotNull("fin d'épisode".takeIf { rules.graceMin > 0 }, "sablier".takeIf { rules.showTimer }, "« toujours là ? »".takeIf { rules.stillThereMin > 0 }).ifEmpty { listOf("aucun confort") }.joinToString(", ")
        }
        val card = Ui.card(c)
        lines.forEachIndexed { i, l -> card.addView(Ui.text(c, l, 15f), Ui.lp(c, if (i == 0) 0 else 12)) }
        content.addView(card, gap(18))
        content.addView(Ui.text(c, if (withBox) "Les règles sont envoyées à la TV, qui les applique même téléphone éteint." else "Les règles sont appliquées par ce téléphone quand il est sur le même Wi-Fi que la TV.", 13f, muted = true), gap(12))
    }

    // ---------- Enregistrement ----------

    private fun finishWizard() {
        rules.enabled = true
        if (withBox) {
            rules.pinHash = store.boxPinHash
            rules.version = System.currentTimeMillis()
            store.saveBoxRules(device.id, rules)
            lifecycleScope.launch {
                val ok = runCatching { BoxClient.of(this@RulesWizardActivity, device).call("set_rules", JSONObject().put("rules", rules.toJson())) }.isSuccess
                Ui.toast(this@RulesWizardActivity, if (ok) "Règles activées sur la TV ✓" else "TV injoignable : les règles partiront dès qu'elle répondra")
                finish()
            }
        } else {
            store.save(device.id, toPhone(store.rules(device.id)))
            fr.paul.telecommande.service.AssistantService.start(this)
            Ui.toast(this, "Règles activées ✓")
            finish()
        }
    }
}
