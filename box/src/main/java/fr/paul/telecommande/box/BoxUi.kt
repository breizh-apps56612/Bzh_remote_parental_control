package fr.paul.telecommande.box

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Kit visuel de l'appli TV — même identité que le téléphone (premium sombre, accent violet),
 * pensé pour la télécommande : le bouton qui a le focus grossit et s'illumine.
 */
object BoxUi {
    const val BG = 0xFF08080C.toInt()
    const val SURFACE = 0xFF13131B.toInt()
    const val SURFACE_HI = 0xFF1C1C28.toInt()
    const val STROKE = 0x1FFFFFFF
    const val INK = 0xFFF3F2FA.toInt()
    const val MUTED = 0xFF8F8CA6.toInt()
    const val SOFT = 0xFFC9C6DA.toInt()
    const val ACCENT = 0xFF8B6CFF.toInt()
    const val ACCENT2 = 0xFFC46BFF.toInt()
    const val OK = 0xFF3DDC97.toInt()
    const val WARN = 0xFFFBBF24.toInt()
    const val DANGER = 0xFFFF6B7A.toInt()

    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun Context.dp(v: Float) = v * resources.displayMetrics.density

    /** Fond d'écran : noir profond + halo violet en haut. */
    fun screen(c: Context): android.graphics.drawable.Drawable {
        val base = GradientDrawable().apply { setColor(BG) }
        val glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = c.dp(700f)
            setGradientCenter(0.5f, 0f); colors = intArrayOf(0x553A2A8C, 0x00000000)
        }
        return LayerDrawable(arrayOf(base, glow))
    }

    /** Voile plein écran pour les fenêtres par-dessus une appli. */
    fun scrim(): android.graphics.drawable.Drawable = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF00B0912.toInt(), 0xFA050508.toInt()))

    fun cardBg(c: Context, radius: Float = 28f, highlight: Boolean = false) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(SURFACE_HI, SURFACE)).apply {
            cornerRadius = c.dp(radius); setStroke(c.dp(if (highlight) 2f else 1f).toInt(), if (highlight) ACCENT else STROKE)
        }

    fun card(c: Context, padH: Int = 44, padV: Int = 36) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        background = cardBg(c)
        setPadding(c.dp(padH.toFloat()).toInt(), c.dp(padV.toFloat()).toInt(), c.dp(padH.toFloat()).toInt(), c.dp(padV.toFloat()).toInt())
    }

    fun text(c: Context, t: String, size: Float, color: Int = INK, bold: Boolean = false, center: Boolean = true) = TextView(c).apply {
        text = t; textSize = size; setTextColor(color); if (center) gravity = Gravity.CENTER
        typeface = if (bold) BoxUi.bold else Typeface.DEFAULT
        maxWidth = c.dp(760f).toInt()
        if (bold && size >= 24) letterSpacing = -0.02f
        setLineSpacing(0f, 1.1f)
    }

    /** Petit intitulé en capitales espacées. */
    fun label(c: Context, t: String, color: Int = ACCENT) = TextView(c).apply {
        text = t.uppercase(); textSize = 13f; setTextColor(color); typeface = medium; letterSpacing = 0.14f
    }

    /** Pastille ronde (icône / numéro d'étape). */
    fun badge(c: Context, t: String, done: Boolean?, sizeDp: Float = 52f) = TextView(c).apply {
        text = t; gravity = Gravity.CENTER; textSize = sizeDp * 0.36f; typeface = bold
        setTextColor(if (done == false) Color.WHITE else if (done == true) 0xFF06281B.toInt() else SOFT)
        background = when (done) {
            true -> GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(OK) }
            false -> GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ACCENT, ACCENT2)).apply { shape = GradientDrawable.OVAL }
            null -> GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF22222F.toInt()); setStroke(c.dp(1f).toInt(), STROKE) }
        }
        layoutParams = LinearLayout.LayoutParams(c.dp(sizeDp).toInt(), c.dp(sizeDp).toInt())
    }

    /** Bouton TV : pilule ; avec le focus, liseré blanc lumineux + léger grossissement. */
    fun button(c: Context, label: String, primary: Boolean, textSize: Float = 18f, onClick: () -> Unit) = Button(c).apply {
        text = label; this.textSize = textSize; isAllCaps = false; typeface = medium; setTextColor(Color.WHITE)
        val r = c.dp(999f)
        fun base() = if (primary) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACCENT, ACCENT2)).apply { cornerRadius = r }
            else GradientDrawable().apply { setColor(0xFF22222F.toInt()); cornerRadius = r; setStroke(c.dp(1f).toInt(), STROKE) }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), base().apply { setStroke(c.dp(3f).toInt(), Color.WHITE) })
            addState(intArrayOf(android.R.attr.state_pressed), base().apply { alpha = 200 })
            addState(intArrayOf(), base())
        }
        stateListAnimator = null; elevation = 0f
        isFocusable = true; isFocusableInTouchMode = true
        minHeight = c.dp(56f).toInt(); minimumHeight = c.dp(56f).toInt()
        setPadding(c.dp(32f).toInt(), c.dp(12f).toInt(), c.dp(32f).toInt(), c.dp(12f).toInt())
        setOnFocusChangeListener { v, has ->
            if (has) unclip(v)
            v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(140).start()
            if (has) v.elevation = c.dp(8f) else v.elevation = 0f
        }
        setOnClickListener { onClick() }
    }

    /** Un élément agrandi par le focus ne doit pas être rogné par ses parents (rangées, cartes, écran). */
    fun unclip(v: android.view.View) {
        var p = v.parent
        while (p is android.view.ViewGroup) { p.clipChildren = false; p.clipToPadding = false; p = p.parent }
    }

    /** Barre de progression pilule dégradée. */
    fun progress(c: Context, ratio: Float) = LinearLayout(c).apply {
        val r = ratio.coerceIn(0f, 1f)
        background = GradientDrawable().apply { setColor(0xFF22222F.toInt()); cornerRadius = c.dp(99f) }
        addView(View(c).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACCENT, ACCENT2)).apply { cornerRadius = c.dp(99f) } },
            LinearLayout.LayoutParams(0, -1, r))
        addView(View(c), LinearLayout.LayoutParams(0, -1, 1f - r))
    }

    @Suppress("unused") private val keepAnimatorImport = ValueAnimator::class

    /**
     * Cadre qui centre son contenu et le réduit automatiquement s'il ne tient pas à l'écran
     * (box en 720p, zoom d'affichage, marges de la TV…). Rien ne dépasse jamais.
     */
    class Fit(c: Context) : android.widget.FrameLayout(c) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            val any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            for (i in 0 until childCount) {
                val ch = getChildAt(i)
                ch.measure(any, any)
                val sw = if (ch.measuredWidth > 0) w * 0.92f / ch.measuredWidth else 1f
                val sh = if (ch.measuredHeight > 0) h * 0.90f / ch.measuredHeight else 1f
                val sc = minOf(1f, sw, sh)
                ch.pivotX = ch.measuredWidth / 2f; ch.pivotY = ch.measuredHeight / 2f
                ch.scaleX = sc; ch.scaleY = sc
            }
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val ch = getChildAt(i)
                val x = (r - l - ch.measuredWidth) / 2; val y = (b - t - ch.measuredHeight) / 2
                ch.layout(x, y, x + ch.measuredWidth, y + ch.measuredHeight)
            }
        }
    }
}
