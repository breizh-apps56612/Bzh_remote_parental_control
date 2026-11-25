package fr.paul.telecommande.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import fr.paul.telecommande.R
import fr.paul.telecommande.core.BoxClient
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.ParentalStore
import fr.paul.telecommande.core.Usage
import fr.paul.telecommande.shared.AppRule
import fr.paul.telecommande.shared.BoxRules
import fr.paul.telecommande.shared.RulesEngine
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Règles appliquées par l'appli Box : limites et horaires par appli, temps total, couvre-feu. */
class BoxRulesActivity : AppCompatActivity() {
    companion object { const val EXTRA_ID = "device_id" }

    private lateinit var device: Device
    private lateinit var store: ParentalStore
    private lateinit var rules: BoxRules
    private lateinit var root: LinearLayout
    private var apps: List<Pair<String, String>> = emptyList()
    private var unlocked = false
    private var syncState = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        device = intent.getStringExtra(EXTRA_ID)?.let { DeviceStore(this).get(it) } ?: run { finish(); return }
        Ui.systemBars(this)
        store = ParentalStore(this)
        rules = store.boxRules(device.id)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(this@BoxRulesActivity, 16); setPadding(p, p, p, p * 2) }
        setContentView(ScrollView(this).apply { setBackgroundResource(R.drawable.bg_screen); addView(root) })
        render()
        Ui.createPinIfNeeded(this) {
            Ui.requirePin(this, "Saisissez le code parent pour modifier les règles.") { unlocked = true; render(); load() }
        }
    }

    private fun load() = lifecycleScope.launch {
        val c = BoxClient.of(this@BoxRulesActivity, device)
        runCatching {
            val remote = BoxRules.fromJson(c.call("get_rules").optJSONObject("rules"))
            if (remote.version > rules.version) { rules = remote; store.saveBoxRules(device.id, rules) }
            val arr = c.call("apps").optJSONArray("apps")
            apps = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it).let { o -> o.optString("pkg") to o.optString("label") } }
            syncState = "Synchronisé avec la box"
        }.onFailure { syncState = "Box injoignable : les changements seront envoyés à sa prochaine connexion." }
        render()
    }

    private fun save() {
        rules.pinHash = store.boxPinHash
        rules.version = System.currentTimeMillis()
        store.saveBoxRules(device.id, rules)
        render()
        lifecycleScope.launch {
            runCatching { BoxClient.of(this@BoxRulesActivity, device).call("set_rules", JSONObject().put("rules", rules.toJson())) }
                .onSuccess { syncState = "Envoyé à la box ✓" }
                .onFailure { syncState = "Box injoignable : envoi automatique à sa prochaine connexion." }
            render()
        }
    }

    private val EMOJIS = listOf("🦖", "🦄", "🚒", "⚽", "🌸", "🐱", "🚀", "🎮", "🙂", "👑")

    /** Création / modification d'un profil (index -1 = nouveau). */
    private fun editProfile(index: Int) {
        val c = this
        val p = if (index >= 0) rules.profiles[index].copy() else fr.paul.telecommande.shared.Profile(java.util.UUID.randomUUID().toString().take(8), "", EMOJIS.first())
        val name = android.widget.EditText(c).apply { hint = "Prénom"; setText(p.name); inputType = android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        val emojis = LinearLayout(c)
        var picked = p.emoji
        val chips = mutableListOf<android.widget.TextView>()
        EMOJIS.forEach { e ->
            val t = Ui.text(c, e, 24f).apply {
                gravity = Gravity.CENTER; setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6))
                setOnClickListener { picked = e; chips.forEach { it.alpha = if (it.text == e) 1f else 0.35f } }
                alpha = if (e == picked) 1f else 0.35f
            }
            chips += t; emojis.addView(t)
        }
        var limit = p.dailyTotalMin
        val limitBtn = Ui.button(c, if (limit > 0) "${Usage.format(limit * 60_000L)} par jour" else "Règles générales") {}
        limitBtn.setOnClickListener {
            pick("Temps de TV par jour", listOf(0, 30, 45, 60, 90, 120, 180), limit) { limit = it; limitBtn.text = if (it > 0) "${Usage.format(it * 60_000L)} par jour" else "Règles générales" }
        }
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 22), Ui.dp(c, 4), Ui.dp(c, 22), 0)
            addView(name); addView(android.widget.HorizontalScrollView(c).apply { addView(emojis) }, Ui.lp(c, 10))
            if (!p.unlimited) addView(limitBtn, Ui.lp(c, 12))
        }
        val dlg = androidx.appcompat.app.AlertDialog.Builder(c).setTitle(if (index >= 0) "Profil" else "Nouveau profil").setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                p.name = name.text.toString().trim().ifBlank { "Profil" }; p.emoji = picked; p.dailyTotalMin = limit
                if (index >= 0) rules.profiles[index] = p else rules.profiles.add(p)
                save()
            }.setNegativeButton("Annuler", null)
        if (index >= 0) dlg.setNeutralButton("Supprimer") { _, _ -> rules.profiles.removeAt(index); save() }
        dlg.show()
    }

    private fun render() {
        val c = this
        root.removeAllViews()
        root.addView(Ui.text(c, device.name, 13f, muted = true))
        root.addView(Ui.title(c, "Règles et limites"))
        if (!unlocked) { root.addView(Ui.text(c, "Protégé par le code parent.", 14f, muted = true), Ui.lp(c, 10)); return }
        if (syncState.isNotBlank()) root.addView(Ui.text(c, syncState, 12f, muted = true), Ui.lp(c, 4))
        root.addView(Ui.button(c, "✨ Assistant pas à pas", primary = true) {
            startActivity(Intent(c, RulesWizardActivity::class.java).putExtra(RulesWizardActivity.EXTRA_ID, device.id))
        }, Ui.lp(c, 12))
        root.addView(Ui.text(c, RulesEngine.activeLabel(rules) + if (rules.activeFrom != rules.activeTo) " · temps libre en dehors" else "", 13f, muted = true), Ui.lp(c, 8))

        val main = Ui.card(c)
        main.addView(switch("Activer le contrôle parental", rules.enabled) { rules.enabled = it; save() })
        main.addView(switch("Protéger les Paramètres de la TV par le code parent", rules.protectSettings) { rules.protectSettings = it; save() }, Ui.lp(c, 8))
        main.addView(Ui.text(c, "Empêche de désactiver la protection ou de désinstaller l'appli depuis la TV.", 12f, muted = true))
        root.addView(main, Ui.lp(c, 12))

        val total = Ui.card(c)
        total.addView(Ui.text(c, "Temps de TV par jour", 16f))
        total.addView(Ui.button(c, if (rules.dailyTotalMin > 0) Usage.format(rules.dailyTotalMin * 60_000L) else "Aucune limite") {
            pick("Temps de TV par jour", listOf(0, 60, 90, 120, 180, 240, 300), rules.dailyTotalMin) { rules.dailyTotalMin = it; save() }
        }, Ui.lp(c, 8))
        total.addView(Ui.text(c, "Durée d'une demande de temps depuis la TV", 14f), Ui.lp(c, 14))
        total.addView(Ui.button(c, "${rules.requestMinutes} min") {
            pick("Durée d'une demande", listOf(5, 10, 15, 30, 60), rules.requestMinutes) { rules.requestMinutes = it; save() }
        }, Ui.lp(c, 8))
        root.addView(total, Ui.lp(c, 10))

        val bed = Ui.card(c)
        bed.addView(switch("Couvre-feu", rules.bedtimeEnabled) { rules.bedtimeEnabled = it; save() })
        val times = LinearLayout(c)
        times.addView(Ui.button(c, "De " + RulesEngine.hhmm(rules.bedtimeStart)) { time(rules.bedtimeStart) { rules.bedtimeStart = it; save() } },
            LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f).apply { marginEnd = Ui.dp(c, 6) })
        times.addView(Ui.button(c, "à " + RulesEngine.hhmm(rules.bedtimeEnd)) { time(rules.bedtimeEnd) { rules.bedtimeEnd = it; save() } },
            LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f).apply { marginStart = Ui.dp(c, 6) })
        bed.addView(times, Ui.lp(c, 10))
        root.addView(bed, Ui.lp(c, 10))

        // --- Profils « Qui regarde ? » ---
        val prof = Ui.card(c)
        prof.addView(Ui.text(c, "Qui regarde ?", 16f))
        prof.addView(Ui.text(c, "À chaque allumage, la TV demande qui regarde. Chaque profil a son propre temps par jour. Le profil parent (sans limite) demande le code.", 12f, muted = true), Ui.lp(c, 2))
        rules.profiles.forEachIndexed { i, p ->
            val row = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 4))
                isClickable = true; setOnClickListener { editProfile(i) }
            }
            row.addView(Ui.text(c, p.emoji, 26f), LinearLayout.LayoutParams(Ui.dp(c, 44), -2))
            val t = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            t.addView(Ui.text(c, p.name, 15f))
            t.addView(Ui.text(c, when { p.unlimited -> "Parent · sans limite · code"; p.dailyTotalMin > 0 -> "${Usage.format(p.dailyTotalMin * 60_000L)} par jour"; else -> "Règles générales" }, 12f, muted = true))
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Ui.text(c, "Modifier", 13f, muted = true))
            prof.addView(row)
        }
        prof.addView(Ui.button(c, "+ Ajouter un profil") { editProfile(-1) }, Ui.lp(c, 10))
        if (rules.profiles.none { it.unlimited }) prof.addView(Ui.button(c, "+ Profil parent (sans limite)") {
            rules.profiles.add(fr.paul.telecommande.shared.Profile(java.util.UUID.randomUUID().toString().take(8), "Parents", "👑", 0, true)); save()
        }, Ui.lp(c, 8))
        root.addView(prof, Ui.lp(c, 10))

        // --- Confort ---
        val comfort = Ui.card(c)
        comfort.addView(Ui.text(c, "Confort", 16f))
        comfort.addView(switch("Fin d'épisode : finir la vidéo en cours avant de couper", rules.graceMin > 0) { rules.graceMin = if (it) 10 else 0; save() }, Ui.lp(c, 8))
        if (rules.graceMin > 0) comfort.addView(Ui.button(c, "Au plus ${rules.graceMin} min de plus") {
            pick("Attente maximale", listOf(10, 15, 20, 30, 45), rules.graceMin) { rules.graceMin = it; save() }
        }, Ui.lp(c, 6))
        comfort.addView(switch("Sablier dans un coin de l'écran (dernières 15 min)", rules.showTimer) { rules.showTimer = it; save() }, Ui.lp(c, 10))
        comfort.addView(switch("« Vous êtes toujours là ? » puis veille", rules.stillThereMin > 0) { rules.stillThereMin = if (it) 180 else 0; save() }, Ui.lp(c, 10))
        if (rules.stillThereMin > 0) comfort.addView(Ui.button(c, "Après ${Usage.format(rules.stillThereMin * 60_000L)} sans toucher la télécommande") {
            pick("Sans aucune touche pendant", listOf(60, 90, 120, 180, 240), rules.stillThereMin) { rules.stillThereMin = it; save() }
        }, Ui.lp(c, 6))
        root.addView(comfort, Ui.lp(c, 10))

        val list = Ui.card(c)
        list.addView(Ui.text(c, "Applis de la TV", 16f))
        list.addView(Ui.text(c, "Touchez une appli pour régler sa limite, ses jours et ses horaires.", 12f, muted = true))
        val all = (apps + rules.apps.keys.filter { k -> apps.none { it.first == k } }.map { it to it }).distinctBy { it.first }
        if (all.isEmpty()) list.addView(Ui.text(c, "Liste disponible quand la box est allumée.", 13f, muted = true), Ui.lp(c, 8))
        all.forEach { (pkg, label) ->
            val r = rules.apps[pkg]
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 10))
                isClickable = true; isFocusable = true; setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { editApp(pkg, label) }
            }
            row.addView(Ui.text(c, label, 15f))
            row.addView(Ui.text(c, summary(r), 12f, muted = r == null || r.isDefault).apply {
                if (r?.blocked == true) setTextColor(Ui.color(c, R.color.danger))
            })
            list.addView(row)
        }
        root.addView(list, Ui.lp(c, 10))
    }

    private fun summary(r: AppRule?): String {
        if (r == null || r.isDefault) return "Aucune règle"
        if (r.blocked) return "Bloquée"
        val parts = mutableListOf<String>()
        if (r.limitMin > 0) parts += Usage.format(r.limitMin * 60_000L) + " par jour"
        if (r.days != AppRule.ALL_DAYS) parts += RulesEngine.daysLabel(r.days)
        if (r.from != r.to) parts += RulesEngine.hhmm(r.from) + "–" + RulesEngine.hhmm(r.to)
        return parts.joinToString(" · ")
    }

    private fun editApp(pkg: String, label: String) {
        val r = (rules.apps[pkg] ?: AppRule()).copy()
        val c = this
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), 0) }
        fun build() {
            box.removeAllViews()
            box.addView(switch("Bloquer cette appli", r.blocked) { r.blocked = it; build() })
            if (!r.blocked) {
                box.addView(Ui.text(c, "Limite par jour", 14f), Ui.lp(c, 12))
                box.addView(Ui.button(c, if (r.limitMin > 0) Usage.format(r.limitMin * 60_000L) else "Illimitée") {
                    pick("Limite par jour", listOf(0, 15, 30, 45, 60, 90, 120, 180), r.limitMin) { r.limitMin = it; build() }
                }, Ui.lp(c, 6))
                box.addView(Ui.text(c, "Jours autorisés", 14f), Ui.lp(c, 12))
                val days = LinearLayout(c)
                listOf("L", "M", "M", "J", "V", "S", "D").forEachIndexed { i, l ->
                    val on = r.days and (1 shl i) != 0
                    days.addView(TextView(c).apply {
                        text = l; gravity = Gravity.CENTER; textSize = 14f
                        setTextColor(if (on) android.graphics.Color.WHITE else Ui.color(c, R.color.ink))
                        setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
                        contentDescription = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")[i] + if (on) ", autorisé" else ", interdit"
                        isClickable = true; isFocusable = true
                        setOnClickListener { r.days = r.days xor (1 shl i); build() }
                    }, LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f).apply { setMargins(Ui.dp(c, 2), 0, Ui.dp(c, 2), 0) })
                }
                box.addView(days, Ui.lp(c, 6))
                box.addView(Ui.text(c, "Horaires", 14f), Ui.lp(c, 12))
                box.addView(switch("Toute la journée", r.from == r.to) { all -> if (all) { r.from = 0; r.to = 0 } else { r.from = 16 * 60; r.to = 19 * 60 }; build() })
                if (r.from != r.to) {
                    val t = LinearLayout(c)
                    t.addView(Ui.button(c, "De " + RulesEngine.hhmm(r.from)) { time(r.from) { r.from = it; build() } },
                        LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f).apply { marginEnd = Ui.dp(c, 6) })
                    t.addView(Ui.button(c, "à " + RulesEngine.hhmm(r.to)) { time(r.to) { r.to = it; build() } },
                        LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f).apply { marginStart = Ui.dp(c, 6) })
                    box.addView(t, Ui.lp(c, 6))
                }
            }
        }
        build()
        AlertDialog.Builder(c).setTitle(label).setView(ScrollView(c).apply { addView(box) })
            .setPositiveButton("Enregistrer") { _, _ ->
                if (r.isDefault) rules.apps.remove(pkg) else rules.apps[pkg] = r
                save()
            }
            .setNeutralButton("Aucune règle") { _, _ -> rules.apps.remove(pkg); save() }
            .setNegativeButton("Annuler", null).show()
    }

    private fun switch(label: String, value: Boolean, onChange: (Boolean) -> Unit) = SwitchCompat(this).apply {
        text = label; textSize = 15f; setTextColor(Ui.color(this@BoxRulesActivity, R.color.ink)); isChecked = value
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun pick(title: String, options: List<Int>, current: Int, done: (Int) -> Unit) {
        val labels = options.map { if (it == 0) "Aucune limite" else Usage.format(it * 60_000L) }.toTypedArray()
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels, options.indexOf(current).coerceAtLeast(0)) { d, i -> d.dismiss(); done(options[i]) }
            .setNegativeButton("Annuler", null).show()
    }

    private fun time(minutes: Int, done: (Int) -> Unit) =
        Ui.pickTime(this, "Choisir l'heure", minutes, done)
}
