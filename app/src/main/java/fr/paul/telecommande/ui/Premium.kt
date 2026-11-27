package fr.paul.telecommande.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import fr.paul.telecommande.R
import fr.paul.telecommande.shared.RulesAdvisor

/**
 * Briques visuelles « A Premium » du contrôle parental : grand bandeau nuit à reflets aurore en haut d'écran,
 * chiffres clés en très grand, encarts de conseils de l'assistant. Contraste fort : blanc sur nuit, encre sur blanc.
 */
object Premium {
    private fun dp(c: Context, v: Int) = Ui.dp(c, v)
    private fun col(c: Context, id: Int) = Ui.color(c, id)

    /** Fond du bandeau : nuit + deux halos (indigo en haut à droite, menthe en bas à gauche), coins du bas arrondis. */
    fun heroBackground(c: Context): android.graphics.drawable.Drawable {
        val r = dp(c, 36).toFloat()
        val base = GradientDrawable().apply { setColor(col(c, R.color.hero_bg)); cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r) }
        fun glow(color: Int, x: Float, y: Float, radiusDp: Int) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = dp(c, radiusDp).toFloat()
            setGradientCenter(x, y); colors = intArrayOf(color, color and 0x00FFFFFF)
        }
        return LayerDrawable(arrayOf(base, glow(col(c, R.color.hero_glow_a), 1f, 0f, 260), glow(col(c, R.color.hero_glow_b), 0f, 1.05f, 240)))
    }

    /** Barre d'état de la couleur du bandeau nuit, icônes claires (écran qui commence par [hero]). */
    fun darkStatusBar(a: android.app.Activity) {
        a.window.statusBarColor = col(a, R.color.hero_bg)
        androidx.core.view.WindowCompat.getInsetsController(a.window, a.window.decorView).isAppearanceLightStatusBars = false
    }

    /** Bandeau du haut : à placer en premier, pleine largeur ; les halos sont coupés à l'arrondi. */
    fun hero(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = heroBackground(c); clipToOutline = true
        setPadding(dp(c, 20), dp(c, 22), dp(c, 20), dp(c, 24))
    }

    fun heroText(c: Context, text: CharSequence, size: Float, soft: Boolean = false, bold: Boolean = false) = TextView(c).apply {
        this.text = text; textSize = size; setTextColor(col(c, if (soft) R.color.hero_soft else R.color.hero_ink))
        if (bold) typeface = Ui.bold
        setLineSpacing(0f, 1.1f)
    }

    /** Très grand chiffre (« 20 ») suivi de son unité (« min restantes »), sur la même ligne de base. */
    fun bigNumber(c: Context, value: String, unit: String, size: Float = 64f, dark: Boolean = true) = LinearLayout(c).apply {
        gravity = Gravity.BOTTOM
        val ink = col(c, if (dark) R.color.hero_ink else R.color.ink)
        addView(TextView(c).apply {
            text = value; textSize = size; typeface = Ui.bold; setTextColor(ink); includeFontPadding = false; letterSpacing = -0.03f
        })
        if (unit.isNotBlank()) addView(TextView(c).apply {
            text = unit; textSize = size * 0.32f; typeface = Ui.bold; setTextColor(ink); includeFontPadding = false
            setPadding(dp(c, 10), 0, 0, dp(c, (size * 0.12f).toInt()))
        })
        contentDescription = "$value $unit"
    }

    /** Pastille d'état claire sur le bandeau (« TV du salon · en direct »). */
    fun heroPill(c: Context, label: String, dot: Int) = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(View(c).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dot); setStroke(dp(c, 3), (dot and 0x00FFFFFF) or 0x33000000) } },
            LinearLayout.LayoutParams(dp(c, 12), dp(c, 12)).apply { marginEnd = dp(c, 8) })
        addView(heroText(c, label, 13.5f).apply { typeface = Ui.medium; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
    }

    /** Barre fine sur le bandeau : remplissage indigo → menthe (ambre au-delà de 90 %). */
    fun heroProgress(c: Context, ratio: Float) = LinearLayout(c).apply {
        val r = ratio.coerceIn(0f, 1f)
        background = GradientDrawable().apply { setColor(0x24FFFFFF); cornerRadius = 99f }
        addView(View(c).apply {
            background = if (r >= 0.9f) GradientDrawable().apply { setColor(col(c, R.color.amber)); cornerRadius = 99f }
            else GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF7C74FF.toInt(), col(c, R.color.mint))).apply { cornerRadius = 99f }
        }, LinearLayout.LayoutParams(0, -1, r))
        addView(View(c), LinearLayout.LayoutParams(0, -1, 1f - r))
    }

    /** Bouton du bandeau : principal = blanc plein, encre ; secondaire = verre dépoli, texte blanc. */
    fun heroButton(c: Context, label: String, primary: Boolean, action: () -> Unit) = TextView(c).apply {
        text = label; gravity = Gravity.CENTER; textSize = 15f; maxLines = 1
        typeface = if (primary) Ui.bold else Ui.medium
        setTextColor(if (primary) col(c, R.color.hero_bg) else Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(c, 18).toFloat()
            if (primary) setColor(Color.WHITE) else { setColor(0x14FFFFFF); setStroke(dp(c, 1), 0x33FFFFFF) }
        }
        minHeight = dp(c, 52); setPadding(dp(c, 8), 0, dp(c, 8), 0)
        isClickable = true; isFocusable = true; setOnClickListener { action() }
    }

    /** Bouton rond du bandeau (retour, cadenas…). */
    fun heroRound(c: Context, icon: Int, desc: String, action: () -> Unit) = android.widget.ImageButton(c).apply {
        setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE); contentDescription = desc
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x14FFFFFF); setStroke(dp(c, 1), 0x33FFFFFF) }
        val p = dp(c, 11); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setOnClickListener { action() }
    }

    /** Tuile claire (fond gris très léger) ; cerclée d'encre quand elle est choisie. */
    fun tileBg(c: Context, selected: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(c, 22).toFloat(); setColor(col(c, R.color.tile))
        if (selected) setStroke(dp(c, 2), col(c, R.color.ink))
    }

    /** Bouton plein encre (blanc sur noir) ou contour encre. */
    fun inkButton(c: Context, label: String, filled: Boolean, onDark: Boolean = false, action: () -> Unit) = TextView(c).apply {
        text = label; gravity = Gravity.CENTER; textSize = 14f; typeface = Ui.bold; maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        val ink = if (onDark) Color.WHITE else col(c, R.color.ink)
        val paper = if (onDark) col(c, R.color.hero_bg) else col(c, R.color.bg)
        setTextColor(if (filled) paper else ink)
        background = GradientDrawable().apply {
            cornerRadius = dp(c, 14).toFloat()
            if (filled) setColor(ink) else { setColor(Color.TRANSPARENT); setStroke(dp(c, 2), ink) }
        }
        minHeight = dp(c, 46); setPadding(dp(c, 12), 0, dp(c, 12), 0)
        isClickable = true; isFocusable = true; setOnClickListener { action() }
    }

    /**
     * Encart de conseil de l'assistant. Contradiction : carte nuit, pastille ambre. Avertissement : fond ambre clair.
     * Information : fond indigo clair. [onFix] reçoit la correction choisie ; [onDismiss] (facultatif) : « Compris ».
     */
    fun adviceCard(c: Context, a: RulesAdvisor.Advice, onFix: (RulesAdvisor.Fix) -> Unit, onDismiss: (() -> Unit)? = null) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        val dark = a.level == RulesAdvisor.Level.CONFLICT
        val bg = when (a.level) { RulesAdvisor.Level.CONFLICT -> col(c, R.color.hero_bg); RulesAdvisor.Level.WARNING -> col(c, R.color.amber_soft); else -> col(c, R.color.indigo_soft) }
        val ink = when (a.level) { RulesAdvisor.Level.CONFLICT -> Color.WHITE; RulesAdvisor.Level.WARNING -> col(c, R.color.amber_ink); else -> col(c, R.color.indigo_ink) }
        background = GradientDrawable().apply { cornerRadius = dp(c, 22).toFloat(); setColor(bg) }
        setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16))
        val head = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(c).apply {
            text = if (a.level == RulesAdvisor.Level.INFO) "i" else "!"; gravity = Gravity.CENTER; textSize = 15f; typeface = Ui.bold
            setTextColor(if (dark) col(c, R.color.hero_bg) else Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (dark) col(c, R.color.amber) else if (a.level == RulesAdvisor.Level.WARNING) 0xFF8A5A00.toInt() else col(c, R.color.accent)) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(c, 28), dp(c, 28)).apply { marginEnd = dp(c, 10) })
        head.addView(TextView(c).apply { text = a.title; textSize = 15.5f; typeface = Ui.bold; setTextColor(ink) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(head)
        addView(TextView(c).apply { text = a.message; textSize = 14f; setTextColor(if (dark) col(c, R.color.hero_soft) else ink); setLineSpacing(0f, 1.15f) },
            Ui.lp(c, 10))
        val actions = a.fixes.map { f -> f.label to { onFix(f) } } + listOfNotNull(onDismiss?.let { d -> "Compris" to d })
        if (actions.isNotEmpty()) {
            val row = LinearLayout(c)
            actions.forEachIndexed { i, (label, act) ->
                val filled = i == 0 && a.fixes.isNotEmpty()
                val b = if (dark) inkButton(c, label, filled, onDark = true) { act() }.apply { if (filled) { setTextColor(col(c, R.color.hero_bg)); (background as GradientDrawable).setColor(Color.WHITE) } else { (background as GradientDrawable).setStroke(dp(c, 1), 0x55FFFFFF) } }
                else inkButton(c, label, filled) { act() }.apply { if (!filled) setTextColor(ink); if (!filled) (background as GradientDrawable).setStroke(dp(c, 2), ink) }
                row.addView(b, LinearLayout.LayoutParams(0, dp(c, 46), 1f).apply { if (i > 0) marginStart = dp(c, 8) })
            }
            addView(row, Ui.lp(c, 14))
        }
    }

    /** Conseils déjà lus (« Compris ») : ne sont plus montrés sur le tableau de bord. */
    fun dismissed(c: Context, deviceId: String): Set<String> =
        c.getSharedPreferences("advisor", Context.MODE_PRIVATE).getStringSet("dismissed_$deviceId", emptySet()) ?: emptySet()

    fun dismiss(c: Context, deviceId: String, id: String) {
        val p = c.getSharedPreferences("advisor", Context.MODE_PRIVATE)
        p.edit().putStringSet("dismissed_$deviceId", dismissed(c, deviceId) + id).apply()
    }
}
