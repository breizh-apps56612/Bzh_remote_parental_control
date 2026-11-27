package fr.paul.telecommande.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Animations « premium » de l'appli, toutes discrètes et coupées si le téléphone a désactivé les animations
 * (Options pour les développeurs ou Accessibilité → « Supprimer les animations ») :
 *  - les cartes apparaissent en glissant vers le haut au fil du défilement (une seule fois : les rafraîchissements
 *    toutes les 30 s ne les rejouent pas) ;
 *  - le bandeau nuit du haut (« héros ») défile plus lentement que le contenu et s'estompe : le contenu passe dessus ;
 *  - la barre de navigation se range quand on descend et revient dès qu'on remonte ;
 *  - les boutons s'enfoncent légèrement sous le doigt.
 */
object Motion {
    /** Étiquette posée sur le bandeau du haut pour l'effet de profondeur. */
    const val HERO_TAG = "motion:hero"

    fun enabled(c: Context): Boolean =
        // Tests visuels (Robolectric) : écrans figés, sans animation, pour des captures nettes
        if (Build.FINGERPRINT == "robolectric") false
        else if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
        else runCatching { Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f

    private val ease = DecelerateInterpolator(2.2f)

    /**
     * Branche les effets de défilement sur [scroll]. [bar] (facultatif) : la barre flottante à ranger pendant la descente.
     */
    fun attach(scroll: ScrollView, bar: View?) {
        val c = scroll.context
        if (!enabled(c)) return
        val d = c.resources.displayMetrics.density
        // Tout ce qui a déjà été vu (au-dessus de cette ligne, en pixels du contenu) s'affiche sans animation
        var seenUntil = 0
        var lastY = 0
        var barHidden = false
        // Pendant la première seconde et demie (le temps que l'écran reçoive ses données), tout arrive en cascade
        val since = android.os.SystemClock.uptimeMillis()

        fun content(): ViewGroup? = scroll.getChildAt(0) as? ViewGroup

        fun reveal() {
            val root = content() ?: return
            val bottom = scroll.scrollY + scroll.height
            var stagger = 0L
            forEachBlock(root) { v, top ->
                if (v.getTag(KEY_SHOWN) == true) return@forEachBlock
                when {
                    // Déjà vu (écran rafraîchi) : affiché tel quel, sans rejouer l'animation
                    top < seenUntil && android.os.SystemClock.uptimeMillis() - since > 1_500 -> { v.setTag(KEY_SHOWN, true); v.alpha = 1f; v.translationY = 0f }
                    // Arrive à l'écran : glisse vers le haut en fondu, en cascade avec ses voisins
                    top < bottom - 16 * d -> {
                        v.setTag(KEY_SHOWN, true)
                        v.alpha = 0f; v.translationY = 28 * d
                        v.animate().alpha(1f).translationY(0f).setStartDelay(stagger).setDuration(420).setInterpolator(ease).start()
                        stagger = (stagger + 55).coerceAtMost(330)
                    }
                    // Plus bas : attend qu'on y arrive
                    else -> { v.alpha = 0f; v.translationY = 28 * d }
                }
            }
            seenUntil = maxOf(seenUntil, bottom - (16 * d).toInt())
        }

        fun parallax() {
            val root = content() ?: return
            val hero = findHero(root) ?: return
            val y = scroll.scrollY.toFloat()
            val h = hero.height.coerceAtLeast(1)
            hero.translationY = y * 0.3f
            hero.alpha = 1f - (y / h).coerceIn(0f, 1f) * 0.6f
            val s = 1f - (y / h).coerceIn(0f, 1f) * 0.04f
            hero.scaleX = s; hero.scaleY = s
        }

        fun navBar(dy: Int) {
            bar ?: return
            val atEnd = !scroll.canScrollVertically(1)
            val hide = dy > 6 && scroll.scrollY > 120 * d && !atEnd
            val show = dy < -6 || atEnd || scroll.scrollY < 40 * d
            if (hide && !barHidden) { barHidden = true; bar.animate().translationY(bar.height * 0.92f).setDuration(240).setInterpolator(ease).start() }
            else if (show && barHidden) { barHidden = false; bar.animate().translationY(0f).setDuration(260).setInterpolator(ease).start() }
        }

        // Premier affichage : les cartes visibles arrivent en cascade ; la suite attend le défilement
        scroll.viewTreeObserver.addOnGlobalLayoutListener {
            if (scroll.height == 0) return@addOnGlobalLayoutListener
            reveal(); parallax()
        }
        scroll.viewTreeObserver.addOnScrollChangedListener {
            val y = scroll.scrollY
            reveal(); parallax()
            navBar(y - lastY)
            lastY = y
        }
    }

    private val KEY_SHOWN = fr.paul.telecommande.R.id.motion_shown

    /**
     * Les « blocs » qui apparaissent un par un : les enfants du contenu, et ceux des colonnes verticales qu'il contient
     * (un écran fait souvent bandeau + colonne de cartes). Le bandeau du haut n'en fait pas partie.
     */
    private fun forEachBlock(root: ViewGroup, f: (View, Int) -> Unit) {
        for (i in 0 until root.childCount) {
            val v = root.getChildAt(i)
            if (v.tag == HERO_TAG || v.visibility != View.VISIBLE) continue
            if (v is LinearLayout && v.orientation == LinearLayout.VERTICAL && v.childCount > 1 && v.background == null) {
                for (j in 0 until v.childCount) {
                    val w = v.getChildAt(j)
                    if (w.visibility == View.VISIBLE && w.height > 0) f(w, v.top + w.top)
                }
            } else if (v.height > 0) f(v, v.top)
        }
    }

    private fun findHero(root: ViewGroup): View? {
        for (i in 0 until root.childCount) {
            val v = root.getChildAt(i)
            if (v.tag == HERO_TAG) return v
        }
        return null
    }

    /** Le bouton s'enfonce un peu sous le doigt et rebondit au relâché (sans gêner le clic). */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(v: View, depth: Float = 0.96f) {
        if (!enabled(v.context)) return
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(depth).scaleY(depth).setDuration(90).setInterpolator(ease).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2.4f)).start()
            }
            false
        }
    }

    /** Petite entrée élastique (pastille d'onglet, badge). */
    fun pop(v: View, delay: Long = 0) {
        if (!enabled(v.context)) return
        v.scaleX = 0.6f; v.scaleY = 0.6f; v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(delay).setDuration(320).setInterpolator(OvershootInterpolator(1.8f)).start()
    }

    /** Une barre de progression qui se remplit depuis zéro (poids d'un LinearLayout horizontal à deux enfants). */
    fun fillWeights(bar: LinearLayout, ratio: Float) {
        if (!enabled(bar.context) || bar.childCount < 2) return
        val a = bar.getChildAt(0); val b = bar.getChildAt(1)
        ValueAnimator.ofFloat(0f, ratio).apply {
            duration = 900; interpolator = ease; startDelay = 180
            addUpdateListener {
                val r = it.animatedValue as Float
                (a.layoutParams as LinearLayout.LayoutParams).weight = r
                (b.layoutParams as LinearLayout.LayoutParams).weight = 1f - r
                bar.requestLayout()
            }
            start()
        }
    }
}
