package fr.paul.telecommande.ui

import android.app.Activity
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import fr.paul.telecommande.core.DiagLog
import fr.paul.telecommande.shared.Updater
import kotlin.concurrent.thread

/** Mise à jour de l'appli téléphone depuis telecommande-box.netlify.app (une vérification par jour). */
object PhoneUpdate {
    private var shownThisRun = false

    fun checkAndOffer(a: Activity, force: Boolean = false) {
        if (shownThisRun && !force) return
        val up = Updater(a, "phone")
        thread {
            val r = runCatching { up.check(force) }.getOrNull()
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                if (r == null) { if (force) Ui.toast(a, "L'appli est à jour"); return@runOnUiThread }
                shownThisRun = true
                AlertDialog.Builder(a).setTitle("Mise à jour disponible")
                    .setMessage("Entente ${r.versionName} est disponible." + (if (r.notes.isNotBlank()) "\n\n${r.notes}" else "") +
                        "\n\nVos appareils et réglages sont conservés.")
                    .setPositiveButton("Mettre à jour") { _, _ -> start(a, up, r) }
                    .setNegativeButton("Plus tard", null).show()
            }
        }
    }

    private fun start(a: Activity, up: Updater, r: Updater.Release) {
        if (!up.canInstall()) {
            AlertDialog.Builder(a).setTitle("Autorisation nécessaire (une seule fois)")
                .setMessage("Autorisez « Entente » à installer ses mises à jour, puis revenez dans l'appli.")
                .setPositiveButton("Ouvrir le réglage") { _, _ -> up.openInstallPermission(a); shownThisRun = false }
                .setNegativeButton("Plus tard", null).show()
            return
        }
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; setPadding(Ui.dp(a, 24), Ui.dp(a, 8), Ui.dp(a, 24), 0) }
        val dlg = AlertDialog.Builder(a).setTitle("Téléchargement…").setView(bar).setCancelable(false).show()
        thread {
            runCatching {
                val f = up.download(r) { p -> a.runOnUiThread { bar.progress = p } }
                a.runOnUiThread { dlg.dismiss() }
                DiagLog.log("APP", "Mise à jour ${r.versionName} vérifiée, installation")
                up.install(f)
            }.onFailure { e ->
                DiagLog.log("APP", "Mise à jour impossible : ${e.message}")
                a.runOnUiThread { dlg.dismiss(); Ui.toast(a, "Mise à jour impossible : ${e.message}") }
            }
        }
    }
}
