package fr.paul.telecommande.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import fr.paul.telecommande.R

/**
 * Curseur vertical façon table de mixage : on le monte ou on le descend du doigt (ou avec les flèches),
 * il s'arrête sur des crans (0..[steps]). Lisible au lecteur d'écran (« Semaine : 1 h 15 »).
 */
class FaderView(c: Context) : View(c) {
    var steps = 10
        set(v) { field = v.coerceAtLeast(1); value = value.coerceIn(0, field); invalidate() }
    var value = 0
        set(v) { field = v.coerceIn(0, steps); invalidate() }
    /** Nom du curseur et texte de la valeur, pour l'accessibilité. */
    var label = ""
    var valueText: (Int) -> String = { it.toString() }
    var onChange: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF; strokeCap = Paint.Cap.ROUND; strokeWidth = 6 * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.color(c, R.color.mint); strokeCap = Paint.Cap.ROUND; strokeWidth = 6 * d }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; strokeWidth = 1.5f * d }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val knobLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.color(c, R.color.hero_bg); strokeWidth = 2 * d }
    private val focus = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.color(c, R.color.mint); style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val rect = RectF()

    init {
        isFocusable = true; isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private val pad get() = 14 * d
    private fun yFor(v: Int) = pad + (height - 2 * pad) * (1f - v.toFloat() / steps)

    override fun onDraw(cv: Canvas) {
        val x = width / 2f
        val top = pad; val bottom = height - pad
        if (steps <= 12) for (i in 0..steps) { val y = yFor(i); cv.drawLine(x - 14 * d, y, x - 9 * d, y, tick); cv.drawLine(x + 9 * d, y, x + 14 * d, y, tick) }
        cv.drawLine(x, top, x, bottom, track)
        val ky = yFor(value)
        cv.drawLine(x, ky, x, bottom, fill)
        rect.set(x - 18 * d, ky - 10 * d, x + 18 * d, ky + 10 * d)
        cv.drawRoundRect(rect, 7 * d, 7 * d, knob)
        cv.drawLine(x - 9 * d, ky, x + 9 * d, ky, knobLine)
        if (isFocused) { rect.inset(-4 * d, -4 * d); cv.drawRoundRect(rect, 10 * d, 10 * d, focus) }
    }

    private fun set(v: Int) {
        val n = v.coerceIn(0, steps)
        if (n == value) return
        value = n
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        contentDescription = "$label : ${valueText(n)}"
        onChange?.invoke(n)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val r = 1f - ((e.y - pad) / (height - 2 * pad)).coerceIn(0f, 1f)
                set(Math.round(r * steps))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); return true }
        }
        return super.onTouchEvent(e)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> { set(value + 1); true }
        KeyEvent.KEYCODE_DPAD_DOWN -> { set(value - 1); true }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect); invalidate()
    }

    // Lecteur d'écran : glisser vers le haut / le bas = monter / descendre d'un cran
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.SeekBar::class.java.name
        info.contentDescription = "$label : ${valueText(value)}"
        if (value < steps) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        if (value > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { set(value + 1); true }
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { set(value - 1); true }
        else -> super.performAccessibilityAction(action, arguments)
    }
}
