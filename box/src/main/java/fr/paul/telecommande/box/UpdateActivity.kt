package fr.paul.telecommande.box

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import fr.paul.telecommande.shared.Updater
import kotlin.concurrent.thread

/**
 * Écran de mise à jour sur la TV : « Installer maintenant » / « Plus tard ».
 * Ouvert par la protection quand la TV est sur l'accueil (jamais pendant un programme), ou depuis l'appli.
 */
class UpdateActivity : Activity() {
    companion object { const val EXTRA_FORCE = "force" }

    private lateinit var updater: Updater
    private lateinit var root: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()
    private var release: Updater.Release? = null
    private var state = "checking"          // checking, none, ready, permission, downloading, installing, error
    private var progress = 0
    private var error = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updater = Updater(this, "box")
        root = BoxUi.card(this, 56, 44)
        root.minimumWidth = dp(640)
        setContentView(BoxUi.Fit(this).apply { background = BoxUi.screen(this@UpdateActivity); addView(root) })
        val force = intent.getBooleanExtra(EXTRA_FORCE, false)
        thread { val r = runCatching { updater.check(force) }.getOrNull(); main.post { release = r; state = if (r == null) "none" else "ready"; render() } }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (state == "permission" && updater.canInstall()) startDownload()
    }

    private fun text(t: String, size: Float, color: Int = BoxUi.INK, bold: Boolean = false) = BoxUi.text(this, t, size, color, bold)

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = BoxUi.button(this, label, primary, onClick = onClick)

    private fun render() {
        root.removeAllViews()
        root.addView(BoxUi.badge(this, "↑", false, 72f).apply { textSize = 30f })
        root.addView(android.widget.Space(this), LinearLayout.LayoutParams(1, dp(18)))
        val r = release
        when (state) {
            "checking" -> { root.addView(text("Recherche de mise à jour…", 26f, bold = true)); root.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(20) }) }
            "none" -> {
                root.addView(text("Télécommande Box est à jour", 26f, bold = true))
                root.addView(text("Version ${versionName()}", 16f, BoxUi.SOFT), lp(8))
                addButtons(button("Fermer", true) { finish() })
            }
            "ready" -> {
                root.addView(text("Mise à jour disponible", 28f, bold = true))
                root.addView(text("Télécommande Box ${versionName()} → ${r?.versionName}", 18f, BoxUi.SOFT), lp(8))
                r?.notes?.takeIf { it.isNotBlank() }?.let { root.addView(text(it, 16f, BoxUi.SOFT), lp(16)) }
                root.addView(text("Vos réglages et la liaison avec le téléphone sont conservés.", 14f, BoxUi.MUTED), lp(16))
                addButtons(button("Installer maintenant", true) { startDownload() }, button("Plus tard", false) { finish() })
            }
            "permission" -> {
                root.addView(text("Une autorisation est nécessaire (une seule fois)", 24f, bold = true))
                root.addView(text("Autorisez « Télécommande Box » à installer ses mises à jour, puis revenez avec ↩.\n\n" +
                    "Si le réglage ne s'ouvre pas : Paramètres › Applications › Accès spécifiques des applications › " +
                    "Installer des applis inconnues › Télécommande Box › Autoriser.", 16f, BoxUi.SOFT), lp(16))
                addButtons(button("Ouvrir le réglage", true) { updater.openInstallPermission(this) }, button("Plus tard", false) { finish() })
            }
            "downloading" -> {
                root.addView(text("Téléchargement… $progress %", 26f, bold = true))
                root.addView(BoxUi.progress(this, progress / 100f), LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(24) })
            }
            "installing" -> {
                root.addView(text("Choisissez « Installer » dans la fenêtre d'Android", 24f, bold = true))
                root.addView(text("L'appli redémarre ensuite toute seule avec la nouvelle version.", 16f, BoxUi.SOFT), lp(8))
                addButtons(button("Fermer", false) { finish() })
            }
            "error" -> {
                root.addView(text("La mise à jour n'a pas pu se faire", 24f, bold = true))
                root.addView(text(error, 16f, BoxUi.DANGER), lp(8))
                addButtons(button("Réessayer", true) { startDownload() }, button("Fermer", false) { finish() })
            }
        }
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun addButtons(vararg b: Button) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        b.forEachIndexed { i, btn -> row.addView(btn, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(16) }) }
        root.addView(row, lp(32))
        b.first().requestFocus()
    }

    private fun versionName() = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("?")

    private fun startDownload() {
        val r = release ?: return
        if (!updater.canInstall()) { state = "permission"; render(); if (!updater.openInstallPermission(this)) render(); return }
        state = "downloading"; progress = 0; render()
        thread {
            runCatching {
                val f = updater.download(r) { p -> if (p != progress) { progress = p; main.post { if (state == "downloading") render() } } }
                main.post { state = "installing"; render() }
                GuardService.instance?.allowSelfUpdate()
                updater.install(f)
            }.onFailure { e -> main.post { state = "error"; error = e.message ?: "Erreur inconnue"; render() } }
        }
    }
}
