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
import fr.paul.telecommande.shared.RulesEngine
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Assistant pas à pas du contrôle parental : une question par écran, avec des choix clairs.
 * Fonctionne avec l'appli Box (règles appliquées par la TV) ou sans (règles appliquées par le téléphone).
 */
class RulesWizardActivity : AppCompatActivity() {
    companion object { const val EXTRA_ID = "device_id" }

    private enum class Step(val title: String, val intro: String) {
        PROFILES("Qui regarde la TV ?", "Créez un profil par enfant. À l'allumage, la TV demandera qui regarde, et chacun aura son propre temps."),
        WHEN("Quand les règles s'appliquent", "En dehors de ces heures et de ces jours, c'est du temps libre : plus aucune limite."),
        DAILY("Temps de TV par jour", "Le temps total pendant lequel la TV peut être utilisée chaque jour."),
        BEDTIME("Couvre-feu", "Pendant le couvre-feu, la TV est interdite. Il s'applique même pendant le temps libre."),
        APPS("Les applis", "Fixez une limite pour une appli précise, ou bloquez-la. Facultatif."),
        COMFORT("Confort", "Des petites attentions pour que l'arrêt se passe bien."),
        SUMMARY("C'est prêt", "Vérifiez les règles avant de les activer.")
    }

    private lateinit var device: Device
    private lateinit var store: ParentalStore
    private lateinit var rules: BoxRules
    private var withBox = false
    private lateinit var steps: List<Step>
    private var index = 0
    private var apps: List<Pair<String, String>> = emptyList()

    private lateinit var content: LinearLayout
    private lateinit var nav: LinearLayout

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

