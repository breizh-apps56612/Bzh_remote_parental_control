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
 *  - double-toucher sur le tiers gauche / droit = −10 s / +10 s ;
 *  - bande verticale à droite = volume (glisser vers le haut / le bas).
 */
class TouchpadView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Gesture { UP, DOWN, LEFT, RIGHT, TAP, LONG_PRESS, SEEK_BACK, SEEK_FORWARD, VOL_UP, VOL_DOWN }

    var listener: ((Gesture) -> Unit)? = null

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
    /** Bande de volume dans le pavé (désactivée : le volume a ses propres touches). */
    var showVolumeStrip = false
        set(v) { field = v; invalidate() }
    private val stripW get() = if (showVolumeStrip) 40 * d else 0f
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
    private var inStrip = false
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

        val padW = width - (if (showVolumeStrip) stripW + 10 * d else 0f)
        val cx = padW / 2f
        val cy = height / 2f
        val m = 36 * d
        val c = 9 * d
        fun chev(x: Float, y: Float, dir: Gesture) {
            chevron.color = if (flashDir == dir) ContextCompat.getColor(context, R.color.accent) else muted
            chevron.strokeWidth = (if (flashDir == dir) 3.4f else 2.4f) * d
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
        chev(cx, m, Gesture.UP); chev(cx, height - m, Gesture.DOWN)
        chev(m, cy, Gesture.LEFT); chev(padW - m, cy, Gesture.RIGHT)
        // Bouton « OK » central : halo indigo, pastille claire, point indigo (grossit au toucher)
        val tap = flashDir == Gesture.TAP
        centerDot.color = ContextCompat.getColor(context, R.color.accent)
        centerDot.alpha = if (tap) 70 else 34
        canvas.drawCircle(cx, cy, (if (tap) 62f else 56f) * d, centerDot)
        centerDot.color = ContextCompat.getColor(context, R.color.surface)
        centerDot.alpha = 255
        centerDot.setShadowLayer(10 * d, 0f, 4 * d, (ContextCompat.getColor(context, R.color.accent) and 0x00FFFFFF) or 0x55000000)
        canvas.drawCircle(cx, cy, (if (tap) 34f else 30f) * d, centerDot)
        centerDot.clearShadowLayer()
        centerDot.color = ContextCompat.getColor(context, R.color.accent)
        canvas.drawCircle(cx, cy, (if (tap) 10f else 7f) * d, centerDot)
        if (!mouseMode && touchX < 0) {
            // Aide sous le bouton central, seulement s'il reste la place avant la flèche du bas
            val lines = hint.split("\n")
            val y0 = cy + (if (tap) 62f else 56f) * d + 20 * d
            if (y0 + (lines.size - 1) * 16 * d < height - m - 22 * d)
                lines.forEachIndexed { i, line -> canvas.drawText(line, cx, y0 + i * 16 * d, textPaint) }
        }
        if (touchX >= 0) canvas.drawCircle(touchX, touchY, 34 * d, touchPaint)

        if (showVolumeStrip) {
            val sx = width - stripW - 10 * d
            rect.set(sx, 24 * d, sx + stripW, height - 24 * d)
            canvas.drawRoundRect(rect, stripW / 2, stripW / 2, stripPaint)
            canvas.drawText("+", rect.centerX(), rect.top + 26 * d, stripText)
            canvas.drawText("−", rect.centerX(), rect.bottom - 14 * d, stripText)
        }
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
                inStrip = showVolumeStrip && e.x > width - stripW - 18 * d
                if (!inStrip && !mouseMode) postDelayed(longPress, longPressMs)
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
                if (inStrip) {
                    if (abs(dy) >= volStep) { fire(if (dy < 0) Gesture.VOL_UP else Gesture.VOL_DOWN); anchorY = e.y }
                } else if (moved && !longFired) {
                    if (abs(dx) >= step && abs(dx) > abs(dy)) {
                        fire(if (dx > 0) Gesture.RIGHT else Gesture.LEFT); anchorX = e.x; anchorY = e.y
                    } else if (abs(dy) >= step && abs(dy) > abs(dx)) {
                        fire(if (dy > 0) Gesture.DOWN else Gesture.UP); anchorX = e.x; anchorY = e.y
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (mouseMode && scrollListener != null) {
                twoFinger = true; moved = true; scrollAccY = 0f; lastTwoY = (e.getY(0) + e.getY(1)) / 2f
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                twoFinger = false
                if (mouseMode) {
                    releaseListener?.invoke()
                    if (!moved && SystemClock.uptimeMillis() - downTime < 350) fire(Gesture.TAP)
                    touchX = -1f; trail.clear(); invalidate(); performClick(); return true
                }
                if (!moved && !longFired) handleTap(e.x, e.y)
                touchX = -1f; invalidate()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); releaseListener?.invoke(); touchX = -1f; trail.clear(); invalidate() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun handleTap(x: Float, y: Float) {
        if (inStrip) {
            fire(if (y < height / 2f) Gesture.VOL_UP else Gesture.VOL_DOWN); return
        }
        val padW = width - stripW
        val zone = when { x < padW / 3f -> -1; x > padW * 2f / 3f -> 1; else -> 0 }
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
