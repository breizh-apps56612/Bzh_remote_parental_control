package fr.paul.telecommande.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import fr.paul.telecommande.R
import kotlin.math.abs

/**
 * Pavé tactile :
 *  - glisser = flèches (un pas tous les ~48 dp, on peut enchaîner sans lever le doigt) ;
 *  - toucher = OK ; appui long = menu ;
 *  - glisser puis garder le doigt posé = touche maintenue (retour / avance rapide en continu, défilement) ;
 *  - double-toucher sur le tiers gauche / droit = −10 s / +10 s ;
 *  - barre de gauche = chaînes, barre de droite = volume (toucher le haut / le bas, rester appuyé
 *    pour répéter, ou glisser le long de la barre) ; le milieu de la barre de volume = muet.
 */
class TouchpadView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Gesture { UP, DOWN, LEFT, RIGHT, TAP, LONG_PRESS, SEEK_BACK, SEEK_FORWARD, VOL_UP, VOL_DOWN, MUTE, CH_UP, CH_DOWN }

    var listener: ((Gesture) -> Unit)? = null
    /** Flèche maintenue (après un glissement, le doigt reste posé) : true au début, false au relâchement. */
    var holdListener: ((Gesture, Boolean) -> Unit)? = null

    /**
     * Mode souris = pavé tactile d'ordinateur portable : le curseur suit le doigt
     * (chaque petit déplacement devient un pas de curseur sur la TV), toucher = clic.
     */
    var mouseMode = false
        set(v) { field = v; releaseListener?.invoke(); trail.clear(); invalidate() }
    /** Un pas de curseur (UP/DOWN/LEFT/RIGHT) à envoyer. */
    var stepListener: ((Gesture) -> Unit)? = null
    /** Le doigt est levé : arrêter le curseur. */
    var releaseListener: (() -> Unit)? = null
    /** Vraie souris (Bluetooth) : déplacement relatif continu en unités souris, à la place des pas de flèches. */
    var rawMoveListener: ((Float, Float) -> Unit)? = null
    /** Deux doigts = molette (crans, >0 vers le haut). */
    var scrollListener: ((Int) -> Unit)? = null
    private var twoFinger = false
    private var scrollAccY = 0f; private var lastTwoY = 0f
    private var accX = 0f; private var accY = 0f
    private var lastMoveX = 0f; private var lastMoveY = 0f; private var lastMoveT = 0L
    private val baseStep get() = 9 * d
    private val trail = ArrayDeque<Pair<Float, Float>>()
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    private fun trackMove(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val dx = x - lastMoveX; val dy = y - lastMoveY
        val dt = (now - lastMoveT).coerceAtLeast(1)
        lastMoveX = x; lastMoveY = y; lastMoveT = now
        // accélération : un geste rapide fait parcourir plus de chemin au curseur
        val speed = kotlin.math.hypot(dx, dy) / d / dt          // dp par ms
        val gain = (1f + (speed - 0.25f).coerceAtLeast(0f) * 1.6f).coerceAtMost(3f)
        if (!moved && (abs(x - downX) > slop || abs(y - downY) > slop)) moved = true
        if (!moved) return
        rawMoveListener?.let { l ->
            l(dx / d * 1.4f * gain, dy / d * 1.4f * gain)
            trail.addLast(x to y); while (trail.size > 14) trail.removeFirst()
            return
        }
        accX += dx * gain; accY += dy * gain
        while (abs(accX) >= baseStep) { val g = if (accX > 0) Gesture.RIGHT else Gesture.LEFT; stepListener?.invoke(g); accX -= if (accX > 0) baseStep else -baseStep }
        while (abs(accY) >= baseStep) { val g = if (accY > 0) Gesture.DOWN else Gesture.UP; stepListener?.invoke(g); accY -= if (accY > 0) baseStep else -baseStep }
        trail.addLast(x to y); while (trail.size > 14) trail.removeFirst()
    }

    var hint: String = "Glisser = flèches · Toucher = OK"
        set(value) { field = value; invalidate() }

    private val d = resources.displayMetrics.density
    private val step = 48 * d
    private val volStep = 26 * d
    /** Barres latérales : chaînes à gauche, volume à droite (masquées en mode souris). */
    var showStrips = true
        set(v) { field = v; invalidate() }
    private val stripsOn get() = showStrips && !mouseMode
    private val stripW = 46 * d
    private val stripM = 8 * d
    /** Largeur occupée par une barre et sa marge (zone de toucher un peu plus large que le dessin). */
    private val stripZone get() = if (stripsOn) stripW + stripM + 8 * d else 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.pad) }
    private val ink = ContextCompat.getColor(context, R.color.ink)
    private val muted = ContextCompat.getColor(context, R.color.muted)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.pad_dot) }
    private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.surface2) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 12 * d; textAlign = Paint.Align.CENTER }
    private val stripText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 18 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent); alpha = 60 }
    private val chevron = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink; style = Paint.Style.STROKE; strokeWidth = 2.4f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val centerDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val path = android.graphics.Path()
    /** Flash bref de la flèche envoyée, pour confirmer le geste. */
    private var flashDir: Gesture? = null
    private val clearFlash = Runnable { flashDir = null; invalidate() }
    private val rect = RectF()

    private var downX = 0f; private var downY = 0f
    private var anchorX = 0f; private var anchorY = 0f
    private var touchX = -1f; private var touchY = -1f
    private var downTime = 0L
    private var moved = false
    /** Barre touchée : -1 = chaînes (gauche), 1 = volume (droite), 0 = pavé. */
    private var strip = 0
    /** Zone de la barre : 1 = haut (+), -1 = bas (−), 0 = milieu (muet sur le volume). */
    private var stripPart = 0
    private val stripRepeat = object : Runnable {
        override fun run() {
            stripFire(); postDelayed(this, if (strip < 0) 450 else 150)
        }
    }
    /** Flèche maintenue en ce moment (null = aucune). */
    private var holding: Gesture? = null
    private var lastSwipe: Gesture? = null
    private val holdCheck = Runnable {
        val g = lastSwipe ?: return@Runnable
        if (touchX >= 0 && holding == null && !mouseMode) {
            holding = g
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            holdListener?.invoke(g, true)
            invalidate()
        }
    }

    private fun stopHold() {
        removeCallbacks(holdCheck)
        holding?.let { g -> holding = null; holdListener?.invoke(g, false); invalidate() }
    }

    private fun stripFire() {
        fire(when {
            strip < 0 && stripPart >= 0 -> Gesture.CH_UP
            strip < 0 -> Gesture.CH_DOWN
            stripPart > 0 -> Gesture.VOL_UP
            stripPart < 0 -> Gesture.VOL_DOWN
            else -> Gesture.MUTE
        })
    }

    override fun onDetachedFromWindow() { stopHold(); removeCallbacks(stripRepeat); super.onDetachedFromWindow() }
    private var longFired = false
    private var lastTapTime = 0L
    private var lastTapZone = 0
    private var pendingTap: Runnable? = null

    private val longPress = Runnable {
        if (!moved) { longFired = true; fire(Gesture.LONG_PRESS) }
    }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Pavé tactile : glisser pour naviguer, toucher pour valider"
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density; color = ContextCompat.getColor(context, R.color.stroke)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val r = 36 * d
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        // fond Aurora : dégradé en diagonale (carte → lueur indigo → menthe) + halo doux au centre + liseré
        bgPaint.shader = android.graphics.LinearGradient(0f, 0f, width * 0.45f, height.toFloat(),
            intArrayOf(ContextCompat.getColor(context, R.color.surface_hi), ContextCompat.getColor(context, R.color.aurora_a),
                ContextCompat.getColor(context, R.color.aurora_b)),
            floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, bgPaint)
        glowPaint.shader = android.graphics.RadialGradient(width / 2f, height / 2f, maxOf(1f, minOf(width, height) * 0.5f),
            ContextCompat.getColor(context, R.color.accent_soft), 0x00000000, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, glowPaint)
        rect.inset(strokePaint.strokeWidth / 2, strokePaint.strokeWidth / 2)
        canvas.drawRoundRect(rect, r, r, strokePaint)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())

        val left = stripZone
        val right = width - stripZone
        val cx = (left + right) / 2f
        val cy = height / 2f
        val m = 30 * d
        val c = 9 * d
        fun chev(x: Float, y: Float, dir: Gesture) {
            chevron.color = ContextCompat.getColor(context, R.color.accent)
            chevron.strokeWidth = 3.2f * d
            path.reset()
            when (dir) {
                Gesture.UP -> { path.moveTo(x - c, y + c / 2); path.lineTo(x, y - c / 2); path.lineTo(x + c, y + c / 2) }
                Gesture.DOWN -> { path.moveTo(x - c, y - c / 2); path.lineTo(x, y + c / 2); path.lineTo(x + c, y - c / 2) }
                Gesture.LEFT -> { path.moveTo(x + c / 2, y - c); path.lineTo(x - c / 2, y); path.lineTo(x + c / 2, y + c) }
                else -> { path.moveTo(x - c / 2, y - c); path.lineTo(x + c / 2, y); path.lineTo(x - c / 2, y + c) }
            }
            canvas.drawPath(path, chevron)
        }
        if (mouseMode) {
            // pavé de portable : fine trame de points, curseur au repos, traînée du doigt
            val gap = 22 * d
            var gy = gap
            while (gy < height) { var gx = gap; while (gx < width) { canvas.drawCircle(gx, gy, 1.2f * d, dotPaint); gx += gap }; gy += gap }
            if (touchX < 0) {
                val arrow = android.graphics.Path().apply {
                    moveTo(cx - 8 * d, cy - 22 * d); lineTo(cx - 8 * d, cy + 4 * d); lineTo(cx - 1 * d, cy - 3 * d)
                    lineTo(cx + 4 * d, cy + 7 * d); lineTo(cx + 8 * d, cy + 5 * d); lineTo(cx + 3 * d, cy - 5 * d); lineTo(cx + 12 * d, cy - 5 * d); close()
                }
                canvas.drawPath(arrow, centerDot.apply { color = ink })
                canvas.drawText("Glissez le doigt pour déplacer le curseur", cx, cy + 34 * d, textPaint)
                canvas.drawText("Touchez pour cliquer", cx, cy + 50 * d, textPaint)
            } else {
                val acc = ContextCompat.getColor(context, R.color.accent)
                var prev: Pair<Float, Float>? = null
                trail.forEachIndexed { i, pt ->
                    prev?.let { pv ->
                        trailPaint.color = acc; trailPaint.alpha = 30 + 160 * i / trail.size; trailPaint.strokeWidth = (4 + 10f * i / trail.size) * d
                        canvas.drawLine(pv.first, pv.second, pt.first, pt.second, trailPaint)
                    }
                    prev = pt
                }
                canvas.drawCircle(touchX, touchY, 22 * d, touchPaint)
            }
            return
        }
        // Flèche envoyée (ou maintenue) : brève confirmation sur le bord du pavé
        when (val f = holding ?: flashDir) {
            Gesture.UP -> chev(cx, m, f); Gesture.DOWN -> chev(cx, height - m - 18 * d, f)
            Gesture.LEFT -> chev(left + m, cy, f); Gesture.RIGHT -> chev(right - m, cy, f)
            else -> {}
        }
        // Bouton « OK » central (maquette Aurora) : halo indigo doux, pastille blanche ombrée
        val tap = flashDir == Gesture.TAP
        val haloR = minOf(66 * d, (right - left) * 0.27f)
        centerDot.color = ContextCompat.getColor(context, R.color.accent)
        centerDot.alpha = if (tap) 60 else 30
        canvas.drawCircle(cx, cy, if (tap) haloR * 1.08f else haloR, centerDot)
        centerDot.color = ContextCompat.getColor(context, R.color.surface)
        centerDot.alpha = 255
        centerDot.setShadowLayer(12 * d, 0f, 5 * d, (ContextCompat.getColor(context, R.color.accent) and 0x00FFFFFF) or 0x50000000)
        canvas.drawCircle(cx, cy, haloR * (if (tap) 0.6f else 0.55f), centerDot)
        centerDot.clearShadowLayer()
        holding?.let { g ->
            textPaint.color = ContextCompat.getColor(context, R.color.accent)
            canvas.drawText(when (g) { Gesture.LEFT -> "◀◀  en continu"; Gesture.RIGHT -> "en continu  ▶▶"; else -> "en continu" }, cx, cy - haloR - 14 * d, textPaint)
            textPaint.color = muted
        }
        if (touchX < 0 && height > 200 * d) {
            // Aide en bas du pavé, entre les deux barres
            textPaint.textSize = 11 * d
            val lines = hint.split("\n")
            lines.forEachIndexed { i, line -> canvas.drawText(line, cx, height - 18 * d - (lines.size - 1 - i) * 15 * d, textPaint) }
            textPaint.textSize = 12 * d
        }
        if (touchX >= 0 && strip == 0) canvas.drawCircle(touchX, touchY, 30 * d, touchPaint)

        if (stripsOn) {
            drawStrip(canvas, stripM, "CH", strip < 0, false)
            drawStrip(canvas, width - stripM - stripW, "VOL", strip > 0, true)
        }
    }

    private val stripFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stripPressed = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stripLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.12f }
    private val stripSign = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /** Barre verticale en pilule : « + » en haut, libellé au milieu, « − » en bas ; la partie touchée s'allume. */
    private fun drawStrip(canvas: Canvas, x: Float, label: String, active: Boolean, volume: Boolean) {
        val top = 16 * d; val bottom = height - 16 * d
        rect.set(x, top, x + stripW, bottom)
        stripFill.color = ContextCompat.getColor(context, R.color.surface); stripFill.alpha = 215
        stripFill.setShadowLayer(6 * d, 0f, 2 * d, 0x22000000)
        canvas.drawRoundRect(rect, stripW / 2, stripW / 2, stripFill)
        stripFill.clearShadowLayer()
        val accent = ContextCompat.getColor(context, R.color.accent)
        if (active) {
            stripPressed.color = accent; stripPressed.alpha = 40
            val h = bottom - top
            val r2 = when { stripPart > 0 || (!volume && stripPart == 0) -> RectF(x, top, x + stripW, top + h * 0.42f)
                stripPart < 0 -> RectF(x, bottom - h * 0.42f, x + stripW, bottom)
                else -> RectF(x, top + h * 0.38f, x + stripW, bottom - h * 0.38f) }
            canvas.save(); canvas.clipRect(r2); canvas.drawRoundRect(rect, stripW / 2, stripW / 2, stripPressed); canvas.restore()
        }
        stripSign.color = if (volume) accent else ink; stripSign.textSize = 24 * d
        canvas.drawText("+", rect.centerX(), top + 34 * d, stripSign)
        stripSign.color = ink
        canvas.drawText("−", rect.centerX(), bottom - 16 * d, stripSign)
        stripLabel.color = muted; stripLabel.textSize = 11 * d; stripLabel.isFakeBoldText = true
        val cy = (top + bottom) / 2f
        if (volume) {
            // Haut-parleur barré : toucher le milieu = muet
            canvas.drawText(label, rect.centerX(), cy + 22 * d, stripLabel)
            chevron.color = muted; chevron.strokeWidth = 1.8f * d
            path.reset()
            val sx = rect.centerX() - 7 * d; val sy = cy - 4 * d
            path.moveTo(sx, sy - 3 * d); path.lineTo(sx + 4 * d, sy - 3 * d); path.lineTo(sx + 8 * d, sy - 7 * d)
            path.lineTo(sx + 8 * d, sy + 7 * d); path.lineTo(sx + 4 * d, sy + 3 * d); path.lineTo(sx, sy + 3 * d); path.close()
            canvas.drawPath(path, chevron)
            canvas.drawLine(sx + 11 * d, sy - 3 * d, sx + 16 * d, sy + 3 * d, chevron)
            canvas.drawLine(sx + 16 * d, sy - 3 * d, sx + 11 * d, sy + 3 * d, chevron)
        } else canvas.drawText(label, rect.centerX(), cy + 4 * d, stripLabel)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                twoFinger = false; accX = 0f; accY = 0f; lastMoveX = e.x; lastMoveY = e.y; lastMoveT = SystemClock.uptimeMillis(); trail.clear()
                downX = e.x; downY = e.y; anchorX = e.x; anchorY = e.y
                touchX = e.x; touchY = e.y
                downTime = SystemClock.uptimeMillis()
                moved = false; longFired = false
                lastSwipe = null; stopHold()
                strip = when { !stripsOn -> 0; e.x < stripZone -> -1; e.x > width - stripZone -> 1; else -> 0 }
                if (strip != 0) {
                    val h = height.toFloat()
                    stripPart = when {
                        strip > 0 && e.y > h * 0.4f && e.y < h * 0.6f -> 0
                        e.y < h / 2f -> 1
                        else -> -1
                    }
                    stripFire()
                    if (!(strip > 0 && stripPart == 0)) postDelayed(stripRepeat, 450)
                } else if (!mouseMode) postDelayed(longPress, longPressMs)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = e.x; touchY = e.y
                if (mouseMode) {
                    removeCallbacks(longPress)
                    if (twoFinger && e.pointerCount >= 2) {
                        val y2 = (e.getY(0) + e.getY(1)) / 2f
                        scrollAccY += y2 - lastTwoY; lastTwoY = y2
                        val notch = 22 * d
                        while (abs(scrollAccY) >= notch) { scrollListener?.invoke(if (scrollAccY > 0) 1 else -1); scrollAccY -= if (scrollAccY > 0) notch else -notch }
                    } else if (!twoFinger) trackMove(e.x, e.y)
                    invalidate(); return true
                }
                val dx = e.x - anchorX; val dy = e.y - anchorY
                if (!moved && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) { moved = true; removeCallbacks(longPress) }
                if (strip != 0) {
                    // Glisser le long de la barre : un cran tous les ~26 dp (la répétition automatique s'arrête)
                    if (abs(dy) >= volStep) {
                        removeCallbacks(stripRepeat)
                        stripPart = if (dy < 0) 1 else -1
                        stripFire(); anchorY = e.y
                    }
                } else if (moved && !longFired) {
                    val g = when {
                        abs(dx) >= step && abs(dx) > abs(dy) -> if (dx > 0) Gesture.RIGHT else Gesture.LEFT
                        abs(dy) >= step && abs(dy) > abs(dx) -> if (dy > 0) Gesture.DOWN else Gesture.UP
                        else -> null
                    }
                    if (g != null) {
                        stopHold()
                        fire(g); anchorX = e.x; anchorY = e.y
                        // Doigt qui reste posé après le glissement : la flèche devient « maintenue »
                        lastSwipe = g; removeCallbacks(holdCheck); postDelayed(holdCheck, 380)
                    } else if (lastSwipe != null && holding == null && kotlin.math.hypot(dx, dy) > slop) {
                        // le doigt bouge encore : on repousse le passage en « maintenu »
                        removeCallbacks(holdCheck); postDelayed(holdCheck, 380)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (mouseMode && scrollListener != null) {
                twoFinger = true; moved = true; scrollAccY = 0f; lastTwoY = (e.getY(0) + e.getY(1)) / 2f
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress); removeCallbacks(stripRepeat); stopHold(); lastSwipe = null
                twoFinger = false
                if (mouseMode) {
                    releaseListener?.invoke()
                    if (!moved && SystemClock.uptimeMillis() - downTime < 350) fire(Gesture.TAP)
                    touchX = -1f; trail.clear(); invalidate(); performClick(); return true
                }
                if (!moved && !longFired && strip == 0) handleTap(e.x, e.y)
                strip = 0
                touchX = -1f; invalidate()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress); removeCallbacks(stripRepeat); stopHold(); lastSwipe = null; strip = 0
                releaseListener?.invoke(); touchX = -1f; trail.clear(); invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun handleTap(x: Float, y: Float) {
        val l = stripZone; val w = width - 2 * stripZone
        val zone = when { x < l + w / 3f -> -1; x > l + w * 2f / 3f -> 1; else -> 0 }
        val now = SystemClock.uptimeMillis()
        if (zone == 0) { fire(Gesture.TAP); return }  // centre : OK immédiat
        if (now - lastTapTime < doubleTapMs && zone == lastTapZone && pendingTap != null) {
            removeCallbacks(pendingTap); pendingTap = null; lastTapTime = 0
            fire(if (zone < 0) Gesture.SEEK_BACK else Gesture.SEEK_FORWARD)
            return
        }
        lastTapTime = now; lastTapZone = zone
        val r = Runnable { pendingTap = null; fire(Gesture.TAP) }
        pendingTap = r
        postDelayed(r, doubleTapMs)
    }

    private fun fire(g: Gesture) {
        if (g == Gesture.UP || g == Gesture.DOWN || g == Gesture.LEFT || g == Gesture.RIGHT || g == Gesture.TAP) {
            flashDir = g; removeCallbacks(clearFlash); postDelayed(clearFlash, 160); invalidate()
        }
        performHapticFeedback(if (g == Gesture.LONG_PRESS) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY)
        listener?.invoke(g)
    }
}
