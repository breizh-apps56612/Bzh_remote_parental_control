package fr.paul.telecommande.ui

import android.content.Context
import android.content.Intent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import fr.paul.telecommande.R
import fr.paul.telecommande.core.DiagLog
import fr.paul.telecommande.core.Device

/**
 * Menu caché « Administrateur » : toutes les options avancées que l'interface simplifiée ne montre pas
 * (journaux, diagnostics, adresse, réglages fins, outils de la TV).
 *
 * Il se débloque en touchant 7 fois la salutation de l'accueil (« Bonjour » / « Bonsoir »), code parent demandé.
 * Une fois débloqué, il apparaît dans Réglages et dans « Plus » de la télécommande, jusqu'à « Masquer ce menu ».
 */
object AdminMenu {
    private const val PREFS = "ui"
    private const val KEY = "admin_menu"
    const val TAPS = 7

    fun enabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    private fun setEnabled(c: Context, on: Boolean) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()

    private var taps = 0
    private var lastTap = 0L

    /** À brancher sur un élément discret : 7 touches rapprochées débloquent le menu (code parent demandé). */
    fun secretTap(a: AppCompatActivity) {
        val now = System.currentTimeMillis()
        taps = if (now - lastTap < 3000) taps + 1 else 1
        lastTap = now
        if (enabled(a)) { if (taps >= TAPS) { taps = 0; open(a, AuroraNav.primary(a)) }; return }
        val left = TAPS - taps
        when {
            left <= 0 -> {
                taps = 0
                Ui.requirePin(a, "Code parent pour activer le menu Administrateur.") {
                    setEnabled(a, true)
                    DiagLog.log("APP", "Menu Administrateur activé")
                    Ui.toast(a, "Menu Administrateur activé : Réglages › Administrateur")
                    open(a, AuroraNav.primary(a))
                }
            }
            left <= 3 -> Ui.toast(a, "Encore $left touche" + if (left > 1) "s" else "")
        }
    }

    /** Options qui n'existent que dans la télécommande ouverte (connexion en cours). */
    class RemoteTools(
        val diagnostic: () -> Unit,
        val editHost: () -> Unit,
        val replayVoice: (() -> Unit)?
    )

    /** Ouvre le menu (code parent demandé s'il existe). */
    fun open(a: AppCompatActivity, device: Device?, remote: RemoteTools? = null) {
        Ui.requirePin(a, "Code parent pour ouvrir le menu Administrateur.") { show(a, device, remote) }
    }

