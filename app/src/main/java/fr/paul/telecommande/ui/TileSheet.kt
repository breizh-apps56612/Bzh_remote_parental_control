package fr.paul.telecommande.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import fr.paul.telecommande.R

/**
 * Volet « premium » partagé par toute l'appli (2.5.3) : il monte du bas sur un voile, avec en tête un bandeau nuit
 * à reflets aurore (grande pastille, titre, chiffre du moment), puis des tuiles qui arrivent en cascade.
 * Une tuile peut ouvrir une page suivante : elle glisse depuis la droite, la flèche (ou le retour du téléphone)
 * revient à la page d'avant. Clair dans l'appli, sombre dans la télécommande (il suit les couleurs de l'écran qui l'ouvre).
 */
class TileSheet(private val a: Activity) {

    /**
     * Une tuile. [key] : touche de télécommande (carrée, grande étiquette, le volet reste ouvert pour enchaîner).
     * [color] : couleur pleine (touches rouge, verte…). [icon] : image (icône d'appli) à la place de l'emoji.
     */
    class Tile(
        val emoji: String, val title: String, val sub: String? = null,
        val tint: Int = 0, val key: Boolean = false, val color: Int = 0, val icon: Bitmap? = null,
        val onTap: () -> Unit
    )

    class Section(val title: String?, val tiles: List<Tile>, val columns: Int = 2)

    /** Une page du volet : son en-tête, et ce qu'elle contient ([build] remplit la colonne du corps). */
    class Page(
        val emoji: String, val title: String, val subtitle: String? = null,
        val big: String? = null, val bigUnit: String = "",
        val build: (TileSheet, LinearLayout) -> Unit
    )

    private val c = a
    private val d = c.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val ease = DecelerateInterpolator(2.2f)

