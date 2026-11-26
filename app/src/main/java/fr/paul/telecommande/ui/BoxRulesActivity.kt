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
    companion object {
        const val EXTRA_ID = "device_id"
        /** Index du profil à ouvrir tout de suite (−1 = nouveau profil). */
        const val EXTRA_PROFILE = "profile_index"
    }

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
            Ui.requirePin(this, "Saisissez le code parent pour modifier les règles.") {
                unlocked = true; render(); load()
                // Ouvert depuis l'écran Parental sur un profil : sa fiche directement
                intent.getIntExtra(EXTRA_PROFILE, -2).takeIf { it >= -1 && it < rules.profiles.size }?.let { editProfile(it) }
            }
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
        if (unlocked) askMissingAges()
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
    /** Profils d'enfants créés avant l'âge : on le demande une fois, profil par profil. */
    private var agesAsked = false
    private fun askMissingAges() {
        if (agesAsked) return
        val i = rules.profiles.indexOfFirst { !it.unlimited && it.birthYear == 0 }
        if (i < 0) return
        val p = rules.profiles[i]
        Ui.askAge(this, p.name, null) { a ->
            rules.profiles[i] = p.copy(birthYear = Ui.birthYearFor(a)); save()
            askMissingAges()
        }
        if (rules.profiles.count { !it.unlimited && it.birthYear == 0 } <= 1) agesAsked = true
    }

    private fun editProfile(index: Int, preset: fr.paul.telecommande.shared.Profile? = null) {
        val c = this
        // Nouvel enfant : l'âge d'abord (il fixe d'office la classification des programmes)
        if (index < 0 && preset == null) {
            Ui.askAge(c, "", null) { age -> editProfile(-1, fr.paul.telecommande.shared.Profile(java.util.UUID.randomUUID().toString().take(8), "", EMOJIS.first(), birthYear = Ui.birthYearFor(age))) }
            return
        }
        val p = preset ?: rules.profiles[index].copy()
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
        val ageBtn = Ui.button(c, "Âge : " + Ui.ageLabel(p)) {}
        ageBtn.setOnClickListener { Ui.askAge(c, name.text.toString().trim(), p.age()) { a -> p.birthYear = Ui.birthYearFor(a); ageBtn.text = "Âge : $a ans" } }
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 22), Ui.dp(c, 4), Ui.dp(c, 22), 0)
            addView(name); addView(android.widget.HorizontalScrollView(c).apply { addView(emojis) }, Ui.lp(c, 10))
            if (!p.unlimited) { addView(ageBtn, Ui.lp(c, 12)); addView(limitBtn, Ui.lp(c, 10)) }
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

    private fun section(text: String) = Ui.label(this, text).apply { setPadding(Ui.dp(this@BoxRulesActivity, 6), 0, 0, 0) }

    private fun minutes(m: Int) = if (m > 0) Usage.format(m * 60_000L) else "Aucune limite"

    private fun render() {
        val c = this
        root.removeAllViews()
        root.addView(Ui.text(c, device.name, 13f, muted = true))
        root.addView(Ui.title(c, "Règles et limites", 28f), Ui.lp(c, 2))
        if (!unlocked) { root.addView(Ui.text(c, "Protégé par le code parent.", 14f, muted = true), Ui.lp(c, 10)); return }
        if (syncState.isNotBlank()) {
            val ok = syncState.startsWith("Synchronisé") || syncState.startsWith("Envoyé")
            root.addView(LinearLayout(c).apply { addView(Ui.pill(c, syncState, Ui.color(c, if (ok) R.color.ok else R.color.danger))) }, Ui.lp(c, 10))
        }

        // ===== Interrupteur principal =====
        val main = Ui.group(c)
        Ui.addRow(main, Ui.switchRow(c, "🛡️", "Contrôle parental",
            if (rules.enabled) "Activé : la TV applique ces règles, même téléphone éteint." else "Désactivé : la TV n'applique aucune règle.",
            rules.enabled) { rules.enabled = it; save() })
        root.addView(main, Ui.lp(c, 14))
        root.addView(Ui.tile(c, "✨", "Assistant pas à pas", primary = true) {
            startActivity(Intent(c, RulesWizardActivity::class.java).putExtra(RulesWizardActivity.EXTRA_ID, device.id))
        }, LinearLayout.LayoutParams(-1, Ui.dp(c, 56)).apply { topMargin = Ui.dp(c, 10) })

        // ===== L'essentiel =====
        root.addView(section("L'essentiel"), Ui.lp(c, 26))
        val ess = Ui.group(c)
        Ui.addRow(ess, Ui.row(c, "⏱️", "Temps de TV par jour", "Toutes applis confondues", minutes(rules.dailyTotalMin)) {
            pick("Temps de TV par jour", listOf(0, 60, 90, 120, 180, 240, 300), rules.dailyTotalMin) { rules.dailyTotalMin = it; save() }
        })
        Ui.addRow(ess, Ui.switchRow(c, "🌙", "Couvre-feu",
            if (rules.bedtimeEnabled) "De ${RulesEngine.hhmm(rules.bedtimeStart)} à ${RulesEngine.hhmm(rules.bedtimeEnd)}" else "Pas de TV la nuit", rules.bedtimeEnabled) { rules.bedtimeEnabled = it; save() })
        if (rules.bedtimeEnabled) {
            Ui.addRow(ess, Ui.row(c, "🕘", "Début du couvre-feu", null, RulesEngine.hhmm(rules.bedtimeStart)) { time(rules.bedtimeStart) { rules.bedtimeStart = it; save() } })
            Ui.addRow(ess, Ui.row(c, "🌅", "Fin du couvre-feu", null, RulesEngine.hhmm(rules.bedtimeEnd)) { time(rules.bedtimeEnd) { rules.bedtimeEnd = it; save() } })
        }
        Ui.addRow(ess, Ui.row(c, "🙋", "Demande de temps depuis la TV", "Durée accordée si vous acceptez", "${rules.requestMinutes} min") {
            pick("Durée d'une demande", listOf(5, 10, 15, 30, 60), rules.requestMinutes) { rules.requestMinutes = it; save() }
        })
        Ui.addRow(ess, Ui.row(c, "📅", "Quand les règles s'appliquent",
            RulesEngine.activeLabel(rules) + if (rules.activeFrom != rules.activeTo) " · temps libre en dehors" else "") {
            startActivity(Intent(c, RulesWizardActivity::class.java).putExtra(RulesWizardActivity.EXTRA_ID, device.id))
        })
        root.addView(ess, Ui.lp(c, 8))

        // ===== Qui regarde ? =====
        root.addView(section("Qui regarde ?"), Ui.lp(c, 26))
        root.addView(Ui.text(c, "À chaque allumage, la TV demande qui regarde. Chaque profil a son propre temps.", 13f, muted = true).apply {
            setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0) }, Ui.lp(c, 4))
        val prof = Ui.group(c)
        rules.profiles.forEachIndexed { i, p ->
            Ui.addRow(prof, Ui.row(c, p.emoji, p.name, when {
                p.unlimited -> "Parent · sans limite · code demandé"
                p.dailyTotalMin > 0 -> "${Ui.ageLabel(p)} · ${Usage.format(p.dailyTotalMin * 60_000L)} par jour"
                else -> "${Ui.ageLabel(p)} · règles générales"
            }) { editProfile(i) })
        }
        Ui.addRow(prof, Ui.row(c, "＋", "Ajouter un profil enfant") { editProfile(-1) })
        if (rules.profiles.none { it.unlimited }) Ui.addRow(prof, Ui.row(c, "👑", "Ajouter le profil parent", "Sans limite, protégé par le code") {
            rules.profiles.add(fr.paul.telecommande.shared.Profile(java.util.UUID.randomUUID().toString().take(8), "Parents", "👑", 0, true)); save()
        })
        root.addView(prof, Ui.lp(c, 8))

        // ===== Applis =====
        root.addView(section("Applis de la TV"), Ui.lp(c, 26))
        val list = Ui.group(c)
        val all = (apps + rules.apps.keys.filter { k -> apps.none { it.first == k } }.map { it to it }).distinctBy { it.first }
            .sortedWith(compareBy({ rules.apps[it.first]?.isDefault ?: true }, { it.second.lowercase() }))
        if (all.isEmpty()) Ui.addRow(list, Ui.row(c, "📺", "Liste disponible quand la TV est allumée"))
        all.forEach { (pkg, label) ->
            val r = rules.apps[pkg]
            val icon = runCatching { fr.paul.telecommande.core.AppIcons.icon(c, device.id, pkg) }.getOrNull()
            Ui.addRow(list, Ui.row(c, label.take(1).uppercase(), label, summary(r), danger = r?.blocked == true, icon = icon) { editApp(pkg, label) })
        }
        root.addView(list, Ui.lp(c, 8))

        // ===== Programmes =====
        root.addView(section("Programmes"), Ui.lp(c, 26))
        root.addView(Ui.text(c, "La TV lit la classification (7+, -12, 16+…) au clic sur « Lecture » et au début de la lecture.", 13f, muted = true).apply {
            setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0) }, Ui.lp(c, 4))
        val prog = Ui.group(c)
        val kids = rules.profiles.filter { !it.unlimited }
        Ui.addRow(prog, Ui.row(c, "🎂", "Selon l'âge de chaque enfant",
            if (kids.isEmpty()) "Ajoutez un profil enfant avec son âge" else kids.joinToString(" · ") { "${it.name} : ${it.age()?.let { a -> "jusqu'à $a ans" } ?: "âge à renseigner"}" }) {
            if (kids.isNotEmpty()) editProfile(rules.profiles.indexOf(kids.first()))
        })
        val labels = fr.paul.telecommande.shared.RatingReader.CONTENT_LABELS
        Ui.addRow(prog, Ui.row(c, "⚠️", "Contenus interdits",
            rules.blockedContents.mapNotNull { labels[it] }.joinToString(", ").ifEmpty { "Aucun (l'âge suffit)" }) {
            val keys = labels.keys.toList()
            val checked = keys.map { it in rules.blockedContents }.toBooleanArray()
            androidx.appcompat.app.AlertDialog.Builder(c).setTitle("Interdire quand l'appli signale…")
                .setMultiChoiceItems(keys.map { labels[it] }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                .setPositiveButton("Enregistrer") { _, _ -> rules.blockedContents = keys.filterIndexed { i, _ -> checked[i] }.toMutableList(); save() }
                .setNegativeButton("Annuler", null).show()
        })
        val policies = listOf("ask" to "Demander au parent", "allow" to "Autoriser", "block" to "Bloquer")
        Ui.addRow(prog, Ui.row(c, "❔", "Sans classification", "Films et séries (Netflix, Disney+…)",
            mapOf("ask" to "Demander", "allow" to "Autoriser", "block" to "Bloquer")[rules.unknownPolicy]) {
            androidx.appcompat.app.AlertDialog.Builder(c).setTitle("Film ou série sans classification lisible")
                .setSingleChoiceItems(policies.map { it.second }.toTypedArray(), policies.indexOfFirst { it.first == rules.unknownPolicy }) { d, i ->
                    d.dismiss(); rules.unknownPolicy = policies[i].first; save() }
                .setNegativeButton("Annuler", null).show()
        })
        Ui.addRow(prog, Ui.row(c, "🚫", "Titres et chaînes interdits",
            rules.blockedPrograms.joinToString(", ").ifEmpty { "Aucun · ex. une série, une chaîne YouTube" }) { editWordList(
                "Titres et chaînes interdits", rules.blockedPrograms.map { it to it }) { remove, add ->
                remove?.let { rules.blockedPrograms.remove(it) }; add?.let { if (it !in rules.blockedPrograms) rules.blockedPrograms += it }; save() } })
        Ui.addRow(prog, Ui.row(c, "✅", "Toujours autorisés",
            rules.allowedPrograms.joinToString(", ") { it.title }.ifEmpty { "Aucun · ajoutés quand vous répondez « Toujours »" }) {
            editWordList("Toujours autorisés", rules.allowedPrograms.map { a ->
                a.key to (a.title + "  ·  " + (rules.profiles.firstOrNull { it.id == a.profile }?.name ?: "toute la famille")) }, canAdd = false) { remove, _ ->
                remove?.let { k -> rules.allowedPrograms.removeAll { it.key == k } }; save() } })
        root.addView(prog, Ui.lp(c, 8))

        // ===== Confort =====
        root.addView(section("Confort"), Ui.lp(c, 26))
        val comfort = Ui.group(c)
        Ui.addRow(comfort, Ui.switchRow(c, "🎬", "Finir l'épisode", "Attendre la fin de la vidéo avant de couper", rules.graceMin > 0) { rules.graceMin = if (it) 10 else 0; save() })
        if (rules.graceMin > 0) Ui.addRow(comfort, Ui.row(c, "⏳", "Attente maximale", null, "${rules.graceMin} min") {
            pick("Attente maximale", listOf(10, 15, 20, 30), rules.graceMin) { rules.graceMin = it; save() }
        })
        Ui.addRow(comfort, Ui.switchRow(c, "⌛", "Sablier à l'écran", "Pendant les 15 dernières minutes", rules.showTimer) { rules.showTimer = it; save() })
        Ui.addRow(comfort, Ui.switchRow(c, "💤", "« Vous êtes toujours là ? »", "Puis veille si personne ne répond", rules.stillThereMin > 0) { rules.stillThereMin = if (it) 180 else 0; save() })
        if (rules.stillThereMin > 0) Ui.addRow(comfort, Ui.row(c, "🕒", "Sans toucher la télécommande pendant", null, Usage.format(rules.stillThereMin * 60_000L)) {
            pick("Sans aucune touche pendant", listOf(60, 90, 120, 180, 240), rules.stillThereMin) { rules.stillThereMin = it; save() }
        })
        root.addView(comfort, Ui.lp(c, 8))

        // ===== Sécurité =====
        root.addView(section("Sécurité"), Ui.lp(c, 26))
        val sec = Ui.group(c)
        Ui.addRow(sec, Ui.switchRow(c, "🔒", "Protéger les Paramètres de la TV", "Empêche de couper la protection ou de supprimer l'appli", rules.protectSettings) { rules.protectSettings = it; save() })
        root.addView(sec, Ui.lp(c, 8))
    }

    /** Liste modifiable : toucher un élément propose de le retirer ; « Ajouter » demande un titre ou une chaîne. */
    private fun editWordList(title: String, items: List<Pair<String, String>>, canAdd: Boolean = true, done: (remove: String?, add: String?) -> Unit) {
        val c = this
        val b = androidx.appcompat.app.AlertDialog.Builder(c).setTitle(title)
        if (items.isEmpty()) b.setMessage(if (canAdd) "Rien pour l'instant." else "Rien pour l'instant. Ils s'ajoutent quand vous répondez « Toujours » à une demande.")
        else b.setItems(items.map { "✕  " + it.second }.toTypedArray()) { _, i ->
            androidx.appcompat.app.AlertDialog.Builder(c).setMessage("Retirer « ${items[i].second.substringBefore("  ·  ")} » ?")
                .setPositiveButton("Retirer") { _, _ -> done(items[i].first, null) }.setNegativeButton("Annuler", null).show()
        }
        if (canAdd) b.setPositiveButton("Ajouter") { _, _ ->
            val input = android.widget.EditText(c).apply { hint = "Titre, chaîne ou mot"; inputType = android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
            val frame = android.widget.FrameLayout(c).apply { setPadding(Ui.dp(c, 20), 0, Ui.dp(c, 20), 0); addView(input) }
            androidx.appcompat.app.AlertDialog.Builder(c).setTitle("Interdire").setView(frame)
                .setMessage("Comparé sans accents ni majuscules, par mots entiers : « chat » n'interdit pas « château ».")
                .setPositiveButton("Ajouter") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { done(null, it.take(60)) } }
                .setNegativeButton("Annuler", null).show()
        }
        b.setNegativeButton("Fermer", null).show()
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
