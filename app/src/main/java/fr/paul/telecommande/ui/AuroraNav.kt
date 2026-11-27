package fr.paul.telecommande.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import fr.paul.telecommande.R
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.DeviceStore

/**
 * Navigation de l'appli (2.5) partagée par les écrans principaux :
 * une barre flottante en pilule (Maison · Enfants · Activité · Réglages ; l'Aide est dans Réglages), l'onglet en cours sur une pastille indigo,
 * et au centre la touche « Télécommande » : un carré nuit (comme la télécommande, toujours sombre) avec son nom
 * écrit dessous. Volontairement, rien ici ne ressemble aux touches de la TV (Retour TV, Accueil TV, micro rond).
 * La barre se range quand on fait défiler vers le bas et revient dès qu'on remonte.
 */
object AuroraNav {
    /** Les onglets de la barre : Maison, Enfants (règles), Activité (ce qui a été regardé), Réglages. Aide s'ouvre depuis Réglages. */
    enum class Tab { HOME, KIDS, ACTIVITY, SETTINGS, HELP }

    private const val PREFS = "ui"
    private const val KEY_PRIMARY = "primary_device"

    /** Hauteur réservée en bas du contenu pour ne pas passer sous la barre. */
    fun bottomSpace(c: Context) = Ui.dp(c, 128)