    private val dialog = Dialog(c, android.R.style.Theme_Translucent_NoTitleBar)
    private val stack = ArrayList<Page>()
    private val scrim = View(c).apply { setBackgroundColor(0x99000000.toInt()) }
    private val panel = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            val r = dp(30).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(Ui.color(c, R.color.bg))
        }
        clipToOutline = true
        elevation = dp(24).toFloat()
        isClickable = true   // les touches sur le volet ne ferment pas
    }
    private val head = FrameLayout(c)
    private val scroll = ScrollView(c).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
    private val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(28)) }
    var onDismiss: (() -> Unit)? = null
    private var closing = false

    val isShowing get() = dialog.isShowing
    val depth get() = stack.size

    init {
        val root = FrameLayout(c)
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        val h = (c.resources.displayMetrics.heightPixels * 0.9f).toInt()
        root.addView(panel, FrameLayout.LayoutParams(-1, h, Gravity.BOTTOM))
        panel.addView(head, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(body, ViewGroup.LayoutParams(-1, -2))
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        scrim.setOnClickListener { dismiss() }
        dialog.setContentView(root, ViewGroup.LayoutParams(-1, -1))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            setWindowAnimations(0)
        }
        // Retour du téléphone : page d'avant, puis fermer
        dialog.setOnKeyListener { _, code, e ->
            if (code == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) { if (stack.size > 1) pop() else dismiss(); true } else false
        }
        dialog.setOnDismissListener { onDismiss?.invoke() }
    }

    // ---------- Ouvrir, empiler, fermer ----------

    fun show(page: Page) {
        stack.clear(); stack += page
        renderPage(page, direction = 0)
        dialog.show()
        if (Motion.enabled(c)) {
            scrim.alpha = 0f; scrim.animate().alpha(1f).setDuration(260).start()
            panel.translationY = c.resources.displayMetrics.heightPixels.toFloat()
            panel.animate().translationY(0f).setDuration(420).setInterpolator(DecelerateInterpolator(2.6f)).start()
        }
    }

    fun push(page: Page) { stack += page; renderPage(page, direction = 1) }

    fun pop() { if (stack.size > 1) { stack.removeAt(stack.lastIndex); renderPage(stack.last(), direction = -1) } }

    /** Refait la page affichée (après un changement : bonus accordé, pause…). */
    fun refresh() { if (dialog.isShowing && stack.isNotEmpty()) renderPage(stack.last(), direction = 0, animate = false) }

    fun dismiss() {
        if (closing || !dialog.isShowing) return
        closing = true
        if (!Motion.enabled(c)) { dialog.dismiss(); return }
        scrim.animate().alpha(0f).setDuration(220).start()
        panel.animate().translationY(panel.height.toFloat()).setDuration(260).setInterpolator(android.view.animation.AccelerateInterpolator(1.6f))
            .withEndAction { runCatching { dialog.dismiss() } }.start()
    }

    // ---------- Construction d'une page ----------

    private fun renderPage(p: Page, direction: Int, animate: Boolean = true) {
        head.removeAllViews(); body.removeAllViews()
        head.addView(header(p), FrameLayout.LayoutParams(-1, -2))
        p.build(this, body)
        scroll.scrollTo(0, 0)
        if (!animate || !Motion.enabled(c)) return
        // Les blocs arrivent en cascade ; une page suivante glisse depuis la droite (la précédente, depuis la gauche)
        val shift = dp(36).toFloat() * direction
        for (i in 0 until body.childCount) {
            val v = body.getChildAt(i)
            v.alpha = 0f; v.translationY = if (direction == 0) dp(26).toFloat() else 0f; v.translationX = shift
            v.animate().alpha(1f).translationY(0f).translationX(0f).setStartDelay(90L + i * 45L).setDuration(380).setInterpolator(ease).start()
        }
        val badge = head.findViewWithTag<View>("badge")
        badge?.let { Motion.pop(it, 60) }
    }

    /** Bandeau nuit : poignée, retour ou fermer, grande pastille, titre, sous-titre, chiffre du moment. */
    private fun header(p: Page): View {
        val h = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Premium.heroBackground(c)
            setPadding(dp(20), dp(10), dp(20), dp(22))
        }
        h.addView(View(c).apply {
            background = GradientDrawable().apply { cornerRadius = 99f; setColor(0x40FFFFFF) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(44), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val top = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = stack.size > 1
        top.addView(Premium.heroRound(c, if (back) R.drawable.ic_back else R.drawable.ic_chevron_down, if (back) "Revenir" else "Fermer") {
            if (back) pop() else dismiss()
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        if (back) top.addView(Premium.heroRound(c, R.drawable.ic_close, "Fermer") { dismiss() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        h.addView(top, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        val badge = TextView(c).apply {
            text = p.emoji; textSize = 30f; gravity = Gravity.CENTER; tag = "badge"
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Ui.color(c, R.color.hero_bg)); setStroke(dp(2), 0x6634F5C5) }
            elevation = dp(8).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(badge, LinearLayout.LayoutParams(dp(68), dp(68)))
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        texts.addView(Premium.heroText(c, p.title, 24f, bold = true).apply { maxLines = 2 })
        if (!p.subtitle.isNullOrBlank()) texts.addView(Premium.heroText(c, p.subtitle, 14f, soft = true).apply { maxLines = 3 }, Ui.lp(c, 4))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        h.addView(row, Ui.lp(c, 14))
        if (!p.big.isNullOrBlank()) h.addView(Premium.bigNumber(c, p.big, p.bigUnit, if (p.big.length > 5) 44f else 56f), Ui.lp(c, 16))
        return h
    }

    // ---------- Briques du corps ----------

    /** Titre de groupe dans le corps. */
    fun label(container: LinearLayout, text: String) {
        container.addView(TextView(c).apply {
            this.text = text.uppercase(); textSize = 12f; typeface = Ui.bold; letterSpacing = 0.08f
            setTextColor(Ui.color(c, R.color.muted)); setPadding(dp(4), dp(20), 0, dp(2))
        })
    }

    /** Une ou plusieurs sections de tuiles. */
    fun sections(container: LinearLayout, vararg list: Section) {
        list.forEach { s ->
            s.title?.let { label(container, it) }
            s.tiles.chunked(s.columns).forEach { chunk ->
                val row = LinearLayout(c)
                chunk.forEach { t -> row.addView(tileView(t), LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }) }
                repeat(s.columns - chunk.size) { row.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(5), 0, dp(5), 0) }) }
                container.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }

    /** Tuile : carte claire (ou sombre dans la télécommande), pastille colorée, titre et précision ; touches : carrées. */
    fun tileView(t: Tile): View {
        val surface = if (t.color != 0) t.color else Ui.color(c, if (t.key) R.color.surface2 else R.color.surface)
        val v = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = RippleDrawable(ColorStateList.valueOf(Ui.color(c, R.color.accent_soft)), GradientDrawable().apply {
                cornerRadius = dp(if (t.key) 20 else 24).toFloat(); setColor(surface)
                if (t.color == 0) setStroke(dp(1), Ui.color(c, R.color.line))
            }, null)
            isClickable = true; isFocusable = true
            contentDescription = listOfNotNull(t.title, t.sub).joinToString(", ")
        }
        if (t.key) {
            v.gravity = Gravity.CENTER
            v.minimumHeight = dp(66)
            v.addView(TextView(c).apply {
                text = t.title; textSize = if (t.title.length <= 2) 24f else 15f; typeface = Ui.bold; gravity = Gravity.CENTER; maxLines = 2
                setTextColor(if (t.color != 0) Color.WHITE else Ui.color(c, R.color.ink))
            })
            v.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); t.onTap() }
            Motion.pressable(v, 0.9f)
            return v
        }
        v.setPadding(dp(14), dp(14), dp(14), dp(14))
        v.minimumHeight = dp(118)
        if (t.icon != null) v.addView(ImageView(c).apply {
            setImageBitmap(t.icon); scaleType = ImageView.ScaleType.FIT_CENTER; clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.TRANSPARENT) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(76), dp(44)))
        else v.addView(TextView(c).apply {
            text = t.emoji; textSize = 21f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(if (t.tint != 0) t.tint else Ui.color(c, R.color.indigo_soft)) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        v.addView(View(c), LinearLayout.LayoutParams(1, 0, 1f))
        v.addView(TextView(c).apply {
            text = t.title; textSize = 15f; typeface = Ui.bold; maxLines = 2; setTextColor(Ui.color(c, R.color.ink))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        if (!t.sub.isNullOrBlank()) v.addView(TextView(c).apply {
            text = t.sub; textSize = 12.5f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(Ui.color(c, R.color.muted))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        v.setOnClickListener { t.onTap() }
        Motion.pressable(v, 0.95f)
        return v
    }

    companion object {
        /** Teintes douces des pastilles (lisibles en clair comme en sombre). */
        const val MINT = 0x2E34F5C5
        const val AMBER = 0x33FFB020
        const val ROSE = 0x2EE11D48
        const val SKY = 0x2E3B82F6
        const val INDIGO = 0x337C74FF
        const val VIOLET = 0x33A855F7

        /** Petit rebond d'apparition (utilisé par l'en-tête). */
        fun bounce(v: View) { v.scaleX = 0.7f; v.scaleY = 0.7f; v.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(2f)).start() }
    }
}