    private fun show(a: AppCompatActivity, device: Device?, remote: RemoteTools?) {
        val c: Context = a
        var dialog: AlertDialog? = null
        val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 16), Ui.dp(c, 4), Ui.dp(c, 16), Ui.dp(c, 12)) }
        fun section(t: String) = root.addView(Ui.label(c, t).apply { setPadding(Ui.dp(c, 6), 0, 0, 0) }, Ui.lp(c, 16))
        fun group(vararg rows: Triple<String, String, String?>, actions: List<() -> Unit>) {
            val g = Ui.group(c)
            rows.forEachIndexed { i, (emoji, title, sub) ->
                Ui.addRow(g, Ui.row(c, emoji, title, sub) { dialog?.dismiss(); actions[i]() })
            }
            root.addView(g, Ui.lp(c, 6))
        }
        val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"
        root.addView(Ui.text(c, "Entente $version · " + (device?.let { "${it.name} (${it.type.label.substringBefore(" (")}${if (it.host.isNotBlank()) " · " + it.host else ""})" } ?: "aucun appareil"), 12.5f, muted = true)
            .apply { setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0) })

        // ----- Journaux -----
        section("Journaux")
        val kb = DiagLog.size() / 1024
        group(
            Triple("📤", "Envoyer le journal", "Téléphone + TV, par mail, messagerie…"),
            Triple("🤖", "Analyser le journal avec Claude", "Ouvre l'appli Claude avec le journal"),
            Triple("📄", "Voir le journal", "Les 300 dernières lignes · $kb Ko au total"),
            Triple("🧹", "Effacer le journal", null),
            actions = listOf(
                { DiagLog.export(a) },
                { DiagLog.export(a, toClaude = true) },
                { showLog(a) },
                { DiagLog.clear(); Ui.toast(a, "Journal effacé") }
            )
        )

        // ----- Connexion -----
        if (remote != null) {
            section("Connexion à ${device?.name ?: "l'appareil"}")
            val rows = mutableListOf(
                Triple("🩺", "Diagnostic de connexion", "Ports, appairage, réponses de la TV"),
                Triple("🌐", "Modifier l'adresse de l'appareil", device?.host?.ifBlank { null } ?: "Adresse IP")
            )
            val acts = mutableListOf(remote.diagnostic, remote.editHost)
            remote.replayVoice?.let { rows += Triple("🎙️", "Réécouter la dernière recherche vocale", "Ce que la TV a reçu"); acts += it }
            group(*rows.toTypedArray(), actions = acts)
        } else if (device != null) {
            section("Connexion")
            group(Triple("🩺", "Diagnostic, adresse, voix", "Ouvre la télécommande de ${device.name} avec ce menu"),
                actions = listOf({ a.startActivity(Intent(a, RemoteActivity::class.java).putExtra(RemoteActivity.EXTRA_ID, device.id).putExtra(RemoteActivity.EXTRA_ADMIN, true)) }))
        }

        // ----- Réglages fins -----
        section("Réglages fins")
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val voicePhone = prefs.getBoolean("voice_phone", false)
        val quick = fr.paul.telecommande.service.ControlNotification.enabled(c)
        group(
            Triple("🗣️", "Recherche vocale : " + if (voicePhone) "par le téléphone" else "par la TV", "Toucher pour changer"),
            Triple("⚡", "Télécommande dans les réglages rapides : " + if (quick) "oui" else "non", "Toucher pour changer"),
            actions = listOf(
                {
                    prefs.edit().putBoolean("voice_phone", !voicePhone).apply()
                    Ui.toast(a, if (!voicePhone) "Le téléphone reconnaît la voix et tape la recherche sur la TV" else "La voix est envoyée à l'Assistant de la TV")
                },
                {
                    if (quick) { fr.paul.telecommande.service.ControlNotification.setEnabled(c, false); Ui.toast(a, "Retirée des réglages rapides") }
                    else {
                        fr.paul.telecommande.service.ControlNotification.setEnabled(c, true)
                        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            androidx.core.app.ActivityCompat.requestPermissions(a, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 12)
                        device?.let { fr.paul.telecommande.service.ControlNotification.show(c, it.id) }
                        Ui.toast(a, "Télécommande ajoutée au lecteur des réglages rapides")
                    }
                }
            )
        )

        // ----- Appli Box sur la TV -----
        if (device != null && device.hasBoxApp) {
            section("Appli Box de ${device.name}")
            group(
                Triple("🩺", "Santé de la TV", "Mémoire, stockage, connexion, événements"),
                Triple("🎬", "Lecture des programmes", "Ce que la TV sait lire des films et séries"),
                Triple("🛡️", "Toutes les règles", "Profils, applis, horaires, programmes"),
                Triple("✨", "Assistant pas à pas", null),
                Triple("🏠", "Ma maison : toutes les TV", null),
                actions = listOf(
                    { BoxActions.diagnostics(a, device) },
                    { BoxActions.programReport(a, device) },
                    { a.startActivity(Intent(a, BoxRulesActivity::class.java).putExtra(BoxRulesActivity.EXTRA_ID, device.id)) },
                    { a.startActivity(Intent(a, RulesWizardActivity::class.java).putExtra(RulesWizardActivity.EXTRA_ID, device.id)) },
                    { a.startActivity(Intent(a, HomeOverviewActivity::class.java)) }
                )
            )
        } else if (device != null && device.isSmartBox) {
            section("Box de ${device.name}")
            group(
                Triple("📊", "Temps d'écran (suivi du téléphone)", null),
                Triple("📦", "Installer l'appli Box sur la TV", null),
                actions = listOf(
                    { a.startActivity(Intent(a, StatsActivity::class.java).putExtra(StatsActivity.EXTRA_ID, device.id)) },
                    { a.startActivity(Intent(a, InstallBoxActivity::class.java).putExtra(InstallBoxActivity.EXTRA_ID, device.id)) }
                )
            )
        }

        // ----- Atelier : émulateurs en direct sur GitHub (easter egg) -----
        section("Atelier")
        group(Triple("🧪", "Émulateur en direct", "Téléphone + TV virtuels dans le navigateur, lancés sur GitHub"),
            actions = listOf({ EmulatorLive.open(a) }))

        // ----- Appli -----
        section("Appli")
        group(
            Triple("⬆️", "Rechercher une mise à jour", "Version installée : $version"),
            Triple("🙈", "Masquer ce menu", "7 touches sur « Bonjour » pour le retrouver"),
            actions = listOf(
                { PhoneUpdate.checkAndOffer(a, force = true) },
                { setEnabled(c, false); Ui.toast(a, "Menu Administrateur masqué") }
            )
        )

        dialog = AlertDialog.Builder(a).setTitle("Administrateur")
            .setView(ScrollView(c).apply { addView(root) })
            .setNegativeButton("Fermer", null).show()
    }

    /** Affiche le journal du téléphone, avec un bouton pour l'envoyer. */
    private fun showLog(a: AppCompatActivity) {
        val text = DiagLog.tail(300).ifBlank { "Le journal est vide." }
        val tv = TextView(a).apply {
            this.text = text; textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Ui.color(a, R.color.ink)); setTextIsSelectable(true)
            val p = Ui.dp(a, 16); setPadding(p, Ui.dp(a, 4), p, p)
        }
        val scroll = ScrollView(a).apply { addView(tv) }
        AlertDialog.Builder(a).setTitle("Journal du téléphone").setView(scroll)
            .setPositiveButton("Envoyer") { _, _ -> DiagLog.export(a) }
            .setNegativeButton("Fermer", null).show()
        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}
