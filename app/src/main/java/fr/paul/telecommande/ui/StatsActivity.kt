package fr.paul.telecommande.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import fr.paul.telecommande.R
import fr.paul.telecommande.core.AppCatalog
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.ParentalStore
import fr.paul.telecommande.core.TvMonitor
import fr.paul.telecommande.core.Usage
import fr.paul.telecommande.core.UsageDb
import fr.paul.telecommande.service.AssistantService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Temps d'écran d'une box : aujourd'hui, 7 derniers jours, détail par appli. */
class StatsActivity : AppCompatActivity() {
    companion object { const val EXTRA_ID = "device_id" }

    private lateinit var device: Device
    private lateinit var root: LinearLayout
    private var weekMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = intent.getStringExtra(EXTRA_ID)?.let { DeviceStore(this).get(it) }
        if (d == null) { finish(); return }
        if (d.hasBoxApp) {
            startActivity(Intent(this, BoxDashboardActivity::class.java).putExtra(BoxDashboardActivity.EXTRA_ID, d.id))
            finish(); return
        }
        device = d
        Ui.systemBars(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@StatsActivity, 16); setPadding(p, p, p, p * 2)
        }
        setContentView(AuroraNav.wrap(this, ScrollView(this).apply { addView(root) }, AuroraNav.Tab.ACTIVITY))
        lifecycleScope.launch {
            while (isActive) { render(); delay(30_000) }
        }
        lifecycleScope.launch { TvMonitor.live.collect { render() } }
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        if (!::device.isInitialized) return
        device = DeviceStore(this).get(device.id) ?: return
        root.removeAllViews()
        val c = this
        val rules = ParentalStore(c).rules(device.id)
        val db = UsageDb.get(c)
        val now = System.currentTimeMillis()
        val today = Usage.startOfDay(now)

        root.addView(Ui.text(c, device.name, 13f, muted = true))
        root.addView(Ui.title(c, "Temps d'écran"))

        // --- interrupteur de suivi ---
        val watching = device.monitorEnabled || rules.enabled
        val sw = SwitchCompat(c).apply {
            text = "Suivre en continu, même avec la télécommande d'origine"
            setTextColor(Ui.color(c, R.color.ink)); textSize = 14f
            isChecked = watching
            isEnabled = !rules.enabled
            setOnCheckedChangeListener { b, on ->
                fun apply(v: Boolean) {
                    device.monitorEnabled = v
                    DeviceStore(c).save(device)
                    TvMonitor.sync(c)
                    if (v) AssistantService.start(c)
                    render()
                }
                if (on) apply(true)
                else {
                    b.setOnCheckedChangeListener(null); b.isChecked = true
                    Ui.requirePin(c, "Arrêter le suivi du temps d'écran ?") { apply(false) }
                }
            }
        }
        root.addView(sw, Ui.lp(c, 8))
        if (rules.enabled) root.addView(Ui.text(c, "Le contrôle parental est actif : le suivi reste allumé.", 12f, muted = true))

        val live = TvMonitor.live.value[device.id]
        val liveText = when {
            !watching -> "Suivi désactivé : activez-le pour mesurer le temps passé devant la TV."
            live == null || !live.connected -> "Box injoignable pour l'instant (éteinte ou hors réseau) : nouvel essai automatique."
            !live.on -> "En ce moment : TV en veille"
            else -> "En ce moment : " + AppCatalog.name(live.pkg)
        }
        root.addView(Ui.text(c, liveText, 13f, muted = true), Ui.lp(c, 6))
        live?.lastAction?.takeIf { it.isNotBlank() }?.let { root.addView(Ui.text(c, "Dernière action : $it", 12f, muted = true)) }

        // --- aujourd'hui + 7 jours ---
        val todayMap = db.totalsByApp(device.id, today, now)
        val todayTotal = todayMap.values.sum()
        val card = Ui.card(c)
        card.addView(Ui.label(c, "Aujourd'hui"))
        card.addView(Ui.title(c, Usage.format(todayTotal), 46f))
        if (rules.enabled && rules.dailyTotalMin > 0) card.addView(Ui.text(c, "Limite quotidienne : ${Usage.format(rules.dailyTotalMin * 60_000L)}", 12f, muted = true))
        val days = (6 downTo 0).map { Usage.addDays(today, -it) }
        val dayFmt = SimpleDateFormat("EEE", Locale.FRANCE)
        val chart = BarChartView(c).apply {
            labels = days.map { dayFmt.format(Date(it)).trimEnd('.').replaceFirstChar { ch -> ch.uppercase() } }
            limitMin = if (rules.enabled) rules.dailyTotalMin else 0
            valuesMin = days.map { start ->
                val end = minOf(Usage.addDays(start, 1), now)
                (db.totalsByApp(device.id, start, end).values.sum() / 60_000).toInt()
            }
        }
        card.addView(chart, Ui.lp(c, 12))
        root.addView(card, Ui.lp(c, 14))

        // --- par appli ---
        val tabs = LinearLayout(c)
        tabs.addView(Ui.button(c, "Aujourd'hui", primary = !weekMode) { weekMode = false; render() }, LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f).apply { marginEnd = Ui.dp(c, 6) })
        tabs.addView(Ui.button(c, "7 jours", primary = weekMode) { weekMode = true; render() }, LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f).apply { marginStart = Ui.dp(c, 6) })
        root.addView(tabs, Ui.lp(c, 16))

        val map = if (weekMode) db.totalsByApp(device.id, days.first(), now) else todayMap
        val apps = Ui.card(c)
        val maxV = map.values.maxOrNull() ?: 0L
        if (map.isEmpty()) apps.addView(Ui.text(c, "Pas encore de données sur cette période.", 14f, muted = true))
        val slices = PieChartView.slicesFor(map)
        val colorOf = slices.associate { it.key to it.color }
        val sum = map.values.sum().coerceAtLeast(1L)
        if (map.isNotEmpty()) {
            apps.addView(PieChartView(c).apply {
                this.slices = slices
                centerTitle = Usage.format(map.values.sum())
                centerSub = if (weekMode) "sur 7 jours" else "aujourd'hui"
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(c, 8) })
        }
        map.entries.sortedByDescending { it.value }.forEach { (pkg, ms) ->
            val limit = if (rules.enabled && !weekMode) rules.perAppMin[pkg] ?: 0 else 0
            val over = limit > 0 && ms >= limit * 60_000L
            val row = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8)) }
            val line = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(android.view.View(c).apply {
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(colorOf[pkg] ?: 0xFF9CA3AF.toInt()) }
            }, LinearLayout.LayoutParams(Ui.dp(c, 12), Ui.dp(c, 12)).apply { marginEnd = Ui.dp(c, 10) })
            line.addView(Ui.text(c, AppCatalog.name(pkg), 15f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(Ui.text(c, "${(ms * 100 / sum)} %", 13f, muted = true), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(c, 12) })
            line.addView(Ui.text(c, Usage.format(ms) + if (limit > 0) " / ${Usage.format(limit * 60_000L)}" else "", 14f).apply {
                if (over) setTextColor(Ui.color(c, R.color.danger))
            })
            row.addView(line)
            val ratio = if (limit > 0) ms.toFloat() / (limit * 60_000L) else if (maxV > 0) ms.toFloat() / maxV else 0f
            row.addView(Ui.progress(c, ratio, over), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 6)).apply { topMargin = Ui.dp(c, 6) })
            apps.addView(row)
        }
        root.addView(apps, Ui.lp(c, 10))

        // --- appli Box (premium) ---
        if (device.isSmartBox) {
            val box = Ui.card(c)
            box.addView(Ui.text(c, "Aller plus loin : l'appli Box", 16f))
            box.addView(Ui.text(c,
                "Installée sur la TV, elle mesure tout même téléphone éteint, bloque les applis directement à l'écran, " +
                    "affiche le compte à rebours et permet à l'enfant de demander du temps.", 12f, muted = true), Ui.lp(c, 4))
            box.addView(Ui.button(c, "Installer l'appli Box sur la TV", primary = true) {
                startActivity(Intent(c, InstallBoxActivity::class.java).putExtra(InstallBoxActivity.EXTRA_ID, device.id))
            }, Ui.lp(c, 10))
            box.addView(Ui.button(c, "Déjà installée : appairer") {
                BoxPairing.start(c, lifecycleScope, device) {
                    startActivity(Intent(c, BoxDashboardActivity::class.java).putExtra(BoxDashboardActivity.EXTRA_ID, device.id)); finish()
                }
            }, Ui.lp(c, 10))
            root.addView(box, Ui.lp(c, 16))
        }

        // --- actions ---
        root.addView(Ui.button(c, if (rules.enabled) "Contrôle parental (actif)" else "Configurer le contrôle parental, pas à pas", primary = true) {
            if (rules.enabled) startActivity(Intent(c, ParentalActivity::class.java).putExtra(ParentalActivity.EXTRA_ID, device.id))
            else startActivity(Intent(c, RulesWizardActivity::class.java).putExtra(RulesWizardActivity.EXTRA_ID, device.id))
        }, Ui.lp(c, 16))
        if (Build.VERSION.SDK_INT >= 23) root.addView(Ui.button(c, "Éviter les coupures du suivi (batterie)") {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            Ui.toast(c, "Choisissez « Entente » puis « Ne pas optimiser »")
        }, Ui.lp(c, 10))

        val since = db.firstRecord(device.id)
        root.addView(Ui.text(c,
            (if (since != null) "Mesuré depuis le " + DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.FRANCE).format(Date(since)) + ". " else "") +
                "L'historique antérieur à l'activation n'est pas transmis par la box. Le suivi fonctionne quand ce téléphone est sur le même Wi-Fi que la box. Les données restent sur le téléphone.",
            12f, muted = true), Ui.lp(c, 14))
        root.addView(AuroraNav.spacer(c))
    }
}
