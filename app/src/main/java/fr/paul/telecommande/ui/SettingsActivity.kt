package fr.paul.telecommande.ui

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import fr.paul.telecommande.BuildConfig
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.ParentalStore

/**
 * Onglet « Réglages » : un vrai écran (avant, une simple liste dans une fenêtre), rangé en trois parties simples :
 * mes écrans (TV principale, ajouter, modifier), les enfants (code parent, règles), et l'appli (mise à jour, aide, version).
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.systemBars(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(AuroraNav.wrap(this, ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(root) }, AuroraNav.Tab.SETTINGS))
        Premium.darkStatusBar(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) render()
    }

    private fun render() {
        val c = this
        root.removeAllViews()
        val h = Premium.hero(c)
        h.addView(Premium.heroText(c, "Réglages", 28f, bold = true), Ui.lp(c, 8))
        h.addView(Premium.heroText(c, "Vos écrans, le code parent et l'appli.", 14.5f, soft = true), Ui.lp(c, 6))
        root.addView(h, LinearLayout.LayoutParams(-1, -2))
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(c, 16); setPadding(p, 0, p, 0) }
        root.addView(body, LinearLayout.LayoutParams(-1, -2))

        // ----- Mes écrans -----
        val store = DeviceStore(c)
        val devices = store.all()
        val main = AuroraNav.primary(c)
        body.addView(Ui.groupTitle(c, "Mes écrans"), Ui.lp(c, 22))
        body.addView(Ui.text(c, "La TV principale est celle que la touche Télécommande ouvre directement.", 13f, muted = true), Ui.lp(c, 4))
        val screens = Ui.group(c)
        devices.forEach { d ->
            val sub = listOfNotNull(d.type.label.substringBefore(" ("), if (d.hasBoxApp) "appli TV installée" else null).joinToString(" · ")
            Ui.addRow(screens, Ui.row(c, "📺", d.name, sub, value = if (d.id == main?.id) "Principale" else null) { deviceMenu(d) })
        }
        Ui.addRow(screens, Ui.row(c, "➕", "Ajouter un écran", "TV, box ou appareil infrarouge : l'appli cherche toute seule sur le Wi-Fi") {
            startActivity(Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_ADD, true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        })
        if (devices.size > 1) Ui.addRow(screens, Ui.row(c, "🏠", "Toutes mes TV en un coup d'œil", "Ce qui passe sur chaque écran") {
            startActivity(Intent(c, HomeOverviewActivity::class.java))
        })
        body.addView(screens, Ui.lp(c, 10))

        // ----- Enfants -----
        val parental = ParentalStore(c)
        body.addView(Ui.groupTitle(c, "Enfants"), Ui.lp(c, 26))
        val kids = Ui.group(c)
        Ui.addRow(kids, Ui.row(c, "🔑", if (parental.hasPin) "Changer le code parent" else "Créer le code parent",
            "Il protège les règles : un enfant ne peut pas les changer") {
            if (parental.hasPin) Ui.requirePin(c, "Code actuel, pour en choisir un nouveau.") { Ui.createPin(c) { render() } }
            else Ui.createPin(c) { render() }
        })
        Ui.addRow(kids, Ui.row(c, "🛡️", "Règles et contrats des enfants", "Ouvre l'onglet Enfants") { AuroraNav.open(c, AuroraNav.Tab.KIDS) })
        body.addView(kids, Ui.lp(c, 10))

        // ----- L'appli -----
        body.addView(Ui.groupTitle(c, "L'appli"), Ui.lp(c, 26))
        val app = Ui.group(c)
        Ui.addRow(app, Ui.row(c, "⬇️", "Rechercher une mise à jour", "Version ${BuildConfig.VERSION_NAME}") { PhoneUpdate.checkAndOffer(c, force = true) })
        Ui.addRow(app, Ui.row(c, "🧭", "Revoir la visite guidée", "Les 4 choses à savoir, en 30 secondes") { Tour.show(c, force = true) })
        Ui.addRow(app, Ui.row(c, "❓", "Aide", "Les réponses aux questions courantes") { AuroraNav.open(c, AuroraNav.Tab.HELP) })
        Ui.addRow(app, Ui.row(c, "🔒", "Vos données restent chez vous",
            "Aucun compte, aucun serveur : tout reste sur ce téléphone et sur vos TV, échangé en chiffré."))
        if (AdminMenu.enabled(c)) Ui.addRow(app, Ui.row(c, "🛠️", "Administrateur", "Outils avancés") { AdminMenu.open(c, main) })
        body.addView(app, Ui.lp(c, 10))
        body.addView(AuroraNav.spacer(c))
    }

    /** Un écran de la liste : en faire la TV principale, ouvrir sa télécommande, ou le modifier / supprimer. */
    private fun deviceMenu(d: Device) {
        val items = listOf(
            "Ouvrir sa télécommande" to {
                AuroraNav.setPrimary(this, d)
                startActivity(Intent(this, RemoteActivity::class.java).putExtra(RemoteActivity.EXTRA_ID, d.id))
            },
            "En faire la TV principale" to { AuroraNav.setPrimary(this, d); Ui.toast(this, "${d.name} est la TV principale"); render() },
            "Modifier, réappairer ou supprimer" to {
                startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_EDIT, d.id)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        )
        val icons = listOf("🎮" to TileSheet.MINT, "⭐" to TileSheet.AMBER, "✏️" to TileSheet.INDIGO)
        val sheet = TileSheet(this)
        sheet.show(TileSheet.Page("📺", d.name, d.type.label.substringBefore(" (")) { sh, col ->
            sh.sections(col, TileSheet.Section(null, items.mapIndexed { i, (label, action) ->
                TileSheet.Tile(icons[i].first, label, null, icons[i].second) { sheet.dismiss(); action() }
            }))
        })
    }
}
