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
        setContentView(AuroraNav.wrap(this, ScrollView(this).apply { addView(root) }, AuroraNav.Tab.KIDS))
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
        // Tout en tuiles : l'essentiel d'abord, puis chaque appli
        val top = Ui.group(c)
        Ui.addRow(top, Ui.row(c, "✨", "Pas à pas", "Tout régler, une question à la fois") {
            startActivity(android.content.Intent(c, RulesWizardActivity::class.java).putExtra(RulesWizardActivity.EXTRA_ID, device.id))
        })
        Ui.addRow(top, Ui.row(c, "📊", "Ce qui a été regardé", "Aujourd'hui, la semaine, les horaires") {
            startActivity(android.content.Intent(c, StatsActivity::class.java).putExtra(StatsActivity.EXTRA_ID, device.id))
        })
        root.addView(top, Ui.lp(c, 14))
        if (!unlocked) {
            val lock = Ui.group(c)
            Ui.addRow(lock, Ui.row(c, "🔑", "Déverrouiller", "Les réglages sont protégés par le code parent") { unlock() })
            root.addView(lock, Ui.lp(c, 12))
            root.addView(AuroraNav.spacer(c))
            return
        }

        root.addView(Ui.label(c, "L'essentiel"), Ui.lp(c, 22))
        val ess = Ui.group(c)
        Ui.addRow(ess, Ui.switchRow(c, "🛡️", "Contrôle parental", "Le téléphone surveille la box et prévient 5 min avant chaque limite", rules.enabled) { on ->
            if (on && !store.hasPin) Ui.createPin(c) { ok -> if (ok) { rules.enabled = true; askNotifications() }; save() }
            else { rules.enabled = on; if (on) askNotifications(); save() }
        })
        Ui.addRow(ess, Ui.row(c, "⏱️", "Temps de TV par jour", "Une fois atteint, la TV se met en veille",
            if (rules.dailyTotalMin > 0) Usage.format(rules.dailyTotalMin * 60_000L) else "Aucune limite") {
            pickMinutes("Temps de TV par jour", listOf(0, 60, 90, 120, 180, 240, 300), rules.dailyTotalMin) { rules.dailyTotalMin = it; save() }
        })
        Ui.addRow(ess, Ui.switchRow(c, "🌙", "Couvre-feu", "La TV se met en veille si on l'allume", rules.bedtimeEnabled) { rules.bedtimeEnabled = it; save() })
        if (rules.bedtimeEnabled) {
            Ui.addRow(ess, Ui.row(c, "🕘", "Début du couvre-feu", null, ParentalLogic.hhmm(rules.bedtimeStart)) { pickTime(rules.bedtimeStart) { rules.bedtimeStart = it; save() } })
            Ui.addRow(ess, Ui.row(c, "🌅", "Fin du couvre-feu", null, ParentalLogic.hhmm(rules.bedtimeEnd)) { pickTime(rules.bedtimeEnd) { rules.bedtimeEnd = it; save() } })
        }
        root.addView(ess, Ui.lp(c, 8))

        root.addView(Ui.label(c, "Chaque appli, par jour"), Ui.lp(c, 22))
        val apps = Ui.group(c)
        val now = System.currentTimeMillis()
        val seen = UsageDb.get(c).totalsByApp(device.id, Usage.addDays(Usage.startOfDay(now), -30), now).keys
            .filter { AppCatalog.kind(it) != AppCatalog.Kind.HOME && it != "unknown" }
        val today = TvMonitor.todayUsage(c, device.id)
        (defaultApps + seen + rules.perAppMin.keys).distinct().forEach { pkg ->
            val cur = rules.perAppMin[pkg] ?: 0
            Ui.addRow(apps, Ui.row(c, AppCatalog.name(pkg).take(1).uppercase(), AppCatalog.name(pkg),
                today[pkg]?.let { "Aujourd'hui : " + Usage.format(it) } ?: "Pas regardée aujourd'hui",
                if (cur > 0) Usage.format(cur * 60_000L) + " par jour" else "Sans limite") {
                pickMinutes(AppCatalog.name(pkg), listOf(0, 15, 30, 45, 60, 90, 120, 180), cur) { m ->
                    if (m > 0) rules.perAppMin[pkg] = m else rules.perAppMin.remove(pkg)
                    save()
                }
            })
        }
        root.addView(apps, Ui.lp(c, 8))

        root.addView(Ui.label(c, "Sécurité"), Ui.lp(c, 22))
        val sec = Ui.group(c)
        Ui.addRow(sec, Ui.row(c, "🔑", if (store.hasPin) "Changer le code parent" else "Créer le code parent", "Il protège ces réglages") {
            Ui.createPin(c) { render() }
        })
        root.addView(sec, Ui.lp(c, 8))
        root.addView(Ui.text(c,
            "Fonctionne avec les box Android TV / Google TV. Le téléphone doit rester sur le Wi-Fi de la maison ; " +
                "s'il part, les limites ne sont plus appliquées jusqu'à son retour.", 12f, muted = true), Ui.lp(c, 14))
        root.addView(AuroraNav.spacer(c))
    }

    private fun pickMinutes(title: String, options: List<Int>, current: Int, done: (Int) -> Unit) {
        val sheet = TileSheet(this)
        sheet.show(TileSheet.Page("⏱️", title, "Touchez la durée voulue") { sh, col ->
            sh.sections(col, TileSheet.Section(null, options.map { m ->
                TileSheet.Tile("", (if (m == current) "✓ " else "") + (if (m == 0) "Aucune limite" else Usage.format(m * 60_000L)), key = true) { sheet.dismiss(); done(m) }
            }, 2))
        })
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
