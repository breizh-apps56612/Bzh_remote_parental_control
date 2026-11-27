package fr.paul.telecommande.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import fr.paul.telecommande.R

/**
 * Visite guidée : quatre écrans, à la première ouverture (et depuis Réglages ou Aide), pour qu'une personne
 * qui n'a jamais vu l'appli sache où tout se trouve. Toujours « Passer » possible.
 */
object Tour {
    private const val PREFS = "ui"
    private const val KEY = "tour_2_5_seen"

    private class Step(val icon: Int, val title: String, val text: String)

    private val STEPS = listOf(
        Step(R.drawable.ic_house, "Bienvenue",
            "Cette appli fait deux choses : elle remplace la télécommande de votre TV, et elle vous aide à fixer les règles de TV de vos enfants."),
        Step(R.drawable.ic_remote, "La télécommande",
            "Le carré sombre au milieu, en bas, ouvre la télécommande. Elle est sombre, comme une vraie : ses touches « Retour TV » et « Accueil TV » agissent sur la TV. Pour revenir à l'appli, la flèche vers le bas, en haut à gauche."),
        Step(R.drawable.ic_shield, "Enfants",
            "Le temps de TV de chaque enfant, ses règles (son contrat), les bonus et ce qu'il a regardé. Tout est protégé par le code parent."),
        Step(R.drawable.ic_help, "Aide et Réglages",
            "Une question ? L'onglet Aide répond simplement. Vos écrans, la TV principale et le code parent sont dans Réglages.")
    )

    fun show(a: Activity, force: Boolean = false) {
        val prefs = a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        if (!force && prefs.getBoolean(KEY, false)) return
        if (a.isFinishing) return
        prefs.edit().putBoolean(KEY, true).apply()

        val c = a
        val dialog = Dialog(c, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var index = 0

        val rootView = FrameLayout(c).apply { background = Premium.heroBackground(c) }
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(c, 28), Ui.dp(c, 56), Ui.dp(c, 28), Ui.dp(c, 28))
        }
        rootView.addView(col, FrameLayout.LayoutParams(-1, -1))

        val skip = TextView(c).apply {
            text = "Passer"; textSize = 15f; typeface = Ui.medium; setTextColor(Ui.color(c, R.color.hero_soft))
            setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12))
            isClickable = true; isFocusable = true; setOnClickListener { dialog.dismiss() }
        }
        col.addView(skip, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })

        val stage = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        val badge = FrameLayout(c).apply {
            background = GradientDrawable().apply { cornerRadius = Ui.dp(c, 34).toFloat(); setColor(Ui.color(c, R.color.hero_bg)); setStroke(Ui.dp(c, 2), 0x6634F5C5) }
        }
        val icon = ImageView(c).apply { imageTintList = ColorStateList.valueOf(Ui.color(c, R.color.mint)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        badge.addView(icon, FrameLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44), Gravity.CENTER))
        stage.addView(View(c), LinearLayout.LayoutParams(1, 0, 1f))
        stage.addView(badge, LinearLayout.LayoutParams(Ui.dp(c, 104), Ui.dp(c, 104)))
        val title = Premium.heroText(c, "", 30f, bold = true).apply { gravity = Gravity.CENTER }
        stage.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(c, 28) })
        val body = Premium.heroText(c, "", 17f, soft = true).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f) }
        stage.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(c, 14) })
        stage.addView(View(c), LinearLayout.LayoutParams(1, 0, 1f))

        val dots = LinearLayout(c).apply { gravity = Gravity.CENTER }
        col.addView(dots, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(c, 22) })
        lateinit var next: TextView
        next = Premium.heroButton(c, "Suivant", true) {
            if (index < STEPS.lastIndex) { index++; showStep(c, STEPS[index], index, badge, icon, title, body, dots, next, true) } else dialog.dismiss()
        }
        col.addView(next, LinearLayout.LayoutParams(-1, Ui.dp(c, 56)))

        showStep(c, STEPS[0], 0, badge, icon, title, body, dots, next, false)
        dialog.setContentView(rootView, ViewGroup.LayoutParams(-1, -1))
        dialog.setOnShowListener { Motion.pop(badge, 80) }
        dialog.show()
    }

    private fun showStep(c: Activity, s: Step, i: Int, badge: View, icon: ImageView, title: TextView, body: TextView, dots: LinearLayout, next: TextView, animate: Boolean) {
        val apply = {
            icon.setImageResource(s.icon); title.text = s.title; body.text = s.text
            next.text = if (i == STEPS.lastIndex) "C'est parti" else "Suivant"
            dots.removeAllViews()
            STEPS.indices.forEach { k ->
                dots.addView(View(c).apply {
                    background = GradientDrawable().apply { cornerRadius = 99f; setColor(if (k == i) Ui.color(c, R.color.mint) else 0x40FFFFFF) }
                }, LinearLayout.LayoutParams(Ui.dp(c, if (k == i) 22 else 8), Ui.dp(c, 8)).apply { marginStart = Ui.dp(c, 4); marginEnd = Ui.dp(c, 4) })
            }
            title.announceForAccessibility("${s.title}. ${s.text}")
        }
        if (!animate || !Motion.enabled(c)) { apply(); return }
        listOf<View>(badge, title, body).forEachIndexed { k, v ->
            v.animate().alpha(0f).translationX(-Ui.dp(c, 24).toFloat()).setDuration(140).withEndAction {
                if (k == 0) apply()
                v.translationX = Ui.dp(c, 24).toFloat()
                v.animate().alpha(1f).translationX(0f).setStartDelay(k * 50L).setDuration(260).start()
            }.start()
        }
    }
}
