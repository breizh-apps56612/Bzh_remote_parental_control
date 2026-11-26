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
import fr.paul.telecommande.core.ParentalStore

/**
 * Navigation « Aurora » partagée par les écrans principaux :
 * une barre flottante en pilule (Maison · Parental · Activité · Réglages) et, au centre,
 * un gros bouton rond en dégradé qui ouvre la télécommande de la TV principale.
 */
object AuroraNav {
    enum class Tab { HOME, PARENTAL, ACTIVITY, SETTINGS }

    private const val PREFS = "ui"
    private const val KEY_PRIMARY = "primary_device"

    /** Hauteur réservée en bas du contenu pour ne pas passer sous la barre. */
    fun bottomSpace(c: Context) = Ui.dp(c, 120)

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
        root.addView(bar(a, selected), FrameLayout.LayoutParams(-1, Ui.dp(a, 104), Gravity.BOTTOM))
        return root
    }

    private fun bar(a: Activity, selected: Tab): View {
        val c: Context = a
        val frame = FrameLayout(c).apply { clipChildren = false; clipToPadding = false }
        val pill = LinearLayout(c).apply {
            setBackgroundResource(R.drawable.bg_nav)
            elevation = Ui.dp(c, 10).toFloat()
            gravity = Gravity.CENTER_VERTICAL
        }
        fun item(tab: Tab, icon: Int, label: String) = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            val on = tab == selected
            val col = Ui.color(c, if (on) R.color.accent else R.color.muted)
            addView(ImageView(c).apply {
                setImageResource(icon); imageTintList = ColorStateList.valueOf(col)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22)))
            addView(TextView(c).apply {
                text = label; textSize = 10.5f; setTextColor(col); maxLines = 1
                typeface = if (on) Ui.bold else Ui.medium
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(c, 3) })
            isClickable = true; isFocusable = true
            contentDescription = label + if (on) ", page actuelle" else ""
            val tv = android.util.TypedValue()
            c.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { if (!on) open(a, tab) }
        }
        pill.addView(item(Tab.HOME, R.drawable.ic_home, "Maison"), LinearLayout.LayoutParams(0, -1, 1f))
        pill.addView(item(Tab.PARENTAL, R.drawable.ic_shield, "Parental"), LinearLayout.LayoutParams(0, -1, 1f))
        pill.addView(View(c), LinearLayout.LayoutParams(Ui.dp(c, 84), 1))
        pill.addView(item(Tab.ACTIVITY, R.drawable.ic_activity, "Activité"), LinearLayout.LayoutParams(0, -1, 1f))
        pill.addView(item(Tab.SETTINGS, R.drawable.ic_settings, "Réglages"), LinearLayout.LayoutParams(0, -1, 1f))
        frame.addView(pill, FrameLayout.LayoutParams(-1, Ui.dp(c, 66), Gravity.BOTTOM).apply {
            setMargins(Ui.dp(c, 16), 0, Ui.dp(c, 16), Ui.dp(c, 16))
        })
        frame.addView(ImageButton(c).apply {
            setBackgroundResource(R.drawable.bg_fab)
            setImageResource(R.drawable.ic_remote); imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            elevation = Ui.dp(c, 14).toFloat()
            contentDescription = "Télécommande"
            setOnClickListener { openRemote(a) }
        }, FrameLayout.LayoutParams(Ui.dp(c, 70), Ui.dp(c, 70), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
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
        when (tab) {
            Tab.HOME -> { a.startActivity(Intent(a, MainActivity::class.java).top()); if (a !is MainActivity) a.finish() }
            Tab.SETTINGS -> settings(a)
            Tab.PARENTAL, Tab.ACTIVITY -> {
                if (d == null) { Ui.toast(a, "Ajoutez d'abord votre TV"); return }
                val i = when {
                    d.hasBoxApp -> Intent(a, BoxDashboardActivity::class.java).putExtra(BoxDashboardActivity.EXTRA_ID, d.id)
                        .putExtra(BoxDashboardActivity.EXTRA_MODE, if (tab == Tab.PARENTAL) BoxDashboardActivity.MODE_PARENTAL else BoxDashboardActivity.MODE_ACTIVITY)
                    tab == Tab.PARENTAL && ParentalStore(a).rules(d.id).enabled -> Intent(a, ParentalActivity::class.java).putExtra(ParentalActivity.EXTRA_ID, d.id)
                    else -> Intent(a, StatsActivity::class.java).putExtra(StatsActivity.EXTRA_ID, d.id)
                }
                a.startActivity(i)
                if (a !is MainActivity) a.finish()
                a.overridePendingTransition(0, 0)
            }
        }
    }

    /** Réglages généraux de l'appli (accessibles de partout). */
    fun settings(a: Activity) {
        val items = listOf(
            "Ajouter un appareil" to { a.startActivity(Intent(a, MainActivity::class.java).putExtra(MainActivity.EXTRA_ADD, true).top()) },
            "Ma maison : toutes les TV" to { a.startActivity(Intent(a, HomeOverviewActivity::class.java)) },
            "Rechercher une mise à jour" to { PhoneUpdate.checkAndOffer(a, force = true) }
        )
        AlertDialog.Builder(a).setTitle("Réglages")
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("Fermer", null).show()
    }

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
