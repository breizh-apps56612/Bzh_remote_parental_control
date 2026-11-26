package fr.paul.telecommande.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import fr.paul.telecommande.R
import fr.paul.telecommande.core.AppCatalog
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.ParentalLogic
import fr.paul.telecommande.core.ParentalRules
import fr.paul.telecommande.core.ParentalStore
import fr.paul.telecommande.core.TvMonitor
import fr.paul.telecommande.core.Usage
import fr.paul.telecommande.core.UsageDb
import fr.paul.telecommande.service.AssistantService

/** Réglages du contrôle parental d'une box Android TV, protégés par le code parent. */
class ParentalActivity : AppCompatActivity() {
    companion object { const val EXTRA_ID = "device_id" }

    private lateinit var device: Device
    private lateinit var store: ParentalStore
    private lateinit var rules: ParentalRules
    private lateinit var root: LinearLayout
    private var unlocked = false

    private val defaultApps = listOf(
        "com.google.android.youtube.tv", "com.google.android.youtube.tvkids", "com.netflix.ninja",
        "com.disney.disneyplus", "com.amazon.amazonvideo.livingroom", "com.canal.android.canal", "com.twitch.android.app"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = intent.getStringExtra(EXTRA_ID)?.let { DeviceStore(this).get(it) }
        if (d == null) { finish(); return }
        if (d.hasBoxApp) {
            startActivity(android.content.Intent(this, BoxRulesActivity::class.java).putExtra(BoxRulesActivity.EXTRA_ID, d.id))
            finish(); return
        }
        device = d
        Ui.systemBars(this)
        store = ParentalStore(this)
        rules = store.rules(device.id)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@ParentalActivity, 16); setPadding(p, p, p, p * 2)
        }
        setContentView(AuroraNav.wrap(this, ScrollView(this).apply { addView(root) }, AuroraNav.Tab.PARENTAL))
        unlocked = !store.hasPin
        render()
        if (!unlocked) unlock()
    }

    private fun unlock() = Ui.requirePin(this, "Saisissez le code parent pour modifier les réglages.") { unlocked = true; render() }

    private fun save() {
        store.save(device.id, rules)
        TvMonitor.sync(this)
        if (rules.enabled) AssistantService.start(this)
        render()
    }

    private fun render() {
        val c = this
        root.removeAllViews()
        root.addView(Ui.text(c, device.name, 13f, muted = true))
        root.addView(Ui.title(c, "Contrôle parental"))
        root.addView(Ui.button(c, "✨ Assistant pas à pas", primary = true) {
            startActivity(android.content.Intent(c, RulesWizardActivity::class.java).putExtra(RulesWizardActivity.EXTRA_ID, device.id))
        }, Ui.lp(c, 12))
        if (!unlocked) {
            root.addView(Ui.text(c, "Réglages protégés par le code parent.", 14f, muted = true), Ui.lp(c, 10))
            root.addView(Ui.button(c, "Déverrouiller", primary = true) { unlock() }, Ui.lp(c, 14))
            return
        }

        // --- activation ---
        val main = Ui.card(c)
        main.addView(SwitchCompat(c).apply {
            text = "Activer le contrôle parental"; textSize = 16f; setTextColor(Ui.color(c, R.color.ink))
            isChecked = rules.enabled
            setOnCheckedChangeListener { b, on ->
                if (on && !store.hasPin) {
                    b.setOnCheckedChangeListener(null); b.isChecked = false
                    Ui.createPin(c) { ok -> if (ok) { rules.enabled = true; askNotifications(); save() } }
                } else { rules.enabled = on; if (on) askNotifications(); save() }
            }
        })
        main.addView(Ui.text(c,
            "Le téléphone surveille la box en permanence (même avec la télécommande d'origine) et vous prévient 5 minutes avant chaque limite.",
            12f, muted = true), Ui.lp(c, 6))
        root.addView(main, Ui.lp(c, 14))

        // --- temps total ---
        val total = Ui.card(c)
        total.addView(Ui.text(c, "Temps de TV par jour", 16f))
        total.addView(Ui.text(c, "Une fois atteint, la TV est mise en veille.", 12f, muted = true))
        total.addView(Ui.button(c, if (rules.dailyTotalMin > 0) Usage.format(rules.dailyTotalMin * 60_000L) else "Aucune limite") {
            pickMinutes("Temps de TV par jour", listOf(0, 60, 90, 120, 180, 240, 300), rules.dailyTotalMin) { rules.dailyTotalMin = it; save() }
        }, Ui.lp(c, 10))
        root.addView(total, Ui.lp(c, 10))

        // --- couvre-feu ---
        val bed = Ui.card(c)
        bed.addView(SwitchCompat(c).apply {
            text = "Couvre-feu"; textSize = 16f; setTextColor(Ui.color(c, R.color.ink))
            isChecked = rules.bedtimeEnabled
            setOnCheckedChangeListener { _, on -> rules.bedtimeEnabled = on; save() }
        })
        bed.addView(Ui.text(c, "Pendant ce créneau, la TV est mise en veille si quelqu'un l'allume.", 12f, muted = true))
        val times = LinearLayout(c)
        times.addView(Ui.button(c, "De " + ParentalLogic.hhmm(rules.bedtimeStart)) {
            pickTime(rules.bedtimeStart) { rules.bedtimeStart = it; save() }
        }, LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f).apply { marginEnd = Ui.dp(c, 6) })
        times.addView(Ui.button(c, "à " + ParentalLogic.hhmm(rules.bedtimeEnd)) {
            pickTime(rules.bedtimeEnd) { rules.bedtimeEnd = it; save() }
        }, LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f).apply { marginStart = Ui.dp(c, 6) })
        bed.addView(times, Ui.lp(c, 10))
        root.addView(bed, Ui.lp(c, 10))

        // --- limites par appli ---
        val apps = Ui.card(c)
        apps.addView(Ui.text(c, "Limites par appli (par jour)", 16f))
        apps.addView(Ui.text(c, "À la limite, la box revient à l'accueil, et à chaque nouvelle ouverture de l'appli.", 12f, muted = true))
        val now = System.currentTimeMillis()
        val seen = UsageDb.get(c).totalsByApp(device.id, Usage.addDays(Usage.startOfDay(now), -30), now).keys
            .filter { AppCatalog.kind(it) != AppCatalog.Kind.HOME && it != "unknown" }
        val today = TvMonitor.todayUsage(c, device.id)
        (defaultApps + seen + rules.perAppMin.keys).distinct().forEach { pkg ->
            val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6)) }
            val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(Ui.text(c, AppCatalog.name(pkg), 15f))
            today[pkg]?.let { texts.addView(Ui.text(c, "Aujourd'hui : " + Usage.format(it), 12f, muted = true)) }
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val cur = rules.perAppMin[pkg] ?: 0
            row.addView(Ui.button(c, if (cur > 0) Usage.format(cur * 60_000L) else "Aucune", primary = cur > 0) {
                pickMinutes(AppCatalog.name(pkg), listOf(0, 15, 30, 45, 60, 90, 120, 180), cur) { m ->
                    if (m > 0) rules.perAppMin[pkg] = m else rules.perAppMin.remove(pkg)
                    save()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(c, 44)))
            apps.addView(row)
        }
        root.addView(apps, Ui.lp(c, 10))

        // --- code ---
        root.addView(Ui.button(c, if (store.hasPin) "Changer le code parent" else "Créer le code parent") {
            Ui.createPin(c) { render() }
        }, Ui.lp(c, 16))
        root.addView(Ui.text(c,
            "Fonctionne avec les box Android TV / Google TV. Le téléphone doit rester sur le Wi-Fi de la maison ; " +
                "s'il part, les limites ne sont plus appliquées jusqu'à son retour.", 12f, muted = true), Ui.lp(c, 12))
        root.addView(AuroraNav.spacer(c))
    }

    private fun pickMinutes(title: String, options: List<Int>, current: Int, done: (Int) -> Unit) {
        val labels = options.map { if (it == 0) "Aucune limite" else Usage.format(it * 60_000L) }.toTypedArray()
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels, options.indexOf(current).coerceAtLeast(0)) { dlg, i -> dlg.dismiss(); done(options[i]) }
            .setNegativeButton("Annuler", null).show()
    }

    private fun pickTime(minutes: Int, done: (Int) -> Unit) {
        Ui.pickTime(this, "Choisir l'heure", minutes, done)
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        }
    }
}