    /** TV « principale » : la dernière dont on a ouvert la télécommande, sinon la première box équipée, sinon le premier appareil. */
    fun primary(c: Context): Device? {
        val all = DeviceStore(c).all()
        if (all.isEmpty()) return null
        val id = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PRIMARY, null)
        return all.firstOrNull { it.id == id } ?: all.firstOrNull { it.hasBoxApp } ?: all.firstOrNull { it.isSmartBox } ?: all.first()
    }

    fun setPrimary(c: Context, d: Device) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PRIMARY, d.id).apply()
    }

    /**
     * Enveloppe [content] (en général un ScrollView) avec la barre flottante.
     * Le contenu doit garder un espace libre de [bottomSpace] en bas.
     */
    fun wrap(a: Activity, content: View, selected: Tab): FrameLayout {
        val root = FrameLayout(a).apply { setBackgroundResource(R.drawable.bg_screen) }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        val bar = bar(a, selected)
        root.addView(bar, FrameLayout.LayoutParams(-1, Ui.dp(a, 112), Gravity.BOTTOM))
        (content as? android.widget.ScrollView)?.let { Motion.attach(it, bar) }
        return root
    }

    private fun bar(a: Activity, selected: Tab): View {
        val c: Context = a
        val frame = FrameLayout(c).apply { clipChildren = false; clipToPadding = false }
        val pill = LinearLayout(c).apply {
            setBackgroundResource(R.drawable.bg_nav)
            elevation = Ui.dp(c, 12).toFloat()
            gravity = Gravity.CENTER_VERTICAL
        }
        fun item(tab: Tab, icon: Int, label: String) = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            val on = tab == selected
            val col = Ui.color(c, if (on) R.color.indigo_ink else R.color.muted)
            // L'onglet en cours : son icône sur une pastille indigo (qui apparaît avec un petit rebond)
            val chip = FrameLayout(c).apply {
                if (on) background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = Ui.dp(c, 16).toFloat(); setColor(Ui.color(c, R.color.indigo_soft))
                }
                addView(ImageView(c).apply {
                    setImageResource(icon); imageTintList = ColorStateList.valueOf(Ui.color(c, if (on) R.color.accent else R.color.muted))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22), Gravity.CENTER))
            }
            addView(chip, LinearLayout.LayoutParams(Ui.dp(c, 54), Ui.dp(c, 32)))
            if (on) Motion.pop(chip, 60)
            addView(TextView(c).apply {
                text = label; textSize = 11f; setTextColor(col); maxLines = 1
                typeface = if (on) Ui.bold else Ui.medium
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(c, 3) })
            isClickable = true; isFocusable = true
            contentDescription = label + if (on) ", page actuelle" else ""
            val tv = android.util.TypedValue()
            c.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { if (!on) open(a, tab) }
        }
        pill.addView(item(Tab.HOME, R.drawable.ic_house, "Maison"), LinearLayout.LayoutParams(0, -1, 1f))
        pill.addView(item(Tab.KIDS, R.drawable.ic_shield, "Enfants"), LinearLayout.LayoutParams(0, -1, 1f))
        // Emplacement central : le nom de la touche Télécommande, écrit sous elle
        pill.addView(TextView(c).apply {
            text = "Télécommande"; textSize = 10.5f; typeface = Ui.bold; maxLines = 1
            setTextColor(Ui.color(c, R.color.ink)); gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            setPadding(0, 0, 0, Ui.dp(c, 11))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(Ui.dp(c, 92), -1))
        pill.addView(item(Tab.ACTIVITY, R.drawable.ic_stats, "Activité"), LinearLayout.LayoutParams(0, -1, 1f))
        pill.addView(item(Tab.SETTINGS, R.drawable.ic_settings, "Réglages"), LinearLayout.LayoutParams(0, -1, 1f))
        frame.addView(pill, FrameLayout.LayoutParams(-1, Ui.dp(c, 72), Gravity.BOTTOM).apply {
            setMargins(Ui.dp(c, 14), 0, Ui.dp(c, 14), Ui.dp(c, 14))
        })
        // Touche Télécommande : carré nuit à liseré menthe, icône de télécommande (le micro rond, lui, n'existe que sur la télécommande)
        frame.addView(ImageButton(c).apply {
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x5534F5C5),
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = Ui.dp(c, 22).toFloat(); setColor(Ui.color(c, R.color.hero_bg))
                    setStroke(Ui.dp(c, 2), 0x6634F5C5)
                }, null)
            setImageResource(R.drawable.ic_remote); imageTintList = ColorStateList.valueOf(Ui.color(c, R.color.mint))
            elevation = Ui.dp(c, 16).toFloat()
            contentDescription = "Télécommande : ouvrir la télécommande de la TV"
            setOnClickListener { openRemote(a) }
            Motion.pressable(this, 0.92f)
        }, FrameLayout.LayoutParams(Ui.dp(c, 64), Ui.dp(c, 64), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.dp(c, 2) })
        return frame
    }

    /** Ouvre la télécommande de la TV principale (ou propose d'ajouter un appareil). */
    fun openRemote(a: Activity) {
        val d = primary(a)
        if (d == null) { a.startActivity(Intent(a, MainActivity::class.java).putExtra(MainActivity.EXTRA_ADD, true).top()); return }
        a.startActivity(Intent(a, RemoteActivity::class.java).putExtra(RemoteActivity.EXTRA_ID, d.id))
    }

    private fun Intent.top() = addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun open(a: Activity, tab: Tab) {
        val d = primary(a)
        val i: Intent = when (tab) {
            Tab.HOME -> Intent(a, MainActivity::class.java).top()
            Tab.HELP -> Intent(a, HelpActivity::class.java)
            Tab.SETTINGS -> Intent(a, SettingsActivity::class.java)
            Tab.KIDS, Tab.ACTIVITY -> {
                if (d == null) { kidsIntro(a, tab); return }
                if (!d.isSmartBox) {
                    val box = DeviceStore(a).all().firstOrNull { it.isSmartBox }
                    if (box == null) { kidsIntro(a, tab); return }
                    setPrimary(a, box); open(a, tab); return
                }
                when {
                    d.hasBoxApp -> Intent(a, BoxDashboardActivity::class.java).putExtra(BoxDashboardActivity.EXTRA_ID, d.id)
                        .putExtra(BoxDashboardActivity.EXTRA_MODE, if (tab == Tab.ACTIVITY) BoxDashboardActivity.MODE_ACTIVITY else BoxDashboardActivity.MODE_PARENTAL)
                    tab == Tab.ACTIVITY -> Intent(a, StatsActivity::class.java).putExtra(StatsActivity.EXTRA_ID, d.id)
                    else -> Intent(a, ParentalActivity::class.java).putExtra(ParentalActivity.EXTRA_ID, d.id)
                }
            }
        }
        a.startActivity(i)
        if (a !is MainActivity) a.finish()
        a.overridePendingTransition(R.anim.tab_in, R.anim.tab_out)
    }

    /**
     * Onglet Enfants sans box Android TV : au lieu d'un message qui disparaît, un volet explique quoi faire
     * et mène directement à l'ajout de la box.
     */
    private fun kidsIntro(a: Activity, tab: Tab) {
        val sheet = TileSheet(a)
        val kids = tab == Tab.KIDS
        sheet.show(TileSheet.Page(if (kids) "🛡️" else "📊", if (kids) "Règles des enfants" else "Activité",
            if (kids) "Temps de TV, couvre-feu et contrat" else "Ce que chacun a regardé") { sh, col ->
            sh.label(col, (if (kids) "Les règles s'appliquent" else "L'activité se suit") + " sur une box ou une TV Android TV, comme la Freebox Player POP. " +
                "Ajoutez-la en choisissant « Android TV » : l'appli Box s'installe ensuite sur la TV, et tout marche même téléphone éteint.")
            sh.sections(col, TileSheet.Section(null, listOf(
                TileSheet.Tile("📺", "Ajouter ma box Android TV", "Freebox Player POP, Chromecast, Sony, TCL…", TileSheet.MINT) {
                    sheet.dismiss(); a.startActivity(Intent(a, MainActivity::class.java).putExtra(MainActivity.EXTRA_ADD, true).top())
                },
                TileSheet.Tile("❓", "Comment ça marche", "Les questions fréquentes", TileSheet.SKY) {
                    sheet.dismiss(); a.startActivity(Intent(a, HelpActivity::class.java))
                }
            )))
        })
    }

    /** Réglages : l'écran Réglages de l'appli (gardé pour les anciens appels). */
    fun settings(a: Activity) = open(a, Tab.SETTINGS)

    /** Petit bouton rond (icône seule) pour les en-têtes d'écran. */
    fun roundButton(c: Context, icon: Int, desc: String, action: () -> Unit) = ImageButton(c).apply {
        setBackgroundResource(R.drawable.bg_round); elevation = Ui.dp(c, 2).toFloat()
        setImageResource(icon); imageTintList = ColorStateList.valueOf(Ui.color(c, R.color.ink))
        contentDescription = desc
        layoutParams = LinearLayout.LayoutParams(Ui.dp(c, 46), Ui.dp(c, 46))
        setOnClickListener { action() }
    }

    /** Espace en bas d'une liste pour qu'elle ne passe pas sous la barre flottante. */
    fun spacer(c: Context) = View(c).apply { layoutParams = ViewGroup.LayoutParams(1, bottomSpace(c)) }
}