        val c = this
        content = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(c, 18); setPadding(p, p * 2, p, p) }
        nav = LinearLayout(c).apply {
            setPadding(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 16))
            setBackgroundResource(R.drawable.bg_bar)
        }
        setContentView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_screen)
            addView(ScrollView(c).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(nav, LinearLayout.LayoutParams(-1, -2))
        })
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
        content.addView(Ui.label(c, "Contrôle parental · ${device.name}"))
        content.addView(Ui.text(c, "Étape ${index + 1} sur ${steps.size}", 13f, muted = true), Ui.lp(c, 6))
        // barre segmentée : une pastille par étape
        content.addView(LinearLayout(c).apply {
            steps.indices.forEach { i ->
                addView(View(c).apply {
                    background = if (i <= index) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.color(c, R.color.accent), Ui.color(c, R.color.accent2))).apply { cornerRadius = 99f }
                    else GradientDrawable().apply { setColor(Ui.color(c, R.color.track)); cornerRadius = 99f }
                }, LinearLayout.LayoutParams(0, Ui.dp(c, 5), 1f).apply { if (i > 0) marginStart = Ui.dp(c, 4) })
            }
        }, Ui.lp(c, 8))
        content.addView(Ui.title(c, step.title, 28f), Ui.lp(c, 22))
        content.addView(Ui.text(c, step.intro, 15f, muted = true), Ui.lp(c, 8))
        when (step) {
            Step.PROFILES -> profiles()
            Step.WHEN -> whenStep()
            Step.DAILY -> daily()
            Step.BEDTIME -> bedtime()
            Step.APPS -> appsStep()
            Step.COMFORT -> comfort()
            Step.SUMMARY -> summary()
        }
        renderNav()
    }

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
    private fun choice(title: String, sub: String?, selected: Boolean, onClick: () -> Unit): View {
        val c = this
        return LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = Ui.dp(c, 20).toFloat()
                setColor(Ui.color(c, R.color.surface))
                setStroke(Ui.dp(c, if (selected) 2 else 1), Ui.color(c, if (selected) R.color.accent else R.color.line))
            }
            setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 14))
            isClickable = true; isFocusable = true; setOnClickListener { onClick() }
            addView(View(c).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (selected) setColor(Ui.color(c, R.color.accent)) else { setColor(Color.TRANSPARENT); setStroke(Ui.dp(c, 2), Ui.color(c, R.color.line)) }
                }
            }, LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)).apply { marginEnd = Ui.dp(c, 14) })
            val t = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            t.addView(Ui.text(c, title, 16f).apply { typeface = Ui.medium })
            sub?.let { t.addView(Ui.text(c, it, 13f, muted = true), Ui.lp(c, 2)) }
            addView(t, LinearLayout.LayoutParams(0, -2, 1f))
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
                p.dailyTotalMin > 0 -> "${Ui.ageLabel(p)} · ${Usage.format(p.dailyTotalMin * 60_000L)} par jour"
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
            Ui.askAge(c, "", null) { age -> editProfile(-1, Profile(java.util.UUID.randomUUID().toString().take(8), "", EMOJIS.first(), birthYear = Ui.birthYearFor(age))) }
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
        val limits = listOf(0, 30, 45, 60, 90, 120, 180)
        var limit = p.dailyTotalMin
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 22), 0, Ui.dp(c, 22), 0)
            addView(name); addView(HorizontalScrollView(c).apply { addView(emojiRow) }, Ui.lp(c, 8))
        }
        if (!p.unlimited) {
            val ageBtn = Ui.button(c, "Âge : " + Ui.ageLabel(p)) {}
            ageBtn.setOnClickListener { Ui.askAge(c, name.text.toString().trim(), p.age()) { a -> p.birthYear = Ui.birthYearFor(a); ageBtn.text = "Âge : $a ans" } }
            box.addView(ageBtn, Ui.lp(c, 12))
            box.addView(Ui.text(c, "Temps par jour pour ce profil", 13f, muted = true), Ui.lp(c, 12))
            val holder = LinearLayout(c)
            fun draw() {
                holder.removeAllViews()
                holder.addView(chipRow(limits.map { (if (it == 0) "Comme la maison" else Usage.format(it * 60_000L)) to (it == limit) }) { k -> limit = limits[k]; draw() })
            }
            draw(); box.addView(holder, Ui.lp(c, 6))
        }
        val dlg = AlertDialog.Builder(c).setTitle(if (i >= 0) "Profil" else "Nouvel enfant").setView(box)
            .setPositiveButton("OK") { _, _ ->
                p.name = name.text.toString().trim().ifBlank { "Enfant" }; p.emoji = emoji; p.dailyTotalMin = limit
                if (i < 0) rules.profiles.add(p)
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
        val opts = listOf(0, 30, 45, 60, 90, 120, 180, 240)
        opts.forEachIndexed { i, m ->
            content.addView(choice(if (m == 0) "Pas de limite" else Usage.format(m * 60_000L) + " par jour",
                if (m == 0) "Seuls les horaires, le couvre-feu et les applis comptent." else null, rules.dailyTotalMin == m) {
                rules.dailyTotalMin = m; render()
            }, gap(if (i == 0) 18 else 8))
        }
        if (rules.profiles.any { it.dailyTotalMin > 0 })
            content.addView(Ui.text(this, "Les profils qui ont leur propre temps le gardent.", 13f, muted = true), gap(10))
    }

    private fun bedtime() {
        val c = this
        content.addView(choice("Pas de couvre-feu", null, !rules.bedtimeEnabled) { rules.bedtimeEnabled = false; render() }, gap(18))
        content.addView(choice("Couvre-feu", "La TV est interdite pendant ces heures.", rules.bedtimeEnabled) { rules.bedtimeEnabled = true; render() }, gap(10))
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
        val card = Ui.card(c)
        all.forEach { (pkg, label) ->
            val r = rules.apps[pkg]
            val row = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 10))
                isClickable = true; setOnClickListener { appChoice(pkg, label) }
            }
            row.addView(Ui.text(c, label, 15f), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Ui.text(c, appLabel(r), 14f).apply {
                setTextColor(Ui.color(c, if (r == null || r.isDefault) R.color.muted else if (r.blocked) R.color.danger else R.color.accent)); typeface = Ui.medium
            })
            card.addView(row)
        }
        content.addView(card, gap(18))
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
